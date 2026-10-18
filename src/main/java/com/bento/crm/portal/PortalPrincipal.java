package com.bento.crm.portal;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Who is signed in to the client portal: the authentication details JwtAuthFilter sets from a
 * portal token. Every portal query is scoped by {@link #partnerId()}, never by an id the
 * browser sends.
 */
public record PortalPrincipal(UUID portalUserId, UUID organizationId, UUID partnerId) {

    public static final String AUTHORITY = "PORTAL_CLIENT";

    public static PortalPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof PortalPrincipal p) {
            return p;
        }
        throw new AccessDeniedException("Not signed in to the client portal");
    }
}
