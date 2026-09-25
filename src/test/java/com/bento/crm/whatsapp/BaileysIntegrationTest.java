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
import java.time.Duration;
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
                        .content("{\"autoCreateLeads\": \"INBOUND\", \"visibility\": \"ALL\", \"outreachPerHour\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoCreateLeads").value("INBOUND"))
                .andExpect(jsonPath("$.outreachPerHour").value(12));
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

    // --- anti-spam: circuit breaker, deferrals, content rules ------------------------------------

    @Test
    void whatsAppsRestriction_pausesOutreach_andTheWarmupStartsOverWhenItEnds() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        assertThat(jdbc.queryForObject("SELECT warmup_started_at FROM wa_account WHERE id = ?", java.sql.Timestamp.class, account.id))
                .as("linking starts the warm-up").isNotNull();

        Instant until = Instant.now().plus(Duration.ofHours(20)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        webhook(events(account.id, """
                {"id": %d, "sessionId": "__SESSION__", "type": "session.status", "data": {
                  "seq": 101, "state": "open", "phoneNumber": "+212600000999", "reachoutLocked": true,
                  "reachoutUntil": "%s", "reachoutType": "BIZ_QUALITY",
                  "newChatQuota": 20, "newChatUsed": 3, "newChatCapStatus": "NONE"}}
                """.formatted(EVENT_IDS.incrementAndGet(), until))).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM wa_account WHERE id = ?", account.id);
        assertThat(instantOf(row.get("reachout_locked_until"))).isEqualTo(until);
        assertThat(row.get("reachout_enforcement")).isEqualTo("BIZ_QUALITY");
        assertThat(instantOf(row.get("outreach_paused_until"))).isEqualTo(until);
        assertThat(instantOf(row.get("warmup_started_at"))).as("the warm-up starts over when the restriction ends").isEqualTo(until);
        assertThat(row.get("new_chat_quota")).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE organization_id = ? AND title = 'WhatsApp outreach paused'",
                Integer.class, account.orgId)).isEqualTo(1);

        mockMvc.perform(get("/whatsapp/account/settings").header("Authorization", "Bearer " + account.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safety.outreach").value("RESTRICTED"))
                .andExpect(jsonPath("$.safety.reachoutEnforcement").value("BIZ_QUALITY"))
                .andExpect(jsonPath("$.safety.newChatQuota").value(20));
    }

    @Test
    void secondWarningFromWhatsApp_pausesOutreachForADay() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        webhook(events(account.id, """
                {"id": %d, "sessionId": "__SESSION__", "type": "session.status", "data": {
                  "seq": 101, "state": "open", "phoneNumber": "+212600000999", "newChatCapStatus": "SECOND_WARNING"}}
                """.formatted(EVENT_IDS.incrementAndGet()))).andExpect(status().isOk());
        Instant paused = instantOf(jdbc.queryForObject("SELECT outreach_paused_until FROM wa_account WHERE id = ?",
                java.sql.Timestamp.class, account.id));
        assertThat(paused).isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
    }

    @Test
    void aRestrictionReportedBySendIsDeferred_withoutSpendingAnAttempt_andPausesOutreach() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        pastWarmup(account);
        String partnerId = partner(account, "Karim Benali", phone());
        Instant until = Instant.now().plus(Duration.ofHours(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        fakeBot.failNextSend(423, "{\"code\":\"REACHOUT_LOCKED\",\"message\":\"restricted\",\"retryable\":false,\"until\":\"" + until + "\"}");

        anyTimeOfDay(() -> {
            String sent = mockMvc.perform(post("/whatsapp/messages").header("Authorization", "Bearer " + account.token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"partnerId\": \"" + partnerId + "\", \"text\": \"Bonjour Karim\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            Map<String, Object> row = awaitMessage(UUID.fromString(JsonPath.read(sent, "$.id")),
                    r -> "REACHOUT_LOCKED".equals(r.get("error_code")));
            assertThat(row.get("status")).isEqualTo("QUEUED");
            assertThat(row.get("attempts")).as("held back, not failed").isEqualTo(0);
            assertThat(instantOf(row.get("not_before"))).isEqualTo(until);
            assertThat(fakeBot.sends().getLast().body().path("newChat").asBoolean()).as("the bot is told it opens a chat").isTrue();
        });
        assertThat(instantOf(jdbc.queryForObject("SELECT outreach_paused_until FROM wa_account WHERE id = ?",
                java.sql.Timestamp.class, account.id))).isEqualTo(until);
    }

    @Test
    void aReplyMarksTheContactsLastMessageRead_andA463ReceiptPausesOutreach() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        pastWarmup(account);
        String phone = phone();
        String inbound = "3EB0%018X".formatted(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        webhook(events(account.id, messageEvent(inbound, "IN", "live", phone, "Salma", "Bonjour, vous êtes ouverts ?")));
        UUID conversationId = (UUID) conversation(account.orgId, phone).get("id");

        String sent = mockMvc.perform(post("/whatsapp/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"Oui, jusqu'à 19h\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Map<String, Object> row = awaitMessage(UUID.fromString(JsonPath.read(sent, "$.id")), r -> "SENT".equals(r.get("status")));
        assertThat(fakeBot.sends().getLast().body().path("readUpTo").path("id").asText()).isEqualTo(inbound);
        assertThat(fakeBot.sends().getLast().body().path("newChat").asBoolean())
                .as("answering someone who wrote first does not open a chat").isFalse();

        webhook(events(account.id, """
                {"id": %d, "sessionId": "__SESSION__", "type": "message.status", "data": {
                  "wamid": "%s", "status": "FAILED", "errorCode": "463", "at": "%s"}}
                """.formatted(EVENT_IDS.incrementAndGet(), row.get("wamid"), Instant.now()))).andExpect(status().isOk());
        Map<String, Object> accountRow = jdbc.queryForMap("SELECT outreach_paused_until, outreach_pause_reason FROM wa_account WHERE id = ?", account.id);
        assertThat(instantOf(accountRow.get("outreach_paused_until")))
                .isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
        assertThat((String) accountRow.get("outreach_pause_reason")).contains("463");
    }

    @Test
    void settingsRefuseLooseLimits_andAdminsCanPauseAndResumeOutreach() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        for (String loose : List.of("{\"outreachPerHour\": 20}", "{\"newChatsPerDay\": 50}",
                "{\"outreachMinGapSeconds\": 10}", "{\"replyMinGapSeconds\": 1}")) {
            mockMvc.perform(put("/whatsapp/account/settings").header("Authorization", "Bearer " + account.token)
                            .contentType(MediaType.APPLICATION_JSON).content(loose))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/whatsapp/account/outreach/pause").header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hours\": 6, \"reason\": \"Salon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safety.outreach").value("PAUSED"))
                .andExpect(jsonPath("$.safety.outreachPauseReason").value("Salon"));
        mockMvc.perform(post("/whatsapp/account/outreach/resume").header("Authorization", "Bearer " + account.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safety.outreach").value("QUIET"))
                .andExpect(jsonPath("$.safety.outreachPausedUntil").doesNotExist());
    }

    @Test
    void aLinkToSomeoneWhoNeverWrote_isRefused() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        String partnerId = partner(account, "Nadia", phone());
        mockMvc.perform(post("/whatsapp/messages").header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\": \"" + partnerId + "\", \"text\": \"Notre catalogue : https://crmbento.com/catalogue\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("spam")));
    }

    @Test
    void campaignsFromALinkedNumber_mustBePersonalized_andEachContactGetsTheirOwnText() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        pastWarmup(account);
        Map<String, String> names = new java.util.LinkedHashMap<>();
        List<String> partnerIds = new ArrayList<>();
        for (String name : List.of("Amine Tazi", "Sara Alaoui", "Youssef Idrissi", "Hind Berrada")) {
            String p = phone();
            names.put(p, name.split(" ")[0]);
            partnerIds.add("\"" + partner(account, name, p) + "\"");
        }
        String campaign = """
                {"title": "Salon", "bodyPreview": "%s", "partnerIds": [%s], "launchNow": true}
                """;
        mockMvc.perform(post("/campaigns/whatsapp").header("Authorization", "Bearer " + account.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaign.formatted("Venez nous voir au salon ce week-end.", String.join(",", partnerIds))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("{{first_name}}")));

        anyTimeOfDay(() -> {
            UUID id = UUID.fromString(JsonPath.read(mockMvc.perform(post("/campaigns/whatsapp")
                            .header("Authorization", "Bearer " + account.token).contentType(MediaType.APPLICATION_JSON)
                            .content(campaign.formatted("{Bonjour|Salut} {{first_name}}, venez nous voir au salon ce week-end.",
                                    String.join(",", partnerIds))))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
            for (Map<String, Object> m : jdbc.queryForList("""
                    SELECT c.phone_e164, m.body FROM wa_message m JOIN wa_conversation c ON c.id = m.conversation_id
                    WHERE m.campaign_id = ?""", id)) {
                assertThat((String) m.get("body")).matches("(Bonjour|Salut) " + names.get((String) m.get("phone_e164"))
                        + ", venez nous voir au salon ce week-end\\.");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message WHERE campaign_id = ?", Integer.class, id)).isEqualTo(4);
        });
    }

    // --- campaigns on a linked number ----------------------------------------------------------

    @Autowired
    private com.bento.crm.whatsapp.config.WaOutboxProperties outboxProperties;

    @Autowired
    private com.bento.crm.whatsapp.service.WaFollowupWorker followupWorker;

    @Test
    void campaignSendsItsTextThroughTheOutbox_skipsIgnoredAndOptedOut_andRelancesWithItsOwnText() throws Exception {
        Account account = baileysAccount("OFF");
        openSession(account);
        pastWarmup(account);
        String reachable = phone();
        String ignored = phone();
        String optedOut = phone();
        jdbc.update("INSERT INTO wa_blocked_number (organization_id, phone_e164) VALUES (?, ?)", account.orgId, ignored);
        webhook(events(account.id, messageEvent("W" + UUID.randomUUID(), "IN", "live", optedOut, "X", "STOP")));
        List<String> partnerIds = new ArrayList<>();
        for (String p : List.of(reachable, ignored, optedOut)) {
            partnerIds.add("\"" + JsonPath.read(mockMvc.perform(post("/partners")
                            .header("Authorization", "Bearer " + account.token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\": \"LEAD\", \"name\": \"C\", \"phone\": \"%s\"}".formatted(p)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id") + "\"");
        }

        boolean businessHours = outboxProperties.isBusinessHoursEnabled();
        org.springframework.test.util.ReflectionTestUtils.setField(outboxProperties, "businessHoursEnabled", false);
        var worker = org.springframework.test.util.AopTestUtils.getTargetObject(followupWorker);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "businessHoursEnabled", false);
        try {
            String campaignId = JsonPath.read(mockMvc.perform(post("/campaigns/whatsapp")
                            .header("Authorization", "Bearer " + account.token).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title": "Rentrée", "bodyPreview": "Offre de rentrée : -20%% cette semaine.",
                                     "followupBody": "Petit rappel pour notre offre de rentrée.",
                                     "followupEnabled": true, "partnerIds": [%s], "launchNow": true}
                                    """.formatted(String.join(",", partnerIds))))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
            UUID campaign = UUID.fromString(campaignId);

            Map<String, String> byPhone = awaitRecipients(campaign, s -> s.containsValue("SENT"));
            assertThat(byPhone).containsEntry(reachable, "SENT").containsEntry(ignored, "SKIPPED").containsEntry(optedOut, "OPTED_OUT");
            assertThat(fakeBot.sends().stream().filter(s -> s.body().path("to").asText().equals(reachable)).toList())
                    .singleElement()
                    .satisfies(s -> assertThat(s.body().path("text").asText()).isEqualTo("Offre de rentrée : -20% cette semaine."));
            assertThat(fakeBot.sends().stream().noneMatch(s -> s.body().path("to").asText().equals(ignored)
                    || s.body().path("to").asText().equals(optedOut))).isTrue();
            assertThat(jdbc.queryForMap("SELECT source, lane, priority FROM wa_message WHERE campaign_id = ? AND sequence_step = 0", campaign))
                    .containsEntry("source", "CAMPAIGN").containsEntry("lane", "OUTREACH").containsEntry("priority", 10);
            awaitCampaignStatus(campaign, "ACTIVE");

            jdbc.update("UPDATE wa_followup SET due_at = now() - interval '1 minute' WHERE campaign_id = ?", campaign);
            for (UUID id : followupWorker.claimBatch()) {
                followupWorker.processOne(id);
            }
            for (int i = 0; i < 60; i++) {
                Integer count = jdbc.queryForObject("SELECT followup_count FROM campaign_recipient WHERE campaign_id = ? AND phone_e164 = ?",
                        Integer.class, campaign, reachable);
                if (count != null && count == 1) break;
                Thread.sleep(100);
            }
            assertThat(fakeBot.sends().getLast().body().path("text").asText()).isEqualTo("Petit rappel pour notre offre de rentrée.");
            assertThat(jdbc.queryForObject("SELECT followup_count FROM campaign_recipient WHERE campaign_id = ? AND phone_e164 = ?",
                    Integer.class, campaign, reachable)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE id = ?", String.class, campaign)).isEqualTo("COMPLETED");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(outboxProperties, "businessHoursEnabled", businessHours);
            org.springframework.test.util.ReflectionTestUtils.setField(worker, "businessHoursEnabled", true);
        }
    }

    private Map<String, String> awaitRecipients(UUID campaignId, java.util.function.Predicate<Map<String, String>> done)
            throws InterruptedException {
        Map<String, String> byPhone = Map.of();
        for (int i = 0; i < 80; i++) {
            byPhone = new java.util.HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT phone_e164, status FROM campaign_recipient WHERE campaign_id = ?", campaignId)) {
                byPhone.put((String) row.get("phone_e164"), (String) row.get("status"));
            }
            if (done.test(byPhone)) break;
            Thread.sleep(100);
        }
        return byPhone;
    }

    private void awaitCampaignStatus(UUID campaignId, String expected) throws InterruptedException {
        String status = null;
        for (int i = 0; i < 50 && !expected.equals(status); i++) {
            status = jdbc.queryForObject("SELECT status FROM campaign WHERE id = ?", String.class, campaignId);
            if (!expected.equals(status)) Thread.sleep(100);
        }
        assertThat(status).isEqualTo(expected);
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

    /** A number linked long ago: no quiet period, no warm-up cap, and no gaps to wait out. */
    private void pastWarmup(Account account) {
        jdbc.update("UPDATE wa_account SET warmup_started_at = now() - interval '60 days', "
                + "outreach_min_gap_seconds = 0, reply_min_gap_seconds = 0 WHERE id = ?", account.id);
    }

    /** Runs {@code body} with business hours off, so outreach is not held by the time of day. */
    private void anyTimeOfDay(ThrowingRunnable body) throws Exception {
        boolean businessHours = outboxProperties.isBusinessHoursEnabled();
        org.springframework.test.util.ReflectionTestUtils.setField(outboxProperties, "businessHoursEnabled", false);
        try {
            body.run();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(outboxProperties, "businessHoursEnabled", businessHours);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private String partner(Account account, String name, String phone) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + account.token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"LEAD\", \"name\": \"%s\", \"phone\": \"%s\"}".formatted(name, phone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Map<String, Object> awaitMessage(UUID id, java.util.function.Predicate<Map<String, Object>> done)
            throws InterruptedException {
        Map<String, Object> row = Map.of();
        for (int i = 0; i < 60; i++) {
            row = jdbc.queryForMap("SELECT * FROM wa_message WHERE id = ?", id);
            if (done.test(row)) {
                return row;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("message never reached the expected state: " + row);
    }

    private static Instant instantOf(Object timestamp) {
        return timestamp == null ? null : ((java.sql.Timestamp) timestamp).toInstant();
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
