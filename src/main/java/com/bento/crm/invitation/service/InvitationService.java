package com.bento.crm.invitation.service;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.service.AuthService;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.invitation.dto.AcceptInvitationRequest;
import com.bento.crm.invitation.dto.CreateInvitationRequest;
import com.bento.crm.invitation.dto.InvitationPreviewResponse;
import com.bento.crm.invitation.dto.InvitationResponse;
import com.bento.crm.invitation.model.InvitationStatus;
import com.bento.crm.invitation.model.UserInvitation;
import com.bento.crm.invitation.repository.UserInvitationRepository;
import com.bento.crm.identity.model.Team;
import com.bento.crm.identity.repository.TeamRepository;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("UTC"));

    private final UserInvitationRepository invitationRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final InvitationProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final EntityManager entityManager;

    // ---------------------------------------------------------------- admin side

    @Transactional
    public InvitationResponse invite(CreateInvitationRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        String email = normalizeEmail(request.getEmail());

        userRepository.findByOrganizationIdAndEmail(orgId, email).ifPresent(existing -> {
            throw new IllegalStateException("A user with this email already exists in the organization");
        });
        // The unique index only covers PENDING rows, so this check and the index agree: an
        // accepted or revoked invitation never blocks a new one.
        invitationRepository.findByOrganizationIdAndEmailAndStatus(orgId, email, InvitationStatus.PENDING)
                .ifPresent(existing -> {
                    throw new IllegalStateException("An invitation is already pending for this email. Resend or revoke it instead.");
                });

        UUID teamId = parseUuid(request.getTeamId());
        if (teamId != null) {
            teamRepository.findByOrganizationIdAndId(orgId, teamId)
                    .orElseThrow(() -> new ResourceNotFoundException("Selected team does not exist in this organization"));
        }

        String token = generateToken();
        UserInvitation invitation = UserInvitation.builder()
                .email(email)
                .role(UserRole.valueOf(request.getRole()))
                .teamId(teamId)
                .displayName(blankToNull(request.getDisplayName()))
                .jobTitle(blankToNull(request.getJobTitle()))
                .language(request.getLanguage() != null ? request.getLanguage() : "en")
                .tokenHash(hashToken(token))
                .rawToken(token)
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(properties.getExpiryDays(), ChronoUnit.DAYS))
                .sendCount(1)
                .lastSentAt(Instant.now())
                .build();
        invitation.setOrganizationId(orgId);

        invitation = invitationRepository.save(invitation);
        dispatchInvitationEmail(invitation, token);
        notifyExistingUsersIfAny(invitation);

        log.info("Invitation {} created for {} in organization {}", invitation.getId(), email, orgId);
        return toResponse(invitation);
    }


    public List<InvitationResponse> list() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return invitationRepository.findByOrganizationId(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Issues a fresh token rather than re-mailing the old one, so a link that leaked into a
     * forwarded email or a shared inbox stops working the moment the invitation is resent.
     * Also revives an expired invitation by extending its deadline.
     */
    @Transactional
    public InvitationResponse resend(UUID invitationId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        UserInvitation invitation = invitationRepository.findByOrganizationIdAndId(orgId, invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only a pending invitation can be resent");
        }

        String token = generateToken();
        invitation.setTokenHash(hashToken(token));
        invitation.setRawToken(token);
        invitation.setExpiresAt(Instant.now().plus(properties.getExpiryDays(), ChronoUnit.DAYS));
        invitation.setLastSentAt(Instant.now());
        invitation.setSendCount(invitation.getSendCount() + 1);

        invitation = invitationRepository.save(invitation);
        dispatchInvitationEmail(invitation, token);
        notifyExistingUsersIfAny(invitation);

        log.info("Invitation {} resent to {}", invitation.getId(), invitation.getEmail());
        return toResponse(invitation);
    }

    @Transactional
    public InvitationResponse revoke(UUID invitationId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        UserInvitation invitation = invitationRepository.findByOrganizationIdAndId(orgId, invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new IllegalStateException("This invitation has already been accepted");
        }

        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setRevokedAt(Instant.now());
        invitation.setRawToken(null);
        // Clearing the hash makes the outstanding link unusable immediately; a random value
        // rather than null keeps the NOT NULL/UNIQUE constraints satisfiable for later rows.
        invitation.setTokenHash(hashToken(generateToken()));

        invitation = invitationRepository.save(invitation);
        log.info("Invitation {} revoked for {}", invitation.getId(), invitation.getEmail());
        return toResponse(invitation);
    }

    /** Pre-assigned role and team must still resolve when the invitee finally accepts. */
    @Transactional
    public InvitationResponse update(UUID invitationId, CreateInvitationRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        UserInvitation invitation = invitationRepository.findByOrganizationIdAndId(orgId, invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only a pending invitation can be edited");
        }

        UUID teamId = parseUuid(request.getTeamId());
        if (teamId != null) {
            teamRepository.findByOrganizationIdAndId(orgId, teamId)
                    .orElseThrow(() -> new ResourceNotFoundException("Selected team does not exist in this organization"));
        }

        invitation.setRole(UserRole.valueOf(request.getRole()));
        invitation.setTeamId(teamId);
        invitation.setDisplayName(blankToNull(request.getDisplayName()));
        invitation.setJobTitle(blankToNull(request.getJobTitle()));
        if (request.getLanguage() != null) {
            invitation.setLanguage(request.getLanguage());
        }

        return toResponse(invitationRepository.save(invitation));
    }

    // ------------------------------------------------------------- invitee side

    /**
     * Unauthenticated. Runs outside any tenant context, so it must not touch
     * {@link TenantContext} -- the token itself selects the organization.
     */
    public InvitationPreviewResponse preview(String token) {
        UserInvitation invitation = requireAcceptable(token);

        Organization organization = organizationRepository.findById(invitation.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        String teamName = invitation.getTeamId() != null
                ? teamRepository.findById(invitation.getTeamId()).map(Team::getName).orElse(null)
                : null;

        return InvitationPreviewResponse.builder()
                .id(invitation.getId())
                .email(invitation.getEmail())
                .organizationName(organization.getName())
                .role(invitation.getRole().name())
                .teamId(invitation.getTeamId())
                .teamName(teamName)
                .displayName(invitation.getDisplayName())
                .jobTitle(invitation.getJobTitle())
                .invitedByName(inviterName(invitation).orElse(null))
                .expiresAt(invitation.getExpiresAt())
                .build();
    }

    /**
     * Creates the AppUser and signs the invitee in. Unauthenticated, so every field that
     * determines access -- organization, role, team, email -- comes from the stored invitation
     * and never from the request body; the invitee only supplies their name, password and phone.
     */
    @Transactional
    public LoginResponse accept(AcceptInvitationRequest request) {
        UserInvitation invitation = requireAcceptable(request.getToken());
        UUID orgId = invitation.getOrganizationId();

        // Between the invite going out and it being accepted, someone may have created the
        // account by hand.
        if (userRepository.findByOrganizationIdAndEmail(orgId, invitation.getEmail()).isPresent()) {
            throw new IllegalStateException("An account already exists for this email. Sign in instead.");
        }

        String displayName = request.getDisplayName().trim();
        AppUser user = AppUser.builder()
                .email(invitation.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(displayName)
                .initials(deriveInitials(displayName))
                .role(invitation.getRole())
                .teamId(invitation.getTeamId())
                .jobTitle(invitation.getJobTitle())
                .phone(blankToNull(request.getPhone()))
                .language(invitation.getLanguage())
                .isActive(true)
                .build();
        user.setOrganizationId(orgId);
        user = userRepository.save(user);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitation.setAcceptedUserId(user.getId());
        invitation.setRawToken(null);
        invitationRepository.save(invitation);

        log.info("Invitation {} accepted -- user {} created in organization {}",
                invitation.getId(), user.getId(), orgId);

        return authService.issueSession(user);
    }

    /**
     * Accepts an invitation on behalf of an already logged-in user.
     * Reuses their existing password credentials and links them directly to the new organization.
     */
    @Transactional
    public LoginResponse acceptForLoggedInUser(UUID invitationId, UUID currentUserId) {
        UserInvitation invitation = invitationRepository.findByIdAcrossOrganizations(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("This invitation is not valid or has expired"));

        if (invitation.getStatus() != InvitationStatus.PENDING || invitation.isExpired()) {
            throw new IllegalStateException("This invitation is not valid or has expired");
        }

        AppUser currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));

        if (!invitation.getEmail().equalsIgnoreCase(currentUser.getEmail())) {
            throw new IllegalStateException("This invitation was sent to a different email address");
        }

        UUID targetOrgId = invitation.getOrganizationId();
        UUID originalTenant = TenantContext.currentOrganizationIdOrNull();

        try {
            TenantContext.setCurrentOrganizationId(targetOrgId);
            updateHibernateTenantFilter(targetOrgId);

            Optional<AppUser> existingInTarget = userRepository.findByOrganizationIdAndEmailAcrossOrganizations(targetOrgId, currentUser.getEmail());
            AppUser targetUser;
            if (existingInTarget.isPresent()) {
                targetUser = existingInTarget.get();
                targetUser.setRole(invitation.getRole());
                targetUser.setTeamId(invitation.getTeamId());
                if (invitation.getJobTitle() != null) {
                    targetUser.setJobTitle(invitation.getJobTitle());
                }
                targetUser.setIsActive(true);
                targetUser = userRepository.save(targetUser);
            } else {
                String displayName = currentUser.getDisplayName() != null ? currentUser.getDisplayName() : invitation.getDisplayName();
                if (displayName == null || displayName.isBlank()) {
                    displayName = currentUser.getEmail().split("@")[0];
                }
                targetUser = AppUser.builder()
                        .email(currentUser.getEmail())
                        .passwordHash(currentUser.getPasswordHash())
                        .displayName(displayName)
                        .initials(deriveInitials(displayName))
                        .role(invitation.getRole())
                        .teamId(invitation.getTeamId())
                        .jobTitle(invitation.getJobTitle() != null ? invitation.getJobTitle() : currentUser.getJobTitle())
                        .phone(currentUser.getPhone())
                        .language(currentUser.getLanguage() != null ? currentUser.getLanguage() : invitation.getLanguage())
                        .isActive(true)
                        .build();
                targetUser.setOrganizationId(targetOrgId);
                targetUser = userRepository.save(targetUser);
            }

            invitation.setStatus(InvitationStatus.ACCEPTED);
            invitation.setAcceptedAt(Instant.now());
            invitation.setAcceptedUserId(targetUser.getId());
            invitation.setRawToken(null);
            invitationRepository.save(invitation);

            entityManager.flush();

            log.info("Invitation {} accepted by logged-in user {} -- joined organization {}",
                    invitation.getId(), currentUser.getId(), targetOrgId);

            return authService.issueSession(targetUser);
        } finally {
            if (originalTenant != null) {
                TenantContext.setCurrentOrganizationId(originalTenant);
                updateHibernateTenantFilter(originalTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void updateHibernateTenantFilter(UUID orgId) {
        try {
            Session session = entityManager.unwrap(Session.class);
            if (orgId != null) {
                session.enableFilter("organizationFilter").setParameter("organizationId", orgId);
            } else {
                session.disableFilter("organizationFilter");
            }
        } catch (Exception ignored) {
        }
    }

    public List<InvitationResponse> listPendingForUser(UUID currentUserId) {
        AppUser user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return invitationRepository.findPendingByEmailAcrossOrganizations(user.getEmail()).stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Checks if the invited email already belongs to one or more AppUsers across any tenant.
     * If so, generates an in-app notification for each existing user account.
     */
    private void notifyExistingUsersIfAny(UserInvitation invitation) {
        try {
            List<AppUser> existingUsers = userRepository.findAllByEmailAcrossOrganizations(invitation.getEmail());
            if (existingUsers.isEmpty()) {
                return;
            }

            String organizationName = organizationRepository.findById(invitation.getOrganizationId())
                    .map(Organization::getName)
                    .orElse("an organization");

            String teamName = invitation.getTeamId() != null
                    ? teamRepository.findById(invitation.getTeamId()).map(Team::getName).orElse(null)
                    : null;

            String teamSuffix = teamName != null ? " in team " + teamName : "";
            String message = String.format("You have been invited to join %s as %s%s. Click to view and accept the invitation.",
                    organizationName, roleLabel(invitation.getRole()), teamSuffix);

            for (AppUser existing : existingUsers) {
                // Do not notify inside the same organization where the invitation is being created
                if (invitation.getOrganizationId().equals(existing.getOrganizationId())) {
                    continue;
                }
                notificationService.createCrossTenantNotification(
                        existing.getOrganizationId(),
                        existing.getId(),
                        Notification.NotificationType.INVITATION,
                        "Invitation to join " + organizationName,
                        message,
                        "INVITATION",
                        invitation.getId()
                );
                log.info("Dispatched in-app invitation notification to user {} in org {} for invitation {}",
                        existing.getId(), existing.getOrganizationId(), invitation.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to dispatch in-app notification for invitation {}: {}", invitation.getId(), e.getMessage());
        }
    }

    /**
     * Every rejection reports the same message. Distinguishing "no such token" from "revoked"
     * or "already accepted" would let anyone holding a guess probe which tokens exist.
     */
    private UserInvitation requireAcceptable(String token) {
        UserInvitation invitation = invitationRepository.findByTokenHash(hashToken(token))
                .orElseThrow(() -> new ResourceNotFoundException("This invitation link is not valid or has expired"));

        if (invitation.getStatus() != InvitationStatus.PENDING || invitation.isExpired()) {
            throw new ResourceNotFoundException("This invitation link is not valid or has expired");
        }
        return invitation;
    }

    private void dispatchInvitationEmail(UserInvitation invitation, String token) {
        String organizationName = organizationRepository.findById(invitation.getOrganizationId())
                .map(Organization::getName)
                .orElse("your organization");

        String separator = properties.getAcceptUrl().contains("?") ? "&" : "?";
        String acceptUrl = properties.getAcceptUrl() + separator + "token=" + token;

        String teamName = invitation.getTeamId() != null
                ? teamRepository.findById(invitation.getTeamId()).map(Team::getName).orElse(null)
                : null;

        emailService.sendHtml(
                invitation.getEmail(),
                "You have been invited to join " + organizationName,
                "invitation",
                Map.of(
                        "appName", "Bento CRM",
                        "organizationName", organizationName,
                        "inviterName", inviterName(invitation).orElse("An administrator"),
                        "roleLabel", roleLabel(invitation.getRole()) + (teamName != null ? " (" + teamName + ")" : ""),
                        "acceptUrl", acceptUrl,
                        "email", invitation.getEmail(),
                        "expiresAt", EXPIRY_FORMAT.format(invitation.getExpiresAt())
                ));
    }

    private Optional<String> inviterName(UserInvitation invitation) {
        if (invitation.getCreatedBy() == null) {
            return Optional.empty();
        }
        return userRepository.findById(invitation.getCreatedBy()).map(AppUser::getDisplayName);
    }

    private InvitationResponse toResponse(UserInvitation invitation) {
        String teamName = invitation.getTeamId() != null
                ? teamRepository.findById(invitation.getTeamId()).map(Team::getName).orElse(null)
                : null;
        String orgName = organizationRepository.findById(invitation.getOrganizationId())
                .map(Organization::getName)
                .orElse(null);
        return InvitationResponse.fromEntity(invitation, inviterName(invitation).orElse(null), teamName, properties.getAcceptUrl(), orgName);
    }

    private static String roleLabel(UserRole role) {
        return switch (role) {
            case ADMIN -> "an Admin";
            case MANAGER -> "a Manager";
            case SALESPERSON -> "a Salesperson";
            case SUPPORT -> "a Support Specialist";
            case VIEWER -> "a Viewer";
        };
    }

    /** 256 bits, URL-safe: it travels in a query string and must survive email clients intact. */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash invitation token", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String deriveInitials(String displayName) {
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase(Locale.ROOT);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("undefined")) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
