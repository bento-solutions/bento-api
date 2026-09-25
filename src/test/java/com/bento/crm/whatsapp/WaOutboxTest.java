package com.bento.crm.whatsapp;

import com.bento.crm.support.IntegrationTestBase;
import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.service.WaIngestService;
import com.bento.crm.whatsapp.service.WaOutboxWorker;
import com.bento.crm.whatsapp.service.WaPacingPolicy;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaOutboxTest extends IntegrationTestBase {

    @Autowired
    private WaIngestService ingestService;

    @Autowired
    private WaOutboxWorker worker;

    @Autowired
    private WaPacingPolicy pacingPolicy;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void replyOnMock_isSentImmediately() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());

        String id = send(s, "{\"text\": \"Merci, je vous rappelle\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("HUMAN"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = awaitStatus(JsonPath.read(id, "$.id"), "SENT");
        assertThat((String) row.get("wamid")).startsWith("wamid.MOCK");
        assertThat(row.get("lane")).isEqualTo("REPLY");
        assertThat(row.get("sent_at")).isNotNull();
        Map<String, Object> conversation = jdbc.queryForMap("SELECT * FROM wa_conversation WHERE id = ?", s.conversationId);
        assertThat(conversation.get("last_message_direction")).isEqualTo("OUT");
        assertThat(conversation.get("last_message_preview")).isEqualTo("Merci, je vous rappelle");
    }

    @Test
    void clientRef_makesRetriedRequestsIdempotent() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        String body = "{\"text\": \"once\", \"clientRef\": \"req-" + UUID.randomUUID() + "\"}";

        String first = JsonPath.read(send(s, body).andReturn().getResponse().getContentAsString(), "$.id");
        String second = JsonPath.read(send(s, body).andReturn().getResponse().getContentAsString(), "$.id");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message WHERE conversation_id = ? AND direction = 'OUT'",
                Integer.class, s.conversationId)).isEqualTo(1);
    }

    @Test
    void draft_waitsForApproval_andIsSentWithTheEditedText() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        String id = JsonPath.read(send(s, "{\"text\": \"brouillon\", \"mode\": \"DRAFT\"}")
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        Thread.sleep(300);
        assertThat(statusOf(id)).isEqualTo("DRAFT");

        mockMvc.perform(post("/whatsapp/messages/" + id + "/approve")
                        .header("Authorization", "Bearer " + s.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"version corrigée\"}"))
                .andExpect(status().isOk());

        Map<String, Object> row = awaitStatus(id, "SENT");
        assertThat(row.get("body")).isEqualTo("version corrigée");
        assertThat(row.get("approved_by_user_id")).isEqualTo(userIdOf(s.token));

        mockMvc.perform(post("/whatsapp/messages/" + id + "/approve").header("Authorization", "Bearer " + s.token))
                .andExpect(status().isConflict());
    }

    @Test
    void discardedDraft_isNeverSent() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        String id = JsonPath.read(send(s, "{\"text\": \"non\", \"mode\": \"DRAFT\"}")
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/whatsapp/messages/" + id).header("Authorization", "Bearer " + s.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        Thread.sleep(300);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    void retryableFailure_isRequeuedWithBackoff_andPermanentFailureFails() throws Exception {
        // MockWhatsAppProvider: numbers ending 9999 fail retryably, 0000 permanently.
        Setup retry = setupWithInbound("+21261234" + "9999");
        String retryId = JsonPath.read(send(retry, "{\"text\": \"x\"}").andReturn().getResponse().getContentAsString(), "$.id");
        Map<String, Object> row = awaitStatus(retryId, "QUEUED", r -> ((Integer) r.get("attempts")) >= 1);
        assertThat(row.get("error_code")).isEqualTo("80007");
        assertThat(((Timestamp) row.get("not_before")).toInstant()).isAfter(Instant.now());

        Setup permanent = setupWithInbound("+21261234" + "0000");
        String failId = JsonPath.read(send(permanent, "{\"text\": \"x\"}").andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(awaitStatus(failId, "FAILED").get("error_code")).isEqualTo("131026");
    }

    @Test
    void staleSendingOnANonIdempotentProvider_isFailedNotResent() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wa_message (id, organization_id, conversation_id, direction, message_type, body, status,
                                        source, occurred_at, claimed_at, attempts)
                VALUES (?, ?, ?, 'OUT', 'text', 'lost', 'SENDING', 'HUMAN', now(), now() - interval '10 minutes', 1)
                """, id, s.orgId, s.conversationId);

        worker.reapStale();

        assertThat(jdbc.queryForMap("SELECT status, error_code FROM wa_message WHERE id = ?", id))
                .containsEntry("status", "FAILED").containsEntry("error_code", "UNKNOWN_OUTCOME");
    }

    @Test
    void metaAccount_refusesFreeTextOutsideTheServiceWindow() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        jdbc.update("UPDATE wa_account SET provider = 'META' WHERE organization_id = ?", s.orgId);
        jdbc.update("UPDATE wa_conversation SET window_expires_at = now() - interval '1 hour' WHERE id = ?", s.conversationId);

        send(s, "{\"text\": \"too late\"}").andExpect(status().isConflict());
    }

    @Test
    void optedOutContact_cannotBeMessagedOutsideTheWindow() throws Exception {
        Setup s = setupWithInbound("+2126" + digits8());
        jdbc.update("UPDATE wa_conversation SET opted_out_at = now(), last_inbound_at = now() - interval '2 days' WHERE id = ?",
                s.conversationId);

        send(s, "{\"text\": \"promo\"}").andExpect(status().isConflict());
    }

    @Test
    void pacing_spacesRepliesAndHoldsOutreachToCapsAndBusinessHours() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        ZoneId casablanca = ZoneId.of("Africa/Casablanca");
        // A Wednesday at 11:00 local: inside business hours.
        Instant wednesday = ZonedDateTime.of(2026, 9, 23, 11, 0, 0, 0, casablanca).toInstant();
        UUID conversationId = conversationFor(orgId, "+2126" + digits8());

        WaMessage reply = message(WaMessage.Lane.REPLY, false);
        assertThat(pacingPolicy.eligibleAt(orgId, reply, wednesday)).isEqualTo(wednesday);

        insertSent(orgId, conversationId, wednesday.minusSeconds(1), WaMessage.Lane.REPLY, false);
        assertThat(pacingPolicy.eligibleAt(orgId, reply, wednesday))
                .as("3s minimum gap after the last send").isEqualTo(wednesday.plusSeconds(2));
        assertThat(pacingPolicy.eligibleAt(orgId, message(WaMessage.Lane.OUTREACH, false), wednesday))
                .as("10s gap for outreach").isEqualTo(wednesday.plusSeconds(9));

        Instant sunday = ZonedDateTime.of(2026, 9, 27, 11, 0, 0, 0, casablanca).toInstant();
        assertThat(pacingPolicy.eligibleAt(orgId, message(WaMessage.Lane.OUTREACH, false), sunday))
                .as("no outreach on Sunday").isEqualTo(ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, casablanca).toInstant());
        assertThat(pacingPolicy.eligibleAt(orgId, reply, sunday))
                .as("replies ignore business hours").isEqualTo(sunday);

        for (int i = 0; i < 15; i++) {
            insertSent(orgId, conversationId, wednesday.minus(Duration.ofMinutes(90 + i)), WaMessage.Lane.OUTREACH, true);
        }
        assertThat(pacingPolicy.eligibleAt(orgId, message(WaMessage.Lane.OUTREACH, true), wednesday))
                .as("15 new chats already opened today")
                .isEqualTo(ZonedDateTime.of(2026, 9, 24, 9, 0, 0, 0, casablanca).toInstant());

        for (int i = 0; i < 30; i++) {
            insertSent(orgId, conversationId, wednesday.minus(Duration.ofMinutes(50 - i)), WaMessage.Lane.OUTREACH, false);
        }
        assertThat(pacingPolicy.eligibleAt(orgId, message(WaMessage.Lane.OUTREACH, false), wednesday))
                .as("30 outreach messages in the last hour").isAfter(wednesday.plusSeconds(60));
    }

    // --- helpers -----------------------------------------------------------------------------

    private record Setup(String token, UUID orgId, UUID conversationId) {
    }

    /** A MOCK account with one inbound message from {@code phone}, so the reply window is open. */
    private Setup setupWithInbound(String phone) throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        ingestService.ingest(orgId, InboundMessage.text("wamid.in" + UUID.randomUUID(), phone, "Bonjour", Instant.now()));
        UUID conversationId = jdbc.queryForObject(
                "SELECT id FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?", UUID.class, orgId, phone);
        return new Setup(token, orgId, conversationId);
    }

    private org.springframework.test.web.servlet.ResultActions send(Setup s, String json) throws Exception {
        return mockMvc.perform(post("/whatsapp/conversations/" + s.conversationId + "/messages")
                .header("Authorization", "Bearer " + s.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String statusOf(String messageId) {
        return jdbc.queryForObject("SELECT status FROM wa_message WHERE id = ?", String.class, UUID.fromString(messageId));
    }

    private Map<String, Object> awaitStatus(String messageId, String expected) throws InterruptedException {
        return awaitStatus(messageId, expected, r -> true);
    }

    private Map<String, Object> awaitStatus(String messageId, String expected,
                                            java.util.function.Predicate<Map<String, Object>> also)
            throws InterruptedException {
        Map<String, Object> row = Map.of();
        for (int i = 0; i < 50; i++) {
            row = jdbc.queryForMap("SELECT * FROM wa_message WHERE id = ?", UUID.fromString(messageId));
            if (expected.equals(row.get("status")) && also.test(row)) {
                return row;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("message never reached " + expected + ": " + row);
    }

    private UUID conversationFor(UUID orgId, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wa_conversation (id, organization_id, phone_e164) VALUES (?, ?, ?)", id, orgId, phone);
        return id;
    }

    private void insertSent(UUID orgId, UUID conversationId, Instant sentAt, WaMessage.Lane lane, boolean newChat) {
        jdbc.update("""
                INSERT INTO wa_message (id, organization_id, conversation_id, direction, message_type, body, status,
                                        source, occurred_at, sent_at, lane, new_chat)
                VALUES (?, ?, ?, 'OUT', 'text', 'x', 'SENT', 'HUMAN', ?, ?, ?, ?)
                """, UUID.randomUUID(), orgId, conversationId, Timestamp.from(sentAt), Timestamp.from(sentAt),
                lane.name(), newChat);
    }

    private static WaMessage message(WaMessage.Lane lane, boolean newChat) {
        WaMessage m = new WaMessage();
        m.setLane(lane);
        m.setNewChat(newChat);
        return m;
    }

    private static String digits8() {
        return String.valueOf(10000000 + ThreadLocalRandom.current().nextInt(89999999));
    }
}
