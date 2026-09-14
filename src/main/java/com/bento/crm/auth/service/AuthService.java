package com.bento.crm.auth.service;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.dto.OrganizationChoiceDto;
import com.bento.crm.common.exception.AuthenticationFailedException;
import com.bento.crm.common.exception.MultipleOrganizationsException;
import com.bento.crm.identity.dto.UserResponseDto;
import com.bento.crm.identity.mapper.UserMapper;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.model.RefreshToken;
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

    /**
     * Authenticates by email and password.
     *
     * <p>Email is unique per organization, not globally, so an address can belong to several
     * tenants. The password decides which: every candidate account is checked and exactly one
     * must match. Two accounts sharing an email <em>and</em> a password is disambiguated by
     * providing organization_id, or prompted with the list of available workspaces.
     */
    @Transactional
    public LoginResponse login(String email, String password, UUID organizationId) {
        List<AppUser> candidates = userRepository.findAllByEmailAcrossOrganizations(normalizeEmail(email));
        if (organizationId != null) {
            candidates = candidates.stream()
                    .filter(u -> organizationId.equals(u.getOrganizationId()))
                    .toList();
        }

        if (candidates.isEmpty()) {
            // Burn a BCrypt round anyway so the timing matches the "account exists" path.
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new AuthenticationFailedException("Invalid credentials");
        }

        List<AppUser> matched = candidates.stream()
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .toList();

        if (matched.isEmpty()) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        List<AppUser> activeMatched = matched.stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .toList();

        if (activeMatched.isEmpty()) {
            throw new AuthenticationFailedException("User account is inactive");
        }

        if (activeMatched.size() > 1) {
            log.warn("Login for {} matched {} active accounts across organizations", email, activeMatched.size());
            List<UUID> orgIds = activeMatched.stream().map(AppUser::getOrganizationId).toList();
            Map<UUID, Organization> orgMap = organizationRepository.findAllById(orgIds).stream()
                    .collect(Collectors.toMap(Organization::getId, Function.identity()));

            List<OrganizationChoiceDto> choices = activeMatched.stream()
                    .map(u -> {
                        Organization org = orgMap.get(u.getOrganizationId());
                        String orgName = org != null ? org.getName() : "Workspace";
                        return OrganizationChoiceDto.builder()
                                .organizationId(u.getOrganizationId())
                                .organizationName(orgName)
                                .role(u.getRole() != null ? u.getRole().name() : null)
                                .lastActiveAt(u.getLastActiveAt())
                                .build();
                    })
                    .sorted((a, b) -> {
                        if (a.getLastActiveAt() == null && b.getLastActiveAt() == null) return 0;
                        if (a.getLastActiveAt() == null) return 1;
                        if (b.getLastActiveAt() == null) return -1;
                        return b.getLastActiveAt().compareTo(a.getLastActiveAt());
                    })
                    .toList();

            throw new MultipleOrganizationsException(
                    "This account belongs to multiple organizations. Please select an organization to sign in to.",
                    choices);
        }

        AppUser user = activeMatched.get(0);
        return issueSession(user);
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
     */
    @Transactional
    public LoginResponse refresh(String refreshTokenValue) {
        String tokenHash = hashToken(refreshTokenValue);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired refresh token"));

        if (stored.getRevokedAt() != null) {
            log.warn("Refresh token reuse detected for user {} — revoking all sessions", stored.getUserId());
            refreshTokenRepository.revokeAllForUser(stored.getUserId(), Instant.now());
            throw new AuthenticationFailedException("Invalid or expired refresh token");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthenticationFailedException("Invalid or expired refresh token");
        }

        AppUser user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired refresh token"));

        // A deactivated user still holds a refresh token valid for up to 30 days. Without this
        // check, deactivating an account does not actually end its access.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
            throw new AuthenticationFailedException("User account is inactive");
        }

        refreshTokenRepository.revokeToken(stored.getId(), Instant.now());
        return mintTokens(user, stored.getId());
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

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim();
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
