package com.bento.crm.whatsapp.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.model.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who is acting on the inbox, passed explicitly into every inbox and outbox service method.
 *
 * <p>Not read from ThreadLocals inside the services: the MCP endpoint runs tools where the
 * request's {@code TenantContext} and security context are not guaranteed to be present, so the
 * caller resolves the actor once at the edge and hands it down.
 *
 * @param apiTokenId set when the caller is an AI agent or integration using a personal API token
 */
public record WaActor(UUID organizationId, UUID userId, UUID apiTokenId, Set<String> authorities) {

    public boolean can(Permission permission) {
        return authorities.contains(permission.getAuthority());
    }

    /** Sees every conversation, not only those of partners assigned to or owned by them. */
    public boolean readsAll() {
        return can(Permission.WHATSAPP_READ_ALL);
    }

    public boolean isAgent() {
        return apiTokenId != null;
    }

    /** The actor of the current authenticated HTTP request. */
    public static WaActor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        UUID apiTokenId = auth.getDetails() instanceof ApiTokenDetails details ? details.tokenId() : null;
        return new WaActor(TenantContext.getCurrentOrganizationId(), userId, apiTokenId, authorities);
    }

    /** Attached as the authentication's details when a request is authenticated by an API token. */
    public record ApiTokenDetails(UUID tokenId) {
    }
}
