package com.bento.crm.identity.service;

import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.identity.model.Account;
import com.bento.crm.identity.model.AccountToken;
import com.bento.crm.identity.model.AccountToken.Purpose;
import com.bento.crm.identity.repository.AccountRepository;
import com.bento.crm.identity.repository.AccountTokenRepository;
import com.bento.crm.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;
import java.util.UUID;

/**
 * What an Account does with its own address and password: confirming the address, choosing a new
 * password from an emailed link, changing it while signed in, and ending its sessions.
 *
 * <p>Every request that starts from an email address answers the same way whether or not an
 * Account exists, so none of them reveals who uses Bento.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    static final Duration VERIFY_TTL = Duration.ofHours(48);
    static final Duration RESET_TTL = Duration.ofHours(2);
    /** A second link of the same kind within this window is not sent; the first still works. */
    static final Duration RESEND_PAUSE = Duration.ofMinutes(2);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID_LINK = "This link is not valid or has expired";

    private final AccountRepository accountRepository;
    private final AccountTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final IdentityProperties properties;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    public boolean verificationRequired() {
        return properties.isRequireVerifiedEmail();
    }

    // ------------------------------------------------------------ email address

    /** Emails a link confirming the Account's address, unless one went out moments ago. */
    @Transactional
    public void sendVerification(Account account) {
        if (account.isEmailVerified() || sentRecently(account, Purpose.VERIFY_EMAIL)) {
            return;
        }
        String token = issue(account, Purpose.VERIFY_EMAIL, VERIFY_TTL);
        send(account, "Confirm your email address for Bento",
                "Confirm your email address",
                "Open this link to confirm that this address is yours. You can sign in to Bento once it's confirmed.",
                "Confirm my email", link("/verify-email", token),
                "If you didn't create a Bento account, ignore this message.");
    }

    /** Always succeeds: the answer must not tell whether the address has an Account. */
    @Transactional
    public void resendVerification(String email) {
        accountRepository.findByEmail(Account.normalizeEmail(email)).ifPresent(this::sendVerification);
    }

    @Transactional
    public void verifyEmail(String token) {
        AccountToken link = usableLink(token, Purpose.VERIFY_EMAIL);
        Account account = accountRepository.findById(link.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(INVALID_LINK));
        Instant now = Instant.now();
        link.setUsedAt(now);
        tokenRepository.save(link);
        markVerified(account, now);
        log.info("Account {} confirmed its email address", account.getId());
    }

    /** Records that the person showed they read this inbox, for instance by opening an emailed invitation. */
    @Transactional
    public void markVerified(UUID accountId) {
        accountRepository.findById(accountId).ifPresent(account -> markVerified(account, Instant.now()));
    }

    /** Tells someone who tried to sign up again that they already have an Account. */
    public void sendAlreadyRegistered(Account account) {
        send(account, "You already have a Bento account",
                "You already have a Bento account",
                "Someone, probably you, tried to create a Bento account with this address. Sign in with it instead: once signed in, you can create another organization from the organization menu. If you forgot your password, choose \"Forgot password?\" on the sign-in page.",
                "Sign in", link("/", null),
                "If this wasn't you, you can ignore this message: nothing was changed.");
    }

    // ------------------------------------------------------------------ password

    /** Always succeeds: the answer must not tell whether the address has an Account. */
    @Transactional
    public void requestPasswordReset(String email) {
        accountRepository.findByEmail(Account.normalizeEmail(email)).ifPresent(account -> {
            if (sentRecently(account, Purpose.RESET_PASSWORD)) {
                return;
            }
            tokenRepository.retireOutstanding(account.getId(), Purpose.RESET_PASSWORD, Instant.now());
            String token = issue(account, Purpose.RESET_PASSWORD, RESET_TTL);
            send(account, "Choose a new Bento password",
                    "Choose a new password",
                    "Open this link within 2 hours to choose a new password. Choosing one signs you out of Bento everywhere else.",
                    "Choose a new password", link("/reset-password", token),
                    "If you didn't ask for this, ignore this message: your password stays the same.");
        });
    }

    /**
     * Sets a new password from an emailed link and ends every session of the Account, in every
     * Organization. The link came to the inbox, so it confirms the address too.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        AccountToken link = usableLink(token, Purpose.RESET_PASSWORD);
        Account account = accountRepository.findById(link.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(INVALID_LINK));
        Instant now = Instant.now();
        tokenRepository.retireOutstanding(account.getId(), Purpose.RESET_PASSWORD, now);
        setPassword(account, newPassword, now);
        markVerified(account, now);
        int ended = refreshTokenRepository.revokeAllForAccount(account.getId(), now);
        log.info("Account {} chose a new password from an emailed link; {} sessions ended", account.getId(), ended);
    }

    /**
     * Changes the password of a signed-in person and ends all their sessions. The caller opens a
     * fresh session for the device the change was made on.
     */
    @Transactional
    public void changePassword(UUID accountId, String currentPassword, String newPassword) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            // Not a 401: the person is signed in, and the client treats 401 as an expired session.
            throw new IllegalArgumentException("Your current password is not right");
        }
        Instant now = Instant.now();
        setPassword(account, newPassword, now);
        int ended = refreshTokenRepository.revokeAllForAccount(accountId, now);
        log.info("Account {} changed its password; {} sessions ended", accountId, ended);
    }

    /** Ends every session of the Account, in every Organization. Access tokens lapse within minutes. */
    @Transactional
    public void signOutEverywhere(UUID accountId) {
        int ended = refreshTokenRepository.revokeAllForAccount(accountId, Instant.now());
        log.info("Account {} signed out everywhere; {} sessions ended", accountId, ended);
    }

    // ------------------------------------------------------------------- helpers

    private void setPassword(Account account, String newPassword, Instant now) {
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setPasswordChangedAt(now);
        accountRepository.save(account);
    }

    private void markVerified(Account account, Instant now) {
        if (!account.isEmailVerified()) {
            account.setEmailVerifiedAt(now);
            accountRepository.save(account);
        }
    }

    private boolean sentRecently(Account account, Purpose purpose) {
        return tokenRepository.existsByAccountIdAndPurposeAndCreatedAtAfter(
                account.getId(), purpose, Instant.now().minus(RESEND_PAUSE));
    }

    private String issue(Account account, Purpose purpose, Duration ttl) {
        String token = generateToken();
        tokenRepository.save(AccountToken.builder()
                .accountId(account.getId())
                .purpose(purpose)
                .tokenHash(hash(token))
                .expiresAt(Instant.now().plus(ttl))
                .build());
        return token;
    }

    /** Every rejection reads the same, so a guess can't tell an unknown link from a used one. */
    private AccountToken usableLink(String token, Purpose purpose) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException(INVALID_LINK);
        }
        AccountToken link = tokenRepository.findByTokenHashAndPurpose(hash(token.trim()), purpose)
                .orElseThrow(() -> new ResourceNotFoundException(INVALID_LINK));
        if (!link.isUsable(Instant.now())) {
            throw new ResourceNotFoundException(INVALID_LINK);
        }
        return link;
    }

    private String link(String path, String token) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return token == null ? base + path : base + path + "?token=" + token;
    }

    private void send(Account account, String subject, String heading, String body, String buttonLabel, String url, String footer) {
        emailService.sendHtml(account.getEmail(), subject, "account-link", Map.of(
                "heading", HtmlUtils.htmlEscape(heading),
                "body", HtmlUtils.htmlEscape(body),
                "buttonLabel", HtmlUtils.htmlEscape(buttonLabel),
                "url", HtmlUtils.htmlEscape(url),
                "email", HtmlUtils.htmlEscape(account.getEmail()),
                "footer", HtmlUtils.htmlEscape(footer)));
    }

    /** 256 bits, URL-safe: it travels in a query string and must survive email clients intact. */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }
}
