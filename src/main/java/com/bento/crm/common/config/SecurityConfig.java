package com.bento.crm.common.config;

import com.bento.crm.auth.filter.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final TenantFilterInterceptor tenantFilterInterceptor;
    /**
     * Optional: {@link RateLimitFilter} is conditional on {@code app.rate-limit.enabled}, so
     * turning rate limiting off (as the integration tests do) must leave the rest of the chain
     * intact rather than failing context startup on a missing bean.
     */
    private final ObjectProvider<RateLimitFilter> rateLimitFilter;

    @Value("${CORS_ALLOWED_ORIGINS:https://crmbento.com,https://www.crmbento.com,https://dev.crmbento.com,http://localhost:4200,http://localhost:4201,http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                        .requestMatchers(
                                // Organization registration (signup)
                                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/organizations"),
                                AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/organizations"),
                                AntPathRequestMatcher.antMatcher("/organizations"),
                                AntPathRequestMatcher.antMatcher("/api/v1/organizations"),
                                // Auth endpoints (login, refresh, etc.)
                                AntPathRequestMatcher.antMatcher("/auth/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/auth/**"),
                                // Public invitations
                                AntPathRequestMatcher.antMatcher("/public/invitations/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/public/invitations/**"),
                                // Public file viewing (e.g. logos)
                                AntPathRequestMatcher.antMatcher("/files/public/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/files/public/**"),
                                // Actuator health & metrics
                                AntPathRequestMatcher.antMatcher("/actuator/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/actuator/**"),
                                // Swagger UI and OpenAPI documentation
                                AntPathRequestMatcher.antMatcher("/swagger-ui/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/swagger-ui/**"),
                                AntPathRequestMatcher.antMatcher("/swagger-ui.html"),
                                AntPathRequestMatcher.antMatcher("/api/v1/swagger-ui.html"),
                                AntPathRequestMatcher.antMatcher("/openapi/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/openapi/**"),
                                AntPathRequestMatcher.antMatcher("/v3/api-docs/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/v3/api-docs/**"),
                                // Webhooks (e.g. WhatsApp HMAC verified in WhatsAppWebhookController)
                                AntPathRequestMatcher.antMatcher("/webhooks/**"),
                                AntPathRequestMatcher.antMatcher("/api/v1/webhooks/**")
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilterInterceptor, JwtAuthFilter.class);

        rateLimitFilter.ifAvailable(filter ->
                http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        java.util.List<String> patterns = new java.util.ArrayList<>(java.util.List.of(
                "https://*.crmbento.com",
                "https://crmbento.com",
                "http://localhost:*",
                "http://127.0.0.1:*"
        ));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(patterns::add);
        }
        configuration.setAllowedOriginPatterns(patterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Rate-Limit-Remaining", "X-Rate-Limit-Retry-After-Seconds", "Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public org.springframework.web.filter.CorsFilter corsFilter() {
        return new org.springframework.web.filter.CorsFilter(corsConfigurationSource());
    }
}
