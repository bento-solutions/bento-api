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

    // --- pacing (paced accounts; see docs/whatsapp-anti-spam-policy.md) ------------------------

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");
    /** A Wednesday at 11:00 local: inside business hours. */
    private static final Instant WEDNESDAY = ZonedDateTime.of(2026, 9, 23, 11, 0, 0, 0, CASABLANCA).toInstant();
    private static final Instant THURSDAY_9AM = ZonedDateTime.of(2026, 9, 24, 9, 0, 0, 0, CASABLANCA).toInstant();

    @Test
    void gaps_areAtLeastTheMinimum_andNeverTheSameTwice() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        com.bento.crm.whatsapp.model.WaAccount account = account(orgId);
        WaMessage reply = message(WaMessage.Lane.REPLY, false);
        assertThat(pacingPolicy.eligibleAt(account, reply, WEDNESDAY)).as("nothing sent yet").isEqualTo(WEDNESDAY);

        insertSent(orgId, conversationFor(orgId, "+2126" + digits8()), WEDNESDAY.minusSeconds(1), WaMessage.Lane.REPLY, false);
        assertThat(pacingPolicy.eligibleAt(account, reply, WEDNESDAY)).as("8s minimum after the last send").isEqualTo(WEDNESDAY.plusSeconds(7));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, false), WEDNESDAY))
                .as("90s minimum for outreach").isEqualTo(WEDNESDAY.plusSeconds(89));

        java.util.Set<Instant> replyTimes = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            WaMessage m = message(WaMessage.Lane.REPLY, false);
            m.setId(UUID.randomUUID());
            Instant at = pacingPolicy.eligibleAt(account, m, WEDNESDAY);
            assertThat(at).as("8–20s after the last send").isBetween(WEDNESDAY.plusSeconds(7), WEDNESDAY.plusSeconds(19));
            assertThat(pacingPolicy.eligibleAt(account, m, WEDNESDAY)).as("stable for one message").isEqualTo(at);
            replyTimes.add(at);

            WaMessage o = message(WaMessage.Lane.OUTREACH, false);
            o.setId(UUID.randomUUID());
            assertThat(pacingPolicy.eligibleAt(account, o, WEDNESDAY)).as("90–225s for outreach")
                    .isBetween(WEDNESDAY.plusSeconds(89), WEDNESDAY.plusSeconds(224));
        }
        assertThat(replyTimes).as("sends never tick at a regular interval").hasSizeGreaterThan(15);
    }

    @Test
    void outreach_waitsForBusinessHours_andStopsAtTheDailyAndHourlyCaps() throws Exception {
        com.bento.crm.whatsapp.model.WaAccount account = account(orgIdOf(signUpAndLogin()));
        UUID orgId = account.getOrganizationId();
        UUID conversationId = conversationFor(orgId, "+2126" + digits8());

        Instant sunday = ZonedDateTime.of(2026, 9, 27, 11, 0, 0, 0, CASABLANCA).toInstant();
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, false), sunday))
                .as("no outreach on Sunday").isEqualTo(ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, CASABLANCA).toInstant());
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.REPLY, false), sunday))
                .as("replies ignore business hours").isEqualTo(sunday);

        for (int i = 0; i < 10; i++) {
            insertSent(orgId, conversationId, WEDNESDAY.minus(Duration.ofMinutes(90 + i)), WaMessage.Lane.OUTREACH, true);
        }
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, true), WEDNESDAY))
                .as("10 new chats already opened today").isEqualTo(THURSDAY_9AM);

        for (int i = 0; i < 8; i++) {
            insertSent(orgId, conversationId, WEDNESDAY.minus(Duration.ofMinutes(50 - i)), WaMessage.Lane.OUTREACH, false);
        }
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, false), WEDNESDAY))
                .as("8 outreach messages in the last hour").isEqualTo(WEDNESDAY.plus(Duration.ofMinutes(2)));

        com.bento.crm.whatsapp.model.WaAccount other = account(orgIdOf(signUpAndLogin()));
        UUID otherConversation = conversationFor(other.getOrganizationId(), "+2126" + digits8());
        for (int i = 0; i < 22; i++) {
            insertSent(other.getOrganizationId(), otherConversation,
                    ZonedDateTime.of(2026, 9, 23, 9, 2 * i, 0, 0, CASABLANCA).toInstant(), WaMessage.Lane.OUTREACH, false);
        }
        assertThat(pacingPolicy.eligibleAt(other, message(WaMessage.Lane.OUTREACH, false), WEDNESDAY))
                .as("22 outreach messages today: about two per allowed new chat").isEqualTo(THURSDAY_9AM);
    }

    @Test
    void warmup_repliesOnlyForTwoDays_thenTheNewChatCapRisesStepByStep() throws Exception {
        com.bento.crm.whatsapp.model.WaAccount account = account(orgIdOf(signUpAndLogin()));
        account.setWarmupStartedAt(WEDNESDAY.minus(Duration.ofHours(1)));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, true), WEDNESDAY))
                .as("48h quiet period after linking").isEqualTo(WEDNESDAY.plus(Duration.ofHours(47)));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.REPLY, false), WEDNESDAY)).isEqualTo(WEDNESDAY);

        java.util.function.IntFunction<Integer> capOnRampDay = day -> {
            account.setWarmupStartedAt(WEDNESDAY.minus(Duration.ofDays(2L + day)));
            return pacingPolicy.newChatsCap(account, WEDNESDAY);
        };
        assertThat(capOnRampDay.apply(0)).isEqualTo(3);
        assertThat(capOnRampDay.apply(4)).isEqualTo(3);
        assertThat(capOnRampDay.apply(5)).isEqualTo(6);
        assertThat(capOnRampDay.apply(12)).isEqualTo(10);
        account.setNewChatsPerDay(20);
        assertThat(capOnRampDay.apply(25)).as("still warming up").isEqualTo(10);
        assertThat(capOnRampDay.apply(26)).as("then the account's own cap").isEqualTo(20);

        account.setNewChatsPerDay(null);
        account.setWarmupStartedAt(WEDNESDAY.minus(Duration.ofDays(2)));
        UUID conversationId = conversationFor(account.getOrganizationId(), "+2126" + digits8());
        for (int i = 0; i < 3; i++) {
            insertSent(account.getOrganizationId(), conversationId, WEDNESDAY.minus(Duration.ofMinutes(60 + i)),
                    WaMessage.Lane.OUTREACH, true);
        }
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, true), WEDNESDAY))
                .as("first ramp day: 3 new chats").isEqualTo(THURSDAY_9AM);
    }

    @Test
    void outreach_waitsOutAPauseAndWhatsAppsRestriction_whileRepliesStillGo() throws Exception {
        com.bento.crm.whatsapp.model.WaAccount account = account(orgIdOf(signUpAndLogin()));
        account.setOutreachPausedUntil(WEDNESDAY.plus(Duration.ofHours(5)));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, false), WEDNESDAY))
                .isEqualTo(WEDNESDAY.plus(Duration.ofHours(5)));

        account.setReachoutLockedUntil(WEDNESDAY.plus(Duration.ofHours(30)));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, true), WEDNESDAY))
                .as("the later of the two").isEqualTo(WEDNESDAY.plus(Duration.ofHours(30)));
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.REPLY, false), WEDNESDAY)).isEqualTo(WEDNESDAY);
    }

    @Test
    void whatsAppsQuota_isNeverUsedPastHalf_andAWarningHalvesTheCap() throws Exception {
        com.bento.crm.whatsapp.model.WaAccount account = account(orgIdOf(signUpAndLogin()));
        account.setNewChatQuota(10);
        assertThat(pacingPolicy.newChatsCap(account, WEDNESDAY)).isEqualTo(5);
        account.setNewChatCapStatus("FIRST_WARNING");
        assertThat(pacingPolicy.newChatsCap(account, WEDNESDAY)).isEqualTo(2);

        Instant cycleEnd = ZonedDateTime.of(2026, 9, 24, 14, 0, 0, 0, CASABLANCA).toInstant();
        account.setNewChatCapStatus("NONE");
        account.setNewChatQuotaUsed(5);
        account.setNewChatCycleEndsAt(cycleEnd);
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, true), WEDNESDAY))
                .as("half the quota is used: new chats wait for the next cycle").isEqualTo(cycleEnd);
        assertThat(pacingPolicy.eligibleAt(account, message(WaMessage.Lane.OUTREACH, false), WEDNESDAY))
                .as("a relance to an existing chat is not a new chat").isEqualTo(WEDNESDAY);
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

    private static com.bento.crm.whatsapp.model.WaAccount account(UUID orgId) {
        com.bento.crm.whatsapp.model.WaAccount account = new com.bento.crm.whatsapp.model.WaAccount();
        account.setOrganizationId(orgId);
        return account;
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
