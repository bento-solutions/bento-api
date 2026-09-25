package com.bento.crm.apitoken;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.service.WaIngestService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiTokenAuthTest extends IntegrationTestBase {

    @Autowired
    private WaIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tokenIsShownOnce_andListsNeverRevealIt() throws Exception {
        String jwt = signUpAndLogin();
        String created = createToken(jwt, "Claude", "[\"whatsapp:read\"]", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("bento_pat_")))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");

        String list = mockMvc.perform(get("/api-tokens").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenPrefix").value(token.substring(0, 18)))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_token WHERE token_hash = ?", Integer.class, token))
                .as("only the hash is stored").isZero();

        mockMvc.perform(get("/api-tokens/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Claude"))
                .andExpect(jsonPath("$.scopes[0]").value("whatsapp:read"));
    }

    @Test
    void scopesAndThePathAllowListBoundWhatATokenCanDo() throws Exception {
        String jwt = signUpAndLogin();
        UUID conversationId = conversationWithInbound(jwt);
        String read = token(jwt, "[\"whatsapp:read\"]", null);

        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + read))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + read).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"hi\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/partners").header("Authorization", "Bearer " + read))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/users").header("Authorization", "Bearer " + read))
                .andExpect(status().isForbidden());
        createToken(read, "escalate", "[\"whatsapp:send\"]", null).andExpect(status().isForbidden());
    }

    @Test
    void draftOnlyToken_alwaysDrafts_andCannotApprove() throws Exception {
        String jwt = signUpAndLogin();
        UUID conversationId = conversationWithInbound(jwt);
        String created = createToken(jwt, "drafter", "[\"whatsapp:read\", \"whatsapp:draft\"]", null)
                .andReturn().getResponse().getContentAsString();
        String draft = JsonPath.read(created, "$.token");
        UUID tokenId = UUID.fromString(JsonPath.read(created, "$.view.id"));

        String message = mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + draft).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Proposition de réponse\", \"mode\": \"AUTO\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.source").value("AGENT"))
                .andReturn().getResponse().getContentAsString();
        String messageId = JsonPath.read(message, "$.id");
        assertThat(jdbc.queryForObject("SELECT api_token_id FROM wa_message WHERE id = ?", UUID.class, UUID.fromString(messageId)))
                .isEqualTo(tokenId);

        mockMvc.perform(post("/whatsapp/messages/" + messageId + "/approve").header("Authorization", "Bearer " + draft))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/whatsapp/messages/" + messageId + "/approve").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.oneOf("QUEUED", "SENDING", "SENT")));
    }

    @Test
    void sendTokenIsCappedPerHour() throws Exception {
        String jwt = signUpAndLogin();
        UUID conversationId = conversationWithInbound(jwt);
        String send = token(jwt, "[\"whatsapp:read\", \"whatsapp:send\"]", 2);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                            .header("Authorization", "Bearer " + send).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\": \"m" + i + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.source").value("AGENT"));
        }
        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + send).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"one too many\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void revokedExpiredAndQueryStringTokensAreRejected() throws Exception {
        String jwt = signUpAndLogin();
        String created = createToken(jwt, "short-lived", "[\"whatsapp:read\"]", null).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        String id = JsonPath.read(created, "$.view.id");

        mockMvc.perform(get("/whatsapp/conversations").param("token", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(delete("/api-tokens/" + id).header("Authorization", "Bearer " + jwt)).andExpect(status().isOk());
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        String expired = JsonPath.read(createToken(jwt, "expired", "[\"whatsapp:read\"]", null)
                .andReturn().getResponse().getContentAsString(), "$.token");
        jdbc.update("UPDATE api_token SET expires_at = now() - interval '1 minute' WHERE token_prefix = ?", expired.substring(0, 18));
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer bento_pat_" + "x".repeat(43)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleCannotGrantMoreThanItHas_andTokensStayInTheirOrganization() throws Exception {
        String admin = signUpAndLogin();
        UUID orgId = orgIdOf(admin);
        String viewer = tokenForNewUser(orgId, UserRole.VIEWER);
        createToken(viewer, "nope", "[\"whatsapp:send\"]", null).andExpect(status().isForbidden());

        String otherAdmin = signUpAndLogin();
        UUID foreignConversation = conversationWithInbound(otherAdmin);
        String mine = token(admin, "[\"whatsapp:read\"]", null);
        mockMvc.perform(get("/whatsapp/conversations/" + foreignConversation).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions createToken(String bearer, String name, String scopes,
                                                                            Integer maxPerHour) throws Exception {
        return mockMvc.perform(post("/api-tokens").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\", \"scopes\": %s, \"maxSendsPerHour\": %s}"
                        .formatted(name, scopes, maxPerHour == null ? "null" : maxPerHour)));
    }

    private String token(String jwt, String scopes, Integer maxPerHour) throws Exception {
        return JsonPath.read(createToken(jwt, "agent", scopes, maxPerHour)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private UUID conversationWithInbound(String jwt) throws Exception {
        UUID orgId = orgIdOf(jwt);
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        String phone = "+2126" + (10000000 + ThreadLocalRandom.current().nextInt(89999999));
        ingestService.ingest(orgId, InboundMessage.text("wamid." + UUID.randomUUID(), phone, "Bonjour", Instant.now()));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?", orgId, phone);
        return (UUID) row.get("id");
    }
}
