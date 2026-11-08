package com.bento.crm.identity;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An Organization always keeps at least one active admin, however a Membership changes. */
class LastAdminTest extends IntegrationTestBase {

    @Test
    void theOnlyActiveAdminCannotBeDemoted() throws Exception {
        String adminToken = signUpAndLogin();
        UUID adminId = userIdOf(adminToken);

        changeRole(adminToken, adminId, "VIEWER")
                .andExpect(status().isConflict());

        mockMvc.perform(get("/users/" + adminId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void anAdminCanBeDemotedWhileAnotherAdminStaysActive() throws Exception {
        String adminToken = signUpAndLogin();
        UUID adminId = userIdOf(adminToken);
        tokenForNewUser(orgIdOf(adminToken), UserRole.ADMIN);

        changeRole(adminToken, adminId, "MANAGER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void theOnlyActiveAdminCannotBeDeactivated() throws Exception {
        String adminToken = signUpAndLogin();
        UUID adminId = userIdOf(adminToken);

        mockMvc.perform(post("/users/" + adminId + "/deactivate").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void anInactiveAdminDoesNotCountAsTheLastAdmin() throws Exception {
        String adminToken = signUpAndLogin();
        UUID secondAdminId = userIdOf(tokenForNewUser(orgIdOf(adminToken), UserRole.ADMIN));
        deactivate(adminToken, secondAdminId).andExpect(status().isNoContent());

        // One active admin is left, but the inactive one isn't it, so changing them is allowed.
        deactivate(adminToken, secondAdminId).andExpect(status().isNoContent());
        changeRole(adminToken, secondAdminId, "VIEWER").andExpect(status().isOk());
    }

    @Test
    void twoAdminsDemotingThemselvesAtOnceLeaveOneAdmin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                String firstToken = signUpAndLogin();
                UUID firstId = userIdOf(firstToken);
                String secondToken = tokenForNewUser(orgIdOf(firstToken), UserRole.ADMIN);
                UUID secondId = userIdOf(secondToken);

                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> first = pool.submit(() -> {
                    start.await();
                    return changeRole(firstToken, firstId, "VIEWER").andReturn().getResponse().getStatus();
                });
                Future<Integer> second = pool.submit(() -> {
                    start.await();
                    return changeRole(secondToken, secondId, "VIEWER").andReturn().getResponse().getStatus();
                });
                start.countDown();

                assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                        .as("round %d", round)
                        .containsExactlyInAnyOrder(200, 409);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions deactivate(String token, UUID userId) throws Exception {
        return mockMvc.perform(post("/users/" + userId + "/deactivate").header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions changeRole(String token, UUID userId, String role) throws Exception {
        return mockMvc.perform(patch("/users/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"display_name": "Test Admin", "role": "%s"}
                        """.formatted(role)));
    }
}
