package com.bento.crm.auth;

import com.bento.crm.identity.model.RefreshToken;
import com.bento.crm.identity.repository.RefreshTokenRepository;
import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rotation and reuse detection for refresh tokens: a client that lost the response carrying its
 * new token (a reload mid-refresh, two tabs refreshing together) must not be signed out, while a
 * real replay must end every session for good.
 */
class RefreshTokenRotationTest extends IntegrationTestBase {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void refresh_presentingJustRotatedToken_issuesAnotherPair() throws Exception {
        String original = loginRefreshToken();
        String rotated = refreshTokenFrom(refresh(original).andExpect(status().isOk()));

        // The same token again, moments later: the client never saw `rotated`.
        String sibling = refreshTokenFrom(refresh(original).andExpect(status().isOk()));

        assertNotEquals(rotated, sibling);
        refresh(rotated).andExpect(status().isOk());
        refresh(sibling).andExpect(status().isOk());
    }

    @Test
    void refresh_presentingTokenRotatedLongAgo_revokesEverySession() throws Exception {
        String original = loginRefreshToken();
        String rotated = refreshTokenFrom(refresh(original).andExpect(status().isOk()));
        backdateRevocation(original);

        refresh(original).andExpect(status().isUnauthorized());

        // The revocation must survive the request failing: before, the exception rolled it back
        // and the "revoke all sessions" never happened.
        refresh(rotated).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_afterLogoutRevocation_isRefusedEvenWithinTheGracePeriod() throws Exception {
        String original = loginRefreshToken();
        // Revoked just now, but by a logout: no successor token records it as replaced.
        RefreshToken stored = stored(original);
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        refresh(original).andExpect(status().isUnauthorized());
    }

    private String loginRefreshToken() throws Exception {
        String email = "rotation-" + System.nanoTime() + "@example.com";
        String password = "TestPassword123!";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Org", "admin_email": "%s", "admin_name": "Admin", "admin_password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());
        return refreshTokenFrom(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk()));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refresh_token": "%s"}
                        """.formatted(refreshToken)));
    }

    private String refreshTokenFrom(ResultActions result) throws Exception {
        result.andExpect(jsonPath("$.refresh_token").isNotEmpty());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("refresh_token").asText();
    }

    /** Looks the token up the way AuthService stores it: by its SHA-256 hex digest. */
    private RefreshToken stored(String refreshToken) throws Exception {
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        return refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    }

    private void backdateRevocation(String refreshToken) throws Exception {
        RefreshToken token = stored(refreshToken);
        token.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(token);
    }
}
