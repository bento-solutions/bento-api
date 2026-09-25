package com.bento.crm.apitoken.controller;

import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.bento.crm.apitoken.service.ApiTokenService;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal API tokens. Creating and revoking require a signed-in person (an API token is never
 * accepted here, see the token path allow-list), so a leaked token cannot mint more tokens.
 */
@RestController
@RequestMapping("/api-tokens")
@RequiredArgsConstructor
@Tag(name = "API tokens", description = "Personal access tokens for AI agents and integrations")
public class ApiTokenController {

    private final ApiTokenService tokenService;

    public record CreateRequest(String name, List<String> scopes, Integer expiresInDays, Integer maxSendsPerHour) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('API_TOKENS_MANAGE')")
    @Operation(summary = "My tokens (admins: ?all=true for the whole organization)")
    public List<ApiTokenService.TokenView> list(@RequestParam(defaultValue = "false") boolean all) {
        boolean admin = hasAuthority("ADMIN_ACCESS");
        return tokenService.list(TenantContext.getCurrentOrganizationId(), currentUserId(), all && admin);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('API_TOKENS_MANAGE')")
    @Operation(summary = "Create a token; the response is the only time the token is shown")
    public ResponseEntity<ApiTokenService.CreatedToken> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tokenService.create(
                TenantContext.getCurrentOrganizationId(), currentUserId(), request.name(), request.scopes(),
                request.expiresInDays(), request.maxSendsPerHour()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('API_TOKENS_MANAGE')")
    @Operation(summary = "Revoke a token immediately")
    public ApiTokenService.TokenView revoke(@PathVariable UUID id) {
        return tokenService.revoke(TenantContext.getCurrentOrganizationId(), currentUserId(),
                hasAuthority("ADMIN_ACCESS"), id);
    }

    /** For an agent: which token it is using and what it may do. */
    @GetMapping("/me")
    @Operation(summary = "Introspect the API token making this request")
    public Map<String, Object> me(HttpServletRequest request) {
        if (!(request.getAttribute(ApiTokenPrincipal.REQUEST_ATTRIBUTE) instanceof ApiTokenPrincipal p)) {
            throw new ResourceNotFoundException("This request is not authenticated with an API token");
        }
        return Map.of("id", p.tokenId(), "name", p.name(), "tokenPrefix", p.tokenPrefix(), "scopes", p.scopes(),
                "organizationId", p.organizationId(), "userId", p.userId(),
                "expiresAt", String.valueOf(p.expiresAt()));
    }

    private static UUID currentUserId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private static boolean hasAuthority(String authority) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
