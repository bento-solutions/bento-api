package com.bento.crm.auth.service;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.dto.OrganizationChoiceDto;
import com.bento.crm.common.exception.AuthenticationFailedException;
import com.bento.crm.common.exception.MultipleOrganizationsException;
import com.bento.crm.identity.dto.UserResponseDto;
import com.bento.crm.identity.mapper.UserMapper;
import com.bento.crm.identity.model.Account;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.model.RefreshToken;
import com.bento.crm.identity.repository.AccountRepository;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.identity.repository.RefreshTokenRepository;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /**
     * A BCrypt hash of a value nobody knows, verified against when no account matches the
     * submitted email. Without it, a login for a non-existent address returns in microseconds
     * while a real one costs a full BCrypt round, which is enough to enumerate customers'
     * email addresses from response timing alone.
     */
    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7DKlF3M9ZLtEIcuNCE1u5ZfvIzZJHFq";

    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OrganizationRepository organizationRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    @Value("${JWT_ACCESS_TOKEN_EXPIRY:900000}")
    private long accessTokenExpiryMs;

    @Value("${JWT_REFRESH_TOKEN_EXPIRY:2592000000}")
    private long refreshTokenExpiryMs;

    /** See {@link #refresh}: how long a just-rotated refresh token may still be exchanged. */
    @Value("${JWT_REFRESH_REUSE_GRACE_MS:30000}")
    private long refreshReuseGraceMs;

    /**
     * Authenticates an Account by email and password, then opens a session in one of its
     * Memberships. With several active Memberships and no {@code organizationId}, the person is
     * asked to choose (409 with the choices); {@code organizationId} only narrows the choice and is
     * never a credential.
     */
    @Transactional
    public LoginResponse login(String email, String password, UUID organizationId) {
        Account account = accountRepository.findByEmail(Account.normalizeEmail(email)).orElse(null);
        if (account == null) {
            // Burn a BCrypt round anyway so the timing matches the "account exists" path.
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new AuthenticationFailedException("Invalid credentials");
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        List<AppUser> active = activeMemberships(account.getId()).stream()
                .filter(m -> organizationId == null || organizationId.equals(m.getOrganizationId()))
                .toList();
        if (active.isEmpty()) {
            throw new AuthenticationFailedException(organizationId == null
                    ? "User account is inactive"
                    : "Invalid credentials");
        }
        if (active.size() > 1) {
            throw new MultipleOrganizationsException(
                    "This account belongs to multiple organizations. Please select an organization to sign in to.",
                    organizationChoices(active));
        }
        return issueSession(active.get(0));
    }

    /** The Account's Memberships a session can be opened in, most recently used first. */
    public List<AppUser> activeMemberships(UUID accountId) {
        return userRepository.findAllByAccountIdAcrossOrganizations(accountId).stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsActive()) && m.getDeletedAt() == null)
                .toList();
    }

    /** Describes Memberships as the Organizations a person can pick from, in the given order. */
    public List<OrganizationChoiceDto> organizationChoices(List<AppUser> memberships) {
        List<UUID> orgIds = memberships.stream().map(AppUser::getOrganizationId).toList();
        Map<UUID, Organization> orgs = organizationRepository.findAllById(orgIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        return memberships.stream()
                .map(m -> {
                    Organization org = orgs.get(m.getOrganizationId());
                    return OrganizationChoiceDto.builder()
                            .organizationId(m.getOrganizationId())
                            .organizationName(org != null ? org.getName() : "Workspace")
                            .logoUrl(org != null ? org.getLogoUrl() : null)
                            .role(m.getRole() != null ? m.getRole().name() : null)
                            .joinedAt(m.getCreatedAt())
                            .lastActiveAt(m.getLastActiveAt())
                            .build();
                })
                .toList();
    }

    /**
     * Mints an access/refresh pair for a user whose identity has already been established by
     * some means other than a password check. Shared with the invitation flow, which signs the
     * invitee straight in after they set their password rather than bouncing them to a login
     * form they would immediately fill with what they just typed.
     */
    @Transactional
    public LoginResponse issueSession(AppUser user) {
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);
        return mintTokens(user, null);
    }

    /**
     * Rotates a refresh token.
     *
     * <p>Rotation is one-shot: the presented token is revoked as it is exchanged. Presenting an
     * already-revoked token therefore means either a replay of a stolen credential or a client
     * that raced itself, and in both cases every token for that user is revoked — the safe
     * reading is that the token leaked, and forcing a fresh login is cheap next to the
     * alternative.
     *
     * <p>Except within a short grace period after rotation. A client can lose the response that
     * carried its new token — a page reloaded while the refresh was in flight, or two browser
     * tabs sharing one stored token that both refreshed when their access token expired — and
     * then present the old one again moments later. Treating that as theft signed users out
     * every few refreshes, so a token rotated less than {@code JWT_REFRESH_REUSE_GRACE_MS} ago
     * is exchanged once more for a fresh pair instead.
     *
     * <p>The revocations happen right before the exception, so it must not roll them back.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public LoginResponse refresh(String refreshTokenValue) {
        String tokenHash = hashToken(refreshTokenValue);
        Instant now = Instant.now();

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired refresh token"));

        if (stored.getRevokedAt() != null && !rotatedWithinGrace(stored, now)) {
            log.warn("Refresh token reuse detected for user {} — revoking all sessions", stored.getUserId());
            refreshTokenRepository.revokeAllForUser(stored.getUserId(), now);
            throw new AuthenticationFailedException("Invalid or expired refresh token");
        }
        if (stored.getExpiresAt().isBefore(now)) {
            throw new AuthenticationFailedException("Invalid or expired refresh token");
        }

        AppUser user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired refresh token"));

        // A deactivated user still holds a refresh token valid for up to 30 days. Without this
        // check, deactivating an account does not actually end its access.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            refreshTokenRepository.revokeAllForUser(user.getId(), now);
            throw new AuthenticationFailedException("User account is inactive");
        }

        if (stored.getRevokedAt() == null) {
            refreshTokenRepository.revokeToken(stored.getId(), now);
        } else {
            log.info("Refresh token for user {} presented again {} ms after rotation — treated as a client race",
                    user.getId(), Duration.between(stored.getRevokedAt(), now).toMillis());
        }
        return mintTokens(user, stored.getId());
    }

    /**
     * Whether a revoked token was revoked by rotation (it has a successor; a logout or a
     * deactivation leaves none) recently enough to count as a client race rather than a replay.
     */
    private boolean rotatedWithinGrace(RefreshToken stored, Instant now) {
        return stored.getRevokedAt().isAfter(now.minusMillis(refreshReuseGraceMs))
                && refreshTokenRepository.existsByReplacedByTokenId(stored.getId());
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("User {} logged out", userId);
    }

    private LoginResponse mintTokens(AppUser user, UUID replacedTokenId) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getOrganizationId(), user.getRole());
        String refreshTokenValue = jwtService.generateRefreshToken(user.getId(), user.getOrganizationId());

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hashToken(refreshTokenValue))
                .expiresAt(Instant.now().plusMillis(refreshTokenExpiryMs))
                .replacedByTokenId(replacedTokenId)
                .build();
        refreshTokenRepository.save(refreshToken);

        UserResponseDto userDto = userMapper.toResponseDto(user);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenValue)
                .tokenType("Bearer")
                .expiresIn(Duration.ofMillis(accessTokenExpiryMs).toSeconds())
                .user(userDto)
                .build();
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }
}
