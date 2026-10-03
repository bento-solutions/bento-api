package com.bento.crm.common.config;

import com.bento.crm.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which bucket a request draws from: signed-in users per user, everyone else per IP. */
class RateLimitFilterTest {

    private static final String OFFICE_IP = "203.0.113.7";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        JwtService jwtService = mock(JwtService.class);
        for (String user : new String[] {"alice", "bob"}) {
            Claims claims = mock(Claims.class);
            when(claims.getSubject()).thenReturn(user);
            when(jwtService.tryParseAccessToken("token-" + user)).thenReturn(claims);
        }
        filter = new RateLimitFilter(new RateLimitConfig(), jwtService);
    }

    @Test
    void signedInUsersBehindOneIpDoNotShareABudget() throws Exception {
        // A couple of full page loads each (~20 calls apiece) used to exhaust a shared 100/min.
        for (int i = 0; i < 150; i++) {
            assertEquals(200, send("/api/v1/tasks", "token-alice"));
            assertEquals(200, send("/api/v1/tasks", "token-bob"));
        }
    }

    @Test
    void anonymousRequestsKeepThePerIpLimit() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertEquals(200, send("/api/v1/tasks", "forged"));
        }
        assertEquals(429, send("/api/v1/tasks", null));
    }

    @Test
    void tokenRefreshIsNotStarvedByOtherTraffic() throws Exception {
        for (int i = 0; i < 100; i++) {
            send("/api/v1/tasks", null);
        }
        assertEquals(429, send("/api/v1/tasks", null));
        assertEquals(200, send("/api/v1/auth/refresh", null));
    }

    private int send(String path, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(OFFICE_IP);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
