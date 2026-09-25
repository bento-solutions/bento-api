package com.bento.crm.common.config;

import com.bento.crm.apitoken.security.ApiTokenPaths;
import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.bento.crm.apitoken.service.ApiTokenService;
import com.bento.crm.auth.service.JwtService;
import com.bento.crm.common.context.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Binds the caller's organization to the request: it populates {@link TenantContext} and enables
 * Hibernate's {@code organizationFilter} on the session, so a query that forgets its own
 * {@code organization_id} predicate still cannot see another tenant's rows.
 *
 * <p>Requests with no resolvable organization are rejected outright unless the path is one of the
 * few that legitimately carries no JWT (login, signup, invitation acceptance, the WhatsApp
 * webhook, health and API docs); those resolve their tenant by other means or have none.
 */
@Component
@RequiredArgsConstructor
public class TenantFilterInterceptor extends OncePerRequestFilter {

    /**
     * Matched against the path with the servlet context-path already stripped, and compared
     * exactly rather than with {@code contains}/{@code endsWith} — a substring test lets
     * {@code /partners/auth/login} or {@code /x?y=/organizations} slip through unauthenticated.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/auth/login",
            "/api/v1/auth/login",
            "/auth/refresh",
            "/api/v1/auth/refresh",
            "/organizations",
            "/api/v1/organizations",
            // Invitation acceptance carries no JWT and therefore no org claim; the
            // invitation token resolves the tenant instead.
            "/public/invitations",
            "/api/v1/public/invitations",
            "/public/invitations/accept",
            "/api/v1/public/invitations/accept",
            "/actuator/health",
            "/api/v1/actuator/health",
            // The WhatsApp webhook carries no JWT and therefore no org claim; it
            // resolves its own tenant from metadata.phone_number_id instead.
            "/webhooks/whatsapp",
            "/api/v1/webhooks/whatsapp",
            // The Baileys bot's webhook: HMAC-signed, resolves its tenant from the session id.
            "/webhooks/baileys",
            "/api/v1/webhooks/baileys"
    );

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/files/public",
            "/api/v1/files/public",
            "/actuator",
            "/api/v1/actuator",
            "/swagger-ui",
            "/api/v1/swagger-ui",
            "/openapi",
            "/api/v1/openapi",
            "/v3/api-docs",
            "/api/v1/v3/api-docs"
    );

    private final EntityManager entityManager;
    private final JwtService jwtService;
    private final ApiTokenService apiTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String authHeader = request.getHeader("Authorization");
            String bearer = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
            String queryToken = request.getParameter("token");
            UUID organizationId;
            if ("/mcp".equals(pathWithinApplication(request)) && !ApiTokenPrincipal.looksLikeToken(bearer)) {
                // The MCP endpoint is for agents only: a signed-in session's JWT is not accepted.
                response.setHeader("WWW-Authenticate", "Bearer realm=\"bento\"");
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "The MCP endpoint needs a Bento API token");
                return;
            }
            if (ApiTokenPrincipal.looksLikeToken(queryToken)) {
                // A personal API token in a URL ends up in proxy logs and browser history.
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "API tokens are only accepted in the Authorization header");
                return;
            } else if (ApiTokenPrincipal.looksLikeToken(bearer)) {
                ApiTokenPrincipal principal = apiTokenService.authenticate(bearer).orElse(null);
                if (principal == null) {
                    response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                    reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid, expired or revoked API token");
                    return;
                }
                if (!ApiTokenPaths.allows(request.getMethod(), pathWithinApplication(request))) {
                    reject(response, HttpServletResponse.SC_FORBIDDEN, "API tokens cannot access this endpoint");
                    return;
                }
                request.setAttribute(ApiTokenPrincipal.REQUEST_ATTRIBUTE, principal);
                organizationId = principal.organizationId();
            } else {
                organizationId = resolveOrganizationId(request);
            }

            if (organizationId == null) {
                if (isPublicEndpoint(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Missing or invalid organization context\"}");
                return;
            }

            TenantContext.setCurrentOrganizationId(organizationId);
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("organizationFilter")
                    .setParameter("organizationId", organizationId);

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private UUID resolveOrganizationId(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (request.getParameter("token") != null && !request.getParameter("token").isBlank()) {
            token = request.getParameter("token");
        }
        if (token == null) {
            return null;
        }
        // Access tokens only: a refresh token also carries an "org" claim, and honouring it here
        // would let a 30-day credential open a tenant-scoped session.
        Claims claims = jwtService.tryParseAccessToken(token);
        if (claims == null) {
            return null;
        }
        String orgId = claims.get("org", String.class);
        if (orgId == null) {
            return null;
        }
        try {
            return UUID.fromString(orgId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isPublicEndpoint(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** The request path with the servlet context-path ({@code /api/v1}) removed. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        } else if (uri.startsWith("/api/v1")) {
            uri = uri.substring(7);
        }
        return uri.isEmpty() ? "/" : uri;
    }
}
