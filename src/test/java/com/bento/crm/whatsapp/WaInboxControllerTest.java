package com.bento.crm.whatsapp;

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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaInboxControllerTest extends IntegrationTestBase {

    @Autowired
    private WaIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void conversationsArePagedNewestFirst_andFilterable() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String first = phone();
        String second = phone();
        String third = phone();
        inbound(orgId, first, "first", Instant.now().minusSeconds(30));
        inbound(orgId, second, "second", Instant.now().minusSeconds(20));
        inbound(orgId, third, "third", Instant.now().minusSeconds(10));
        markRead(token, conversationId(orgId, second));

        String page1 = mockMvc.perform(get("/whatsapp/conversations").param("limit", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].phone").value(third))
                .andExpect(jsonPath("$.items[1].phone").value(second))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        mockMvc.perform(get("/whatsapp/conversations").param("limit", "2").param("cursor", cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].phone").value(first))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/whatsapp/conversations").param("filter", "unread")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(get("/whatsapp/conversations").param("q", third.substring(5))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].phone").value(third));

        mockMvc.perform(get("/whatsapp/unread-summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.conversations").value(2))
                .andExpect(jsonPath("$.messages").value(2));
    }

    @Test
    void threadIsPagedNewestFirst() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String phone = phone();
        for (int i = 0; i < 5; i++) {
            inbound(orgId, phone, "m" + i, Instant.now().minusSeconds(50 - i));
        }
        UUID conversationId = conversationId(orgId, phone);

        String page = mockMvc.perform(get("/whatsapp/conversations/" + conversationId + "/messages").param("limit", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[*].body").value(org.hamcrest.Matchers.contains("m4", "m3", "m2")))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/whatsapp/conversations/" + conversationId + "/messages")
                        .param("limit", "3").param("before", (String) JsonPath.read(page, "$.nextCursor"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[*].body").value(org.hamcrest.Matchers.contains("m1", "m0")));
    }

    @Test
    void viewerCanReadButNotSend() throws Exception {
        String admin = signUpAndLogin();
        UUID orgId = orgIdOf(admin);
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + admin));
        String phone = phone();
        inbound(orgId, phone, "hello", Instant.now());
        UUID conversationId = conversationId(orgId, phone);
        String viewer = tokenForNewUser(orgId, UserRole.VIEWER);

        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void salespersonSeesOnlyConversationsOfTheirOwnPartners() throws Exception {
        String admin = signUpAndLogin();
        UUID orgId = orgIdOf(admin);
        String sales = tokenForNewUser(orgId, UserRole.SALESPERSON);
        UUID salesId = userIdOf(sales);

        String mine = phone();
        String partnerId = JsonPath.read(mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"LEAD\", \"name\": \"Mine\", \"phone\": \"%s\", \"assigned_to_user_id\": \"%s\"}"
                                .formatted(mine, salesId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.update("UPDATE partner SET assigned_to_user_id = ? WHERE id = ?", salesId, UUID.fromString(partnerId));
        String unlinked = phone();
        inbound(orgId, mine, "for sales", Instant.now());
        inbound(orgId, unlinked, "personal", Instant.now());

        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + sales))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].phone").value(mine));
        mockMvc.perform(get("/whatsapp/conversations/" + conversationId(orgId, unlinked))
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void otherOrganizationsConversationsAreInvisible() throws Exception {
        String tokenA = signUpAndLogin();
        String phone = phone();
        inbound(orgIdOf(tokenA), phone, "private", Instant.now());
        UUID conversationId = conversationId(orgIdOf(tokenA), phone);
        String tokenB = signUpAndLogin();

        mockMvc.perform(get("/whatsapp/conversations/" + conversationId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/whatsapp/conversations").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void ignoringANumber_purgesItAndDropsLaterMessages_untilUnblocked() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String phone = phone();
        inbound(orgId, phone, "personal chat", Instant.now());
        UUID conversationId = conversationId(orgId, phone);

        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/ignore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/whatsapp/conversations/" + conversationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message WHERE conversation_id = ?", Integer.class,
                conversationId)).isZero();
        assertThat(ingestService.ingest(orgId, InboundMessage.text("wamid.z" + UUID.randomUUID(), phone, "again", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.IGNORED);

        String blocked = mockMvc.perform(get("/whatsapp/blocked").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].phone").value(phone))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/whatsapp/blocked/" + JsonPath.read(blocked, "$[0].id"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(ingestService.ingest(orgId, InboundMessage.text("wamid.y" + UUID.randomUUID(), phone, "back", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.STORED);
    }

    @Test
    void createLead_linksANewWhatsAppLead() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String phone = phone();
        ingestService.ingest(orgId, new InboundMessage("wamid.l" + UUID.randomUUID(),
                com.bento.crm.whatsapp.model.WaMessage.Direction.IN, InboundMessage.Origin.LIVE, phone, null, null,
                "Karim Benali", "text", "Salam, prix ?", null, null, null, null, Instant.now()));
        UUID conversationId = conversationId(orgId, phone);

        String view = mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/create-lead")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerName").value("Karim Benali"))
                .andExpect(jsonPath("$.partnerType").value("LEAD"))
                .andReturn().getResponse().getContentAsString();

        UUID partnerId = UUID.fromString(JsonPath.read(view, "$.partnerId"));
        assertThat(jdbc.queryForMap("SELECT source, external_id, assigned_to_user_id FROM partner WHERE id = ?", partnerId))
                .containsEntry("source", "WHATSAPP")
                .containsEntry("external_id", "whatsapp:" + phone)
                .containsEntry("assigned_to_user_id", userIdOf(token));

        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/create-lead")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/whatsapp/conversations/by-partner/" + partnerId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.id").value(conversationId.toString()));
        mockMvc.perform(put("/whatsapp/conversations/" + conversationId + "/partner")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\": null}"))
                .andExpect(jsonPath("$.partnerId").doesNotExist());
    }

    @Test
    void stream_deliversChangeHintsAfterCommit_withoutMessageText() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);

        var stream = mockMvc.perform(get("/whatsapp/stream").param("token", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        String phone = phone();
        inbound(orgId, phone, "secret contents", Instant.now());
        UUID conversationId = conversationId(orgId, phone);

        String body = "";
        for (int i = 0; i < 30 && !body.contains("MESSAGE_CREATED"); i++) {
            Thread.sleep(100);
            body = stream.getResponse().getContentAsString();
        }
        assertThat(body).contains("event:connected")
                .contains("MESSAGE_CREATED")
                .contains(conversationId.toString())
                .doesNotContain("secret contents");
    }

    // --- helpers -----------------------------------------------------------------------------

    private void inbound(UUID orgId, String phone, String body, Instant at) {
        ingestService.ingest(orgId, InboundMessage.text("wamid." + UUID.randomUUID(), phone, body, at));
    }

    private UUID conversationId(UUID orgId, String phone) {
        return jdbc.queryForObject("SELECT id FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?",
                UUID.class, orgId, phone);
    }

    private void markRead(String token, UUID conversationId) throws Exception {
        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/read")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    private static String phone() {
        return "+2126" + (10000000 + ThreadLocalRandom.current().nextInt(89999999));
    }
}
