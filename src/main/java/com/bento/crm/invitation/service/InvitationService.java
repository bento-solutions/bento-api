package com.bento.crm.invitation.service;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.service.AuthService;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.model.Account;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AccountRepository;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.identity.service.AccountService;
import com.bento.crm.invitation.dto.AcceptInvitationRequest;
import com.bento.crm.invitation.dto.CreateInvitationRequest;
import com.bento.crm.invitation.dto.InvitationPreviewResponse;
import com.bento.crm.invitation.dto.InvitationResponse;
import com.bento.crm.invitation.model.InvitationStatus;
import com.bento.crm.invitation.model.UserInvitation;
import com.bento.crm.invitation.repository.UserInvitationRepository;
import com.bento.crm.identity.model.Team;
import com.bento.crm.identity.repository.TeamRepository;
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
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final EmailService emailService;
    private final InvitationProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AccountService accountService;
    private final EntityManager entityManager;

    // ---------------------------------------------------------------- admin side

    @Transactional
    public InvitationResponse invite(CreateInvitationRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        String email = normalizeEmail(request.getEmail());

        // Someone who left, or was deactivated, can be invited back: accepting reactivates them.
        userRepository.findByOrganizationIdAndEmail(orgId, email)
                .filter(existing -> Boolean.TRUE.equals(existing.getIsActive()))
                .ifPresent(existing -> {
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

        String emailToken = generateToken();
        String shareToken = generateToken();
        UserInvitation invitation = UserInvitation.builder()
                .email(email)
                .role(UserRole.valueOf(request.getRole()))
                .teamId(teamId)
                .displayName(blankToNull(request.getDisplayName()))
                .jobTitle(blankToNull(request.getJobTitle()))
                .language(request.getLanguage() != null ? request.getLanguage() : "en")
                .tokenHash(hashToken(emailToken))
                .shareTokenHash(hashToken(shareToken))
                .rawToken(shareToken)
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(properties.getExpiryDays(), ChronoUnit.DAYS))
                .sendCount(1)
                .lastSentAt(Instant.now())
                .build();
        invitation.setOrganizationId(orgId);

        invitation = invitationRepository.save(invitation);
        dispatchInvitationEmail(invitation, emailToken);

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
     * Issues fresh tokens rather than re-mailing the old one, so a link that leaked into a
     * forwarded email or a shared inbox, or a copied link, stops working the moment the invitation
     * is resent.
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

        String emailToken = generateToken();
        String shareToken = generateToken();
        invitation.setTokenHash(hashToken(emailToken));
        invitation.setShareTokenHash(hashToken(shareToken));
        invitation.setRawToken(shareToken);
        invitation.setExpiresAt(Instant.now().plus(properties.getExpiryDays(), ChronoUnit.DAYS));
        invitation.setLastSentAt(Instant.now());
        invitation.setSendCount(invitation.getSendCount() + 1);

        invitation = invitationRepository.save(invitation);
        dispatchInvitationEmail(invitation, emailToken);

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
        invitation.setShareTokenHash(null);

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
        UserInvitation invitation = requireAcceptable(token).invitation();

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
                .hasAccount(accountRepository.findByEmail(Account.normalizeEmail(invitation.getEmail())).isPresent())
                .build();
    }

    /** A session, or the address a confirmation link went to when the invitee must confirm it first. */
    public record AcceptOutcome(LoginResponse session, String checkEmail) {
    }

    /**
     * Creates the Account and its Membership, and signs the invitee in. Unauthenticated, so every
     * field that determines access -- organization, role, team, email -- comes from the stored
     * invitation and never from the request body; the invitee only supplies their name, password
     * and phone.
     *
     * <p>The emailed link confirms the address. A copied link doesn't, so when addresses must be
     * verified the invitee is sent a confirmation link instead of a session.
     */
    @Transactional
    public AcceptOutcome accept(AcceptInvitationRequest request) {
        AcceptableInvitation acceptable = requireAcceptable(request.getToken());
        UserInvitation invitation = acceptable.invitation();
        UUID orgId = invitation.getOrganizationId();

        // A person who already has an Account joins by signing in and accepting from there. Setting
        // a password here would let whoever holds the link take over that Account.
        if (accountRepository.findByEmail(Account.normalizeEmail(invitation.getEmail())).isPresent()) {
            throw new IllegalStateException("An account already exists for this email. Sign in instead.");
        }
        Account account = accountRepository.save(Account.builder()
                .email(invitation.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .passwordChangedAt(Instant.now())
                .emailVerifiedAt(acceptable.emailed() ? Instant.now() : null)
                .build());

        String displayName = request.getDisplayName().trim();
        AppUser user = AppUser.builder()
                .accountId(account.getId())
                .email(invitation.getEmail())
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
        invitation.setShareTokenHash(null);
        invitationRepository.save(invitation);

        log.info("Invitation {} accepted through the {} link -- user {} created in organization {}",
                invitation.getId(), acceptable.emailed() ? "emailed" : "copied", user.getId(), orgId);

        if (accountService.verificationRequired() && !account.isEmailVerified()) {
            accountService.sendVerification(account);
            return new AcceptOutcome(null, account.getEmail());
        }
        return new AcceptOutcome(authService.issueSession(user), null);
    }

    /**
     * Adds a Membership in the inviting Organization to the signed-in person's Account. The
     * emailed token is required as well as a matching address: an address alone is not proof that
     * the caller reads that inbox.
     */
    @Transactional
    public LoginResponse acceptForLoggedInUser(UUID invitationId, String token, UUID currentUserId) {
        AcceptableInvitation acceptable = requireAcceptable(token);
        UserInvitation invitation = acceptable.invitation();
        if (!invitation.getId().equals(invitationId)) {
            throw new ResourceNotFoundException("This invitation link is not valid or has expired");
        }

        AppUser currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));

        if (!invitation.getEmail().equalsIgnoreCase(currentUser.getEmail())) {
            throw new IllegalStateException("This invitation was sent to a different email address");
        }

        // Extract needed fields and detach currentUser from Hibernate session
        UUID accountId = currentUser.getAccountId();
        String email = currentUser.getEmail();
        String displayName = currentUser.getDisplayName() != null ? currentUser.getDisplayName() : invitation.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = email.split("@")[0];
        }
        String jobTitle = invitation.getJobTitle() != null ? invitation.getJobTitle() : currentUser.getJobTitle();
        String phone = currentUser.getPhone();
        String language = currentUser.getLanguage() != null ? currentUser.getLanguage() : invitation.getLanguage();

        try {
            entityManager.detach(currentUser);
        } catch (Exception ignored) {
        }

        UUID targetOrgId = invitation.getOrganizationId();

        // Switch to the target tenant context for the rest of this request and commit
        TenantContext.setCurrentOrganizationId(targetOrgId);
        updateHibernateTenantFilter(targetOrgId);

        UUID assignedTeamId = invitation.getTeamId();
        if (assignedTeamId != null && teamRepository.findByOrganizationIdAndId(targetOrgId, assignedTeamId).isEmpty()) {
            assignedTeamId = null;
        }

        Optional<AppUser> existingInTarget = userRepository.findByAccountIdAndOrganizationIdAcrossOrganizations(accountId, targetOrgId);
        AppUser targetUser;
        if (existingInTarget.isPresent()) {
            targetUser = existingInTarget.get();
            targetUser.setRole(invitation.getRole());
            targetUser.setTeamId(assignedTeamId);
            if (invitation.getJobTitle() != null) {
                targetUser.setJobTitle(invitation.getJobTitle());
            }
            targetUser.setIsActive(true);
            targetUser = userRepository.save(targetUser);
        } else {
            targetUser = AppUser.builder()
                    .accountId(accountId)
                    .email(email)
                    .displayName(displayName)
                    .initials(deriveInitials(displayName))
                    .role(invitation.getRole())
                    .teamId(assignedTeamId)
                    .jobTitle(jobTitle)
                    .phone(phone)
                    .language(language)
                    .isActive(true)
                    .build();
            targetUser.setOrganizationId(targetOrgId);
            targetUser = userRepository.save(targetUser);
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitation.setAcceptedUserId(targetUser.getId());
        invitation.setRawToken(null);
        invitation.setShareTokenHash(null);
        invitationRepository.save(invitation);

        if (acceptable.emailed()) {
            accountService.markVerified(accountId);
        }

        log.info("Invitation {} accepted by logged-in user {} -- joined organization {}",
                invitation.getId(), currentUserId, targetOrgId);

        return authService.issueSession(targetUser);
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

    // ------------------------------------------------------------------ helpers

    /**
     * Every rejection reports the same message. Distinguishing "no such token" from "revoked"
     * or "already accepted" would let anyone holding a guess probe which tokens exist.
     */
    private AcceptableInvitation requireAcceptable(String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("This invitation link is not valid or has expired");
        }
        String hash = hashToken(token.trim());
        AcceptableInvitation acceptable = invitationRepository.findByTokenHash(hash)
                .map(invitation -> new AcceptableInvitation(invitation, true))
                .or(() -> invitationRepository.findByShareTokenHash(hash)
                        .map(invitation -> new AcceptableInvitation(invitation, false)))
                .orElseThrow(() -> new ResourceNotFoundException("This invitation link is not valid or has expired"));

        UserInvitation invitation = acceptable.invitation();
        if (invitation.getStatus() != InvitationStatus.PENDING || invitation.isExpired()) {
            throw new ResourceNotFoundException("This invitation link is not valid or has expired");
        }
        return acceptable;
    }

    /** An invitation that can still be accepted, and whether the link used was the emailed one. */
    private record AcceptableInvitation(UserInvitation invitation, boolean emailed) {
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
