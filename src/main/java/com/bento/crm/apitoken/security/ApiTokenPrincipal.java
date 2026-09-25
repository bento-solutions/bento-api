package com.bento.crm.apitoken.security;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated API token: who it acts as, where, and with what. Resolved once per request
 * and carried explicitly (request attribute, then WaActor / MCP caller) rather than re-derived.
 *
 * @param authorities the token's scopes intersected with its owner's role
 */
public record ApiTokenPrincipal(UUID tokenId, UUID organizationId, UUID userId, String name, String tokenPrefix,
                                Set<String> scopes, Set<String> authorities, Integer maxSendsPerHour,
                                Instant expiresAt) {

    /** Request attribute under which the filter chain hands the principal on. */
    public static final String REQUEST_ATTRIBUTE = ApiTokenPrincipal.class.getName();

    public static final String PREFIX = "bento_pat_";

    public static boolean looksLikeToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
