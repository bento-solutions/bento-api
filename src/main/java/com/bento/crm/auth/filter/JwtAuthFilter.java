package com.bento.crm.auth.filter;

import com.bento.crm.common.config.TenantFilterInterceptor;
import com.bento.crm.portal.PortalPrincipal;
import java.util.UUID;

import com.bento.crm.apitoken.security.ApiTokenPrincipal;
import com.bento.crm.auth.service.JwtService;
import com.bento.crm.whatsapp.service.WaActor;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getAttribute(ApiTokenPrincipal.REQUEST_ATTRIBUTE) instanceof ApiTokenPrincipal principal) {
            // Authenticated by TenantFilterInterceptor as a personal API token: act as its owner,
            // narrowed to the token's scopes, and mark the authentication so WaActor knows.
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal.userId().toString(), null,
                    principal.authorities().stream().map(SimpleGrantedAuthority::new).toList());
            auth.setDetails(new WaActor.ApiTokenDetails(principal.tokenId()));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (request.getParameter("token") != null && !request.getParameter("token").isBlank()) {
            token = request.getParameter("token");
        }

        if (token != null && TenantFilterInterceptor.isPortalPath(request)) {
            Claims portal = jwtService.tryParsePortalToken(token);
            if (portal != null && !portalSessionValid(portal)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Your session has ended: sign in again\"}");
                return;
            }
            if (portal != null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        portal.getSubject(), null, List.of(new SimpleGrantedAuthority(PortalPrincipal.AUTHORITY)));
                auth.setDetails(new PortalPrincipal(UUID.fromString(portal.getSubject()),
                        UUID.fromString(portal.get("org", String.class)), UUID.fromString(portal.get("partner", String.class))));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } else if (token != null) {
            Claims claims = jwtService.tryParseAccessToken(token);

            if (claims != null) {
                @SuppressWarnings("unchecked")
                List<String> authorities = (List<String>) claims.get("authorities");

                List<SimpleGrantedAuthority> grantedAuthorities = authorities != null
                        ? authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())
                        : Collections.emptyList();

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, grantedAuthorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * A client session ends as soon as the firm deactivates or removes the account, moves it to
     * another client, or the password is reset.
     */
    private boolean portalSessionValid(Claims portal) {
        try {
            Boolean ok = jdbc.query("""
                    SELECT 1 FROM portal_user WHERE id = ? AND organization_id = ? AND partner_id = ? AND is_active
                      AND deleted_at IS NULL AND password_hash IS NOT NULL
                      AND (password_changed_at IS NULL OR password_changed_at <= ?)
                    """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) java.sql.ResultSet::next, UUID.fromString(portal.getSubject()), UUID.fromString(portal.get("org", String.class)),
                    UUID.fromString(portal.get("partner", String.class)),
                    java.sql.Timestamp.from(portal.getIssuedAt().toInstant()));
            return Boolean.TRUE.equals(ok);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
