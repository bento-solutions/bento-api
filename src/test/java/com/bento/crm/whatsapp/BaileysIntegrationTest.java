package com.bento.crm.whatsapp;

import com.bento.crm.support.FakeBot;
import com.bento.crm.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Baileys path end to end: linking through the (fake) bot, the signed webhook, lead
 * resolution, echo suppression, session state ordering, and sends through the paced outbox.
 */
class BaileysIntegrationTest extends IntegrationTestBase {

    private static final AtomicLong EVENT_IDS = new AtomicLong();

    @Autowired
    private JdbcTemplate jdbc;

    // --- webhook authentication ----------------------------------------------------------------

    @Test
    void webhookRejectsBadSignatureStaleTimestampAndProxiedCalls() throws Exception {
        String body = "{\"events\":[]}";
        long now = Instant.now().getEpochSecond();

        mockMvc.perform(post("/webhooks/baileys").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Bento-Timestamp", now).header("X-Bento-Signature", "sha256=deadbeef"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/webhooks/baileys").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Bento-Timestamp", now - 3600).header("X-Bento-Signature", sign(now - 3600, body)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/webhooks/baileys").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Bento-Timestamp", now).header("X-Bento-Signature", sign(now, body))
                        .header("X-Real-Ip", "203.0.113.9"))
                .andExpect(status().isForbidden());
        webhook(body).andExpect(status().isOk());
    }

    // --- leads ---------------------------------------------------------------------------------

    @Test
    void unknownNumber_createsALeadOnlyWhenTheAccountAllowsIt() throws Exception {
        Account off = baileysAccount("OFF");
        String phone = phone();
        webhook(events(off.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "Salma", "Bonjour")))
                .andExpect(jsonPath("$.processed.length()").value(1));
        assertThat(partnersWithPhone(off.orgId, phone)).isZero();
        assertThat(conversation(off.orgId, phone).get("partner_id")).isNull();

        Account inbound = baileysAccount("INBOUND");
        webhook(events(inbound.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "Salma", "Bonjour")));
        Map<String, Object> lead = jdbc.queryForMap(
                "SELECT id, name, source, type, assigned_to_user_id FROM partner WHERE organization_id = ? AND phone = ?",
                inbound.orgId, phone);
        assertThat(lead).containsEntry("name", "Salma").containsEntry("source", "WHATSAPP").containsEntry("type", "LEAD");
        assertThat(lead.get("assigned_to_user_id")).isEqualTo(inbound.adminId);
        assertThat(conversation(inbound.orgId, phone).get("partner_id")).isEqualTo(lead.get("id"));
    }

    @Test
    void historyAndPhoneTypedMessages_respectTheSwitch() throws Exception {
        Account account = baileysAccount("INBOUND");
        String historic = phone();
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "history", historic, "Old", "hi")));
        assertThat(partnersWithPhone(account.orgId, historic)).as("history never creates leads").isZero();

        String typedOnPhone = phone();
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "OUT", "live", typedOnPhone, null, "hello")));
        assertThat(partnersWithPhone(account.orgId, typedOnPhone)).as("INBOUND ignores owner-first chats").isZero();

        jdbc.update("UPDATE wa_account SET auto_create_leads = 'INBOUND_AND_PHONE' WHERE id = ?", account.id);
        String another = phone();
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "OUT", "live", another, null, "hello")));
        assertThat(partnersWithPhone(account.orgId, another)).isEqualTo(1);
    }

    @Test
    void concurrentFirstMessagesFromOneNumber_createExactlyOneLead() throws Exception {
        Account account = baileysAccount("INBOUND");
        String phone = phone();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String body = events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "Burst", "m" + i));
            calls.add(() -> webhook(body).andReturn().getResponse().getStatus());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (var f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(partnersWithPhone(account.orgId, phone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message m JOIN wa_conversation c ON c.id = m.conversation_id "
                + "WHERE c.organization_id = ? AND c.phone_e164 = ?", Integer.class, account.orgId, phone))
                .as("a failed event is retried by the bot; here every one succeeded").isEqualTo(6);
    }

    @Test
    void ignoredNumbersAreDropped_andOwnSendEchoesAreNotDuplicated() throws Exception {
        Account account = baileysAccount("INBOUND");
        String phone = phone();
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "X", "hi")));
        UUID conversationId = (UUID) conversation(account.orgId, phone).get("id");
        openSession(account);

        String sent = mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"réponse\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String wamid = awaitWamid(JsonPath.read(sent, "$.id"));

        webhook(events(account.id, messageEvent(wamid, "OUT", "offline", phone, null, "réponse")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message WHERE organization_id = ? AND wamid = ?",
                Integer.class, account.orgId, wamid)).isEqualTo(1);

        mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/ignore")
                .header("Authorization", "Bearer " + account.token)).andExpect(status().isNoContent());
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "X", "again")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?",
                Integer.class, account.orgId, phone)).isZero();
    }

    // --- session state -------------------------------------------------------------------------

    @Test
    void sessionEventsApplyInSequenceOrder_andLogoutAlertsAdmins() throws Exception {
        Account account = baileysAccount("OFF");
        webhook(events(account.id, sessionEvent(5, "open", "+212600000001")));
        webhook(events(account.id, sessionEvent(3, "pairing", null)));

        mockMvc.perform(get("/whatsapp/account/session").header("Authorization", "Bearer " + account.token))
                .andExpect(jsonPath("$.state").value("open"))
                .andExpect(jsonPath("$.linkedPhone").value("+212600000001"));

        webhook(events(account.id, sessionEvent(6, "logged_out", null)));
        assertThat(jdbc.queryForObject("SELECT session_state FROM wa_account WHERE id = ?", String.class, account.id))
                .isEqualTo("logged_out");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE organization_id = ? AND title = 'WhatsApp disconnected'",
                Integer.class, account.orgId)).isEqualTo(1);
    }

    @Test
    void linkingRequestsAPairingCodeForThePreparedNumber() throws Exception {
        String token = signUpAndLogin();
        String view = mockMvc.perform(post("/whatsapp/account/baileys/prepare")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"0612345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("BAILEYS"))
                .andExpect(jsonPath("$.requestedPhone").value("+212612345678"))
                .andReturn().getResponse().getContentAsString();
        String accountId = JsonPath.read(view, "$.accountId");

        mockMvc.perform(post("/whatsapp/account/baileys/link").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pairing"))
                .andExpect(jsonPath("$.pairingCode").value("ABCD-EFGH"));

        var start = fakeBot.requests().stream()
                .filter(r -> r.path().equals("/sessions/" + accountId + "/start")).reduce((a, b) -> b).orElseThrow();
        assertThat(start.body().path("phoneNumber").asText()).isEqualTo("212612345678");

        mockMvc.perform(put("/whatsapp/account/settings").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoCreateLeads\": \"INBOUND\", \"visibility\": \"ALL\", \"outreachPerHour\": 20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateLeads").value("INBOUND"))
                .andExpect(jsonPath("$.outreachPerHour").value(20));
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // --- outbound through the paced outbox -----------------------------------------------------

    @Test
    void replySendsThroughTheBotUnderACrmAssignedId_andRetriesKeepTheSameId() throws Exception {
        Account account = baileysAccount("OFF");
        String phone = phone();
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", phone, "C", "question")));
        UUID conversationId = (UUID) conversation(account.orgId, phone).get("id");
        openSession(account);

        fakeBot.failNextSend(409, "{\"code\":\"SESSION_NOT_OPEN\",\"message\":\"reconnecting\",\"retryable\":true}");
        String sent = mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"réponse\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID messageId = UUID.fromString(JsonPath.read(sent, "$.id"));

        Map<String, Object> row = Map.of();
        for (int i = 0; i < 40; i++) {
            row = jdbc.queryForMap("SELECT * FROM wa_message WHERE id = ?", messageId);
            if ("QUEUED".equals(row.get("status")) && "SESSION_NOT_OPEN".equals(row.get("error_code"))) {
                break;
            }
            Thread.sleep(100);
        }
        String wamid = (String) row.get("wamid");
        assertThat(wamid).matches("3EB0[0-9A-F]{18}");
        assertThat(row.get("lane")).isEqualTo("REPLY");

        jdbc.update("UPDATE wa_message SET not_before = now() WHERE id = ?", messageId);
        for (int i = 0; i < 60 && !"SENT".equals(row.get("status")); i++) {
            Thread.sleep(100);
            row = jdbc.queryForMap("SELECT * FROM wa_message WHERE id = ?", messageId);
        }
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("wamid")).as("the retry reuses the id, so the bot cannot send twice").isEqualTo(wamid);
        assertThat(fakeBot.sends().stream().filter(s -> s.body().path("messageId").asText().equals(wamid)).count())
                .isEqualTo(2);
        assertThat(fakeBot.sends().getLast().body().path("to").asText()).isEqualTo(phone);
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Account(UUID id, UUID orgId, UUID adminId, String token) {
    }

    private Account baileysAccount(String autoCreateLeads) throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String view = mockMvc.perform(post("/whatsapp/account/baileys/prepare")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"+2126" + digits8() + "\", \"autoCreateLeads\": \"" + autoCreateLeads + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID adminId = userIdOf(token);
        jdbc.update("UPDATE wa_account SET default_assignee_user_id = ? WHERE organization_id = ?", adminId, orgId);
        return new Account(UUID.fromString(JsonPath.read(view, "$.accountId")), orgId, adminId, token);
    }

    private void openSession(Account account) throws Exception {
        webhook(events(account.id, sessionEvent(100, "open", "+212600000999")));
    }

    private String awaitWamid(String messageId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String wamid = jdbc.queryForObject("SELECT wamid FROM wa_message WHERE id = ?", String.class, UUID.fromString(messageId));
            if (wamid != null) {
                return wamid;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("message never claimed");
    }

    private ResultActions webhook(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        return mockMvc.perform(post("/webhooks/baileys").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Bento-Timestamp", ts).header("X-Bento-Signature", sign(ts, body)));
    }

    private static String events(UUID sessionId, String... events) {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(events[i].replace("__SESSION__", sessionId.toString()));
        }
        return sb.append("]}").toString();
    }

    private static String messageEvent(String wamid, String direction, String origin, String phone, String pushName, String body) {
        return """
                {"id": %d, "sessionId": "__SESSION__", "type": "message.upsert", "data": {
                  "wamid": "%s", "direction": "%s", "origin": "%s", "phoneE164": "%s", "pushName": %s,
                  "messageType": "text", "body": "%s", "occurredAt": "%s"}}
                """.formatted(EVENT_IDS.incrementAndGet(), wamid, direction, origin, phone,
                pushName == null ? "null" : "\"" + pushName + "\"", body, Instant.now());
    }

    private static String sessionEvent(long seq, String state, String phone) {
        return """
                {"id": %d, "sessionId": "__SESSION__", "type": "session.status", "data": {
                  "seq": %d, "state": "%s", "phoneNumber": %s, "pairingCode": null}}
                """.formatted(EVENT_IDS.incrementAndGet(), seq, state, phone == null ? "null" : "\"" + phone + "\"");
    }

    private static String sign(long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(FakeBot.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> conversation(UUID orgId, String phone) {
        return jdbc.queryForMap("SELECT * FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?", orgId, phone);
    }

    private int partnersWithPhone(UUID orgId, String phone) {
        return jdbc.queryForObject("SELECT count(*) FROM partner WHERE organization_id = ? AND phone = ?", Integer.class, orgId, phone);
    }

    private static String phone() {
        return "+2126" + digits8();
    }

    private static String digits8() {
        return String.valueOf(10000000 + ThreadLocalRandom.current().nextInt(89999999));
    }
}
