package com.bento.crm.portal.service;

import com.bento.crm.auth.service.JwtService;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.portal.dto.PortalDtos;
import com.bento.crm.portal.model.PortalUser;
import com.bento.crm.portal.repository.PortalUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import com.bento.crm.common.exception.AuthenticationFailedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Client portal accounts: staff invite people at a client company; they set a password from the
 * emailed link and sign in. A portal account only ever sees its own company's data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAccountService {

    static final Duration INVITE_TTL = Duration.ofDays(7);
    static final Duration RESET_TTL = Duration.ofHours(2);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Compared against when no account matches, so a wrong email costs the same time as a wrong password. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5wWyS2xBEz6JHq/yQyQ0K3rTt5aqQxW";

    private final PortalUserRepository repository;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    // Staff side

    public List<PortalDtos.Account> accounts(UUID partnerId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        partner(orgId, partnerId);
        return repository.forPartner(orgId, partnerId).stream().map(PortalAccountService::view).toList();
    }

    @Transactional
    public PortalDtos.Invited invite(PortalDtos.InviteRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if (r.partnerId() == null) throw new IllegalArgumentException("Choose the client");
        Partner partner = partner(orgId, r.partnerId());
        String email = normalize(r.email());
        PortalUser u = repository.byEmail(orgId, email).orElse(null);
        if (u != null && !u.getPartnerId().equals(r.partnerId())) {
            throw new IllegalStateException("This email already has portal access for another client");
        }
        if (u == null) {
            u = new PortalUser();
            u.setOrganizationId(orgId);
            u.setPartnerId(r.partnerId());
            u.setEmail(email);
        }
        if (r.displayName() != null && !r.displayName().isBlank()) u.setDisplayName(cut(r.displayName().strip(), 120));
        u.setActive(true);
        String token = newToken();
        u.setInviteTokenHash(hash(token));
        u.setInviteExpiresAt(Instant.now().plus(INVITE_TTL));
        try {
            u = repository.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This email already has portal access");
        }
        String url = frontendUrl + "/portal/accept?token=" + token;
        String firm = firmName(orgId);
        boolean emailed = send(email, "Votre espace client " + firm, """
                <p>Bonjour%s,</p>
                <p>%s vous ouvre un espace client pour <strong>%s</strong> : vos factures, les documents à envoyer,
                l'avancement de vos dossiers et vos échéances fiscales.</p>
                <p><a href="%s">Créer mon mot de passe</a></p>
                <p>Ce lien est valable 7 jours.</p>
                """.formatted(u.getDisplayName() == null ? "" : " " + HtmlUtils.htmlEscape(u.getDisplayName()),
                HtmlUtils.htmlEscape(firm), HtmlUtils.htmlEscape(DriveService.displayName(partner)), HtmlUtils.htmlEscape(url)));
        return new PortalDtos.Invited(view(u), url, emailed);
    }

    @Transactional
    public PortalDtos.Account update(UUID id, PortalDtos.AccountUpdate r) {
        PortalUser u = account(id);
        if (r.active() != null) u.setActive(r.active());
        if (r.displayName() != null) u.setDisplayName(r.displayName().isBlank() ? null : cut(r.displayName().strip(), 120));
        return view(repository.saveAndFlush(u));
    }

    @Transactional
    public void delete(UUID id) {
        PortalUser u = account(id);
        u.setDeletedAt(Instant.now());
        u.setInviteTokenHash(null);
        repository.save(u);
    }

    // Sign-in (no tenant context: these run before the person is known)

    @Transactional
    public Object login(PortalDtos.LoginRequest r) {
        String email = r.email() == null ? "" : r.email().trim().toLowerCase(Locale.ROOT);
        String password = r.password() == null ? "" : r.password();
        List<PortalUser> matches = repository.activeByEmail(email).stream()
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(password, u.getPasswordHash()))
                .filter(u -> r.organizationId() == null || u.getOrganizationId().equals(r.organizationId()))
                .toList();
        if (matches.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new AuthenticationFailedException("Wrong email or password");
        }
        if (matches.size() > 1) {
            return new PortalDtos.ChooseFirm(matches.stream()
                    .map(u -> new PortalDtos.FirmChoice(u.getOrganizationId(), firmName(u.getOrganizationId()))).toList());
        }
        return session(matches.get(0));
    }

    @Transactional
    public PortalDtos.Session accept(PortalDtos.AcceptRequest r) {
        PortalUser u = byToken(r.token());
        if (r.displayName() != null && !r.displayName().isBlank()) u.setDisplayName(cut(r.displayName().strip(), 120));
        setPassword(u, r.password());
        return session(u);
    }

    @Transactional
    public PortalDtos.Session reset(PortalDtos.ResetRequest r) {
        PortalUser u = byToken(r.token());
        setPassword(u, r.password());
        return session(u);
    }

    /** Always succeeds, so the answer does not tell whether the email has an account. */
    @Transactional
    public void forgot(PortalDtos.ForgotRequest r) {
        String email = r.email() == null ? "" : r.email().trim().toLowerCase(Locale.ROOT);
        for (PortalUser u : repository.activeByEmail(email)) {
            if (u.getPasswordHash() == null) continue; // not accepted yet: the invitation still works
            String token = newToken();
            u.setInviteTokenHash(hash(token));
            u.setInviteExpiresAt(Instant.now().plus(RESET_TTL));
            repository.save(u);
            String url = frontendUrl + "/portal/reset?token=" + token;
            send(u.getEmail(), "Nouveau mot de passe - " + firmName(u.getOrganizationId()), """
                    <p>Bonjour,</p><p>Pour choisir un nouveau mot de passe pour votre espace client, ouvrez ce lien
                    (valable 2 heures) :</p><p><a href="%s">Choisir un mot de passe</a></p>
                    <p>Si vous n'avez rien demandé, ignorez ce message.</p>""".formatted(HtmlUtils.htmlEscape(url)));
        }
    }

    public PortalDtos.Me me(UUID orgId, UUID portalUserId) {
        PortalUser u = repository.find(orgId, portalUserId).filter(PortalUser::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        Partner p = partner(orgId, u.getPartnerId());
        var org = organizationRepository.findById(orgId).orElseThrow();
        return new PortalDtos.Me(u.getId(), u.getEmail(), u.getDisplayName(), p.getId(), DriveService.displayName(p),
                orgId, org.getName(), org.getLogoUrl());
    }

    private PortalDtos.Session session(PortalUser u) {
        u.setLastLoginAt(Instant.now());
        repository.saveAndFlush(u);
        String token = jwtService.generatePortalToken(u.getId(), u.getOrganizationId(), u.getPartnerId());
        return new PortalDtos.Session(token, JwtService.PORTAL_TOKEN_SECONDS, me(u.getOrganizationId(), u.getId()));
    }

    private PortalUser byToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("This link is not valid");
        PortalUser u = repository.byInviteHash(hash(token.trim()))
                .orElseThrow(() -> new IllegalArgumentException("This link is not valid or was already used"));
        if (!u.isActive() || u.getInviteExpiresAt() == null || u.getInviteExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This link has expired: ask for a new one");
        }
        return u;
    }

    private void setPassword(PortalUser u, String password) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Choose a password of at least 8 characters");
        if (password.length() > 128) throw new IllegalArgumentException("That password is too long");
        u.setPasswordHash(passwordEncoder.encode(password));
        // Whole seconds, like the issue time of a session, so the new session still counts.
        u.setPasswordChangedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        u.setInviteTokenHash(null);
        u.setInviteExpiresAt(null);
    }

    /** Tells the client's portal users that a document of their mission is ready to download. */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void onDelivered(com.bento.crm.mission.service.MissionService.DeliverableDelivered e) {
        String firm = firmName(e.organizationId());
        String link = frontendUrl + "/portal/missions";
        for (PortalUser u : repository.forPartner(e.organizationId(), e.partnerId())) {
            if (!u.isActive() || u.getPasswordHash() == null) continue;
            send(u.getEmail(), "Document disponible : " + e.title(), """
                    <p>Bonjour,</p><p>%s a terminé <strong>%s</strong> pour la mission « %s ».</p>
                    <p><a href="%s">Voir sur votre espace client</a></p>""".formatted(HtmlUtils.htmlEscape(firm),
                    HtmlUtils.htmlEscape(e.title()), HtmlUtils.htmlEscape(e.missionName()), HtmlUtils.htmlEscape(link)));
        }
    }

    private boolean send(String to, String subject, String html) {
        try {
            emailService.sendRawHtml(to, subject, "<div style='font-family:sans-serif;line-height:1.6;color:#333;'>" + html + "</div>");
            return true;
        } catch (RuntimeException e) {
            log.warn("[portal] email to {} not sent: {}", to, e.getMessage());
            return false;
        }
    }

    private PortalUser account(UUID id) {
        return repository.find(TenantContext.getCurrentOrganizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Portal account not found"));
    }

    private Partner partner(UUID orgId, UUID id) {
        return partnerRepository.findByOrganizationIdAndId(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Client not found"));
    }

    private String firmName(UUID orgId) {
        return organizationRepository.findById(orgId).map(o -> o.getName()).orElse("");
    }

    private static PortalDtos.Account view(PortalUser u) {
        return new PortalDtos.Account(u.getId(), u.getPartnerId(), u.getEmail(), u.getDisplayName(), u.isActive(),
                u.getInviteTokenHash() != null && u.getPasswordHash() == null, u.getInviteExpiresAt(), u.getLastLoginAt(),
                u.getCreatedAt());
    }

    private static String normalize(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!e.matches("^[^@\\s]+@[a-z0-9.-]+\\.[a-z]{2,}$")) throw new IllegalArgumentException("Enter a valid email address");
        return e;
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
