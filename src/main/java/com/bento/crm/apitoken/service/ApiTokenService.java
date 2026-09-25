package com.bento.crm.apitoken.service;

import com.bento.crm.apitoken.model.ApiScope;
import com.bento.crm.apitoken.model.ApiToken;
import com.bento.crm.apitoken.repository.ApiTokenRepository;
import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.Permission;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Personal API tokens: {@code bento_pat_} + 32 random bytes in base62. Only the SHA-256 is
 * stored, the same scheme as refresh tokens. Authentication is cached for a minute so an agent
 * polling the API does not hit the database on every call; revoking evicts the cache on this
 * instance (other instances drop it within the minute).
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    public static final int DEFAULT_EXPIRY_DAYS = 90;
    public static final int MAX_EXPIRY_DAYS = 365;
    public static final int DEFAULT_MAX_SENDS_PER_HOUR = 20;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final ApiTokenRepository tokenRepository;
    private final AppUserRepository userRepository;

    private record Cached(ApiTokenPrincipal principal, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public record TokenView(UUID id, UUID userId, String name, String tokenPrefix, List<String> scopes,
                            Integer maxSendsPerHour, Instant expiresAt, Instant lastUsedAt, Instant revokedAt,
                            Instant createdAt) {
        static TokenView of(ApiToken t) {
            return new TokenView(t.getId(), t.getUserId(), t.getName(), t.getTokenPrefix(), t.getScopes(),
                    t.getMaxSendsPerHour(), t.getExpiresAt(), t.getLastUsedAt(), t.getRevokedAt(), t.getCreatedAt());
        }
    }

    /** The only time the raw token exists outside the caller's hands. */
    public record CreatedToken(String token, TokenView view) {
    }

    @Transactional
    public CreatedToken create(UUID orgId, UUID userId, String name, List<String> scopes,
                               Integer expiresInDays, Integer maxSendsPerHour) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("Give the token a name (at most 100 characters)");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one scope");
        }
        AppUser owner = userRepository.findByOrganizationIdAndId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Set<Permission> ownerPermissions = Permission.forRole(owner.getRole());
        Set<String> accepted = new LinkedHashSet<>();
        for (String s : scopes) {
            ApiScope scope = ApiScope.of(s).orElseThrow(() -> new IllegalArgumentException("Unknown scope " + s));
            if (scope.permissions().stream().noneMatch(ownerPermissions::contains)) {
                throw new AccessDeniedException("Your role cannot grant " + s);
            }
            accepted.add(scope.value());
        }
        int days = expiresInDays == null ? DEFAULT_EXPIRY_DAYS : expiresInDays;
        if (days < 1 || days > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException("Expiry must be between 1 and " + MAX_EXPIRY_DAYS + " days");
        }
        int cap = maxSendsPerHour == null ? DEFAULT_MAX_SENDS_PER_HOUR : maxSendsPerHour;
        if (cap < 1 || cap > 200) {
            throw new IllegalArgumentException("The hourly message limit must be between 1 and 200");
        }

        String secret = randomBase62(32);
        String raw = ApiTokenPrincipal.PREFIX + secret;
        ApiToken token = new ApiToken();
        token.setOrganizationId(orgId);
        token.setUserId(userId);
        token.setName(trimmed);
        token.setTokenPrefix(ApiTokenPrincipal.PREFIX + secret.substring(0, 8));
        token.setTokenHash(hash(raw));
        token.setScopes(List.copyOf(accepted));
        token.setMaxSendsPerHour(cap);
        token.setExpiresAt(Instant.now().plus(Duration.ofDays(days)));
        token = tokenRepository.save(token);
        return new CreatedToken(raw, TokenView.of(token));
    }

    @Transactional(readOnly = true)
    public List<TokenView> list(UUID orgId, UUID userId, boolean wholeOrganization) {
        return (wholeOrganization ? tokenRepository.findForOrg(orgId) : tokenRepository.findForUser(orgId, userId))
                .stream().map(TokenView::of).toList();
    }

    /** Owners revoke their own tokens; admins may revoke any token in the organization. */
    @Transactional
    public TokenView revoke(UUID orgId, UUID actorUserId, boolean admin, UUID tokenId) {
        ApiToken token = tokenRepository.findByOrgAndId(orgId, tokenId)
                .orElseThrow(() -> new ResourceNotFoundException("Token not found"));
        if (!admin && !token.getUserId().equals(actorUserId)) {
            throw new ResourceNotFoundException("Token not found");
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            token = tokenRepository.save(token);
        }
        cache.values().removeIf(c -> c.principal().tokenId().equals(tokenId));
        return TokenView.of(token);
    }

    /**
     * Resolves a raw token to its principal, or empty if it is unknown, revoked, expired, or its
     * owner is no longer active.
     */
    @Transactional
    public Optional<ApiTokenPrincipal> authenticate(String raw) {
        if (!ApiTokenPrincipal.looksLikeToken(raw)) {
            return Optional.empty();
        }
        String hash = hash(raw);
        Instant now = Instant.now();
        Cached cached = cache.get(hash);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(now)) {
            ApiTokenPrincipal p = cached.principal();
            return p.expiresAt() == null || p.expiresAt().isAfter(now) ? Optional.of(p) : Optional.empty();
        }
        Optional<ApiTokenPrincipal> principal = tokenRepository.findByHash(hash)
                .filter(t -> t.isUsable(now))
                .flatMap(t -> userRepository.findByOrganizationIdAndId(t.getOrganizationId(), t.getUserId())
                        .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                        .map(u -> toPrincipal(t, u)));
        principal.ifPresentOrElse(p -> {
            cache.put(hash, new Cached(p, now));
            tokenRepository.touch(p.tokenId(), now);
        }, () -> cache.remove(hash));
        return principal;
    }

    private static ApiTokenPrincipal toPrincipal(ApiToken t, AppUser owner) {
        Set<Permission> ownerPermissions = Permission.forRole(owner.getRole());
        Set<String> authorities = t.getScopes().stream()
                .map(ApiScope::of).flatMap(Optional::stream)
                .flatMap(s -> s.permissions().stream())
                .filter(ownerPermissions::contains)
                .map(Permission::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        return new ApiTokenPrincipal(t.getId(), t.getOrganizationId(), t.getUserId(), t.getName(), t.getTokenPrefix(),
                Set.copyOf(t.getScopes()), authorities, t.getMaxSendsPerHour(), t.getExpiresAt());
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }

    private static String randomBase62(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        BigInteger n = new BigInteger(1, buf);
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(62);
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(base);
            sb.append(BASE62.charAt(qr[1].intValue()));
            n = qr[0];
        }
        while (sb.length() < 43) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }
}
