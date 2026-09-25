package com.bento.crm.whatsapp;

import com.bento.crm.support.IntegrationTestBase;
import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.ingest.StatusUpdate;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.service.WaIngestService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaIngestServiceTest extends IntegrationTestBase {

    @Autowired
    private WaIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void inboundFromUnknownNumber_createsConversationAndIsIdempotent() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        String phone = randomPhone();
        String wamid = "wamid.T" + UUID.randomUUID();

        assertThat(ingestService.ingest(orgId, InboundMessage.text(wamid, phone, "Bonjour", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.STORED);
        assertThat(ingestService.ingest(orgId, InboundMessage.text(wamid, phone, "Bonjour", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.DUPLICATE);

        Map<String, Object> conversation = conversation(orgId, phone);
        assertThat(conversation.get("unread_count")).isEqualTo(1);
        assertThat(conversation.get("last_message_preview")).isEqualTo("Bonjour");
        assertThat(conversation.get("last_message_direction")).isEqualTo("IN");
        assertThat((java.sql.Timestamp) conversation.get("window_expires_at")).isAfter(java.sql.Timestamp.from(Instant.now()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message WHERE organization_id = ? AND wamid = ?",
                Integer.class, orgId, wamid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM wa_message WHERE organization_id = ? AND wamid = ?",
                String.class, orgId, wamid)).isEqualTo("CONTACT");
    }

    @Test
    void inboundMatchesPartnerByLastNineDigits_andNotifiesAssigneeWithThrottle() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String local = "06" + (10000000 + ThreadLocalRandom.current().nextInt(89999999));
        String partnerId = createPartner(token, "Nadia", local);
        UUID adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE organization_id = ? LIMIT 1", UUID.class, orgId);
        jdbc.update("UPDATE partner SET assigned_to_user_id = ? WHERE id = ?", adminId, UUID.fromString(partnerId));
        String phone = "+212" + local.substring(1);

        ingestService.ingest(orgId, InboundMessage.text("wamid.A" + UUID.randomUUID(), phone, "one", Instant.now()));
        ingestService.ingest(orgId, InboundMessage.text("wamid.B" + UUID.randomUUID(), phone, "two", Instant.now()));

        Map<String, Object> conversation = conversation(orgId, phone);
        assertThat(conversation.get("partner_id")).isEqualTo(UUID.fromString(partnerId));
        assertThat(conversation.get("unread_count")).isEqualTo(2);
        assertThat(notificationsFor(conversation)).as("second message inside the throttle window").isEqualTo(1);

        // Reading the conversation re-arms the notification.
        jdbc.update("UPDATE wa_conversation SET unread_count = 0 WHERE id = ?", conversation.get("id"));
        ingestService.ingest(orgId, InboundMessage.text("wamid.C" + UUID.randomUUID(), phone, "three", Instant.now()));
        assertThat(notificationsFor(conversation)).isEqualTo(2);
    }

    @Test
    void historyAndOwnDeviceMessages_haveNoSideEffects() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        String phone = randomPhone();
        Instant earlier = Instant.now().minus(3, ChronoUnit.DAYS);

        ingestService.ingest(orgId, new InboundMessage("wamid.H" + UUID.randomUUID(), WaMessage.Direction.IN,
                InboundMessage.Origin.HISTORY, phone, null, null, "Old Friend", "text", "old news", null, null,
                null, null, earlier));
        Map<String, Object> conversation = conversation(orgId, phone);
        assertThat(conversation.get("unread_count")).isEqualTo(0);
        assertThat(conversation.get("display_name")).isEqualTo("Old Friend");
        assertThat(notificationsFor(conversation)).isZero();

        ingestService.ingest(orgId, InboundMessage.text("wamid.I" + UUID.randomUUID(), phone, "new", Instant.now()));
        assertThat(conversation(orgId, phone).get("unread_count")).isEqualTo(1);

        String ownWamid = "wamid.O" + UUID.randomUUID();
        ingestService.ingest(orgId, new InboundMessage(ownWamid, WaMessage.Direction.OUT,
                InboundMessage.Origin.LIVE, phone, null, null, null, "text", "typed on my phone", null, null,
                null, null, Instant.now()));
        conversation = conversation(orgId, phone);
        assertThat(conversation.get("unread_count")).as("answering from the phone reads the chat").isEqualTo(0);
        assertThat(conversation.get("last_message_direction")).isEqualTo("OUT");
        assertThat(conversation.get("last_message_preview")).isEqualTo("typed on my phone");
        assertThat(jdbc.queryForMap("SELECT source, status FROM wa_message WHERE organization_id = ? AND wamid = ?",
                orgId, ownWamid)).containsEntry("source", "PHONE").containsEntry("status", "SENT");
    }

    @Test
    void olderMessage_doesNotReplaceNewerPreview() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        String phone = randomPhone();
        ingestService.ingest(orgId, InboundMessage.text("wamid.N" + UUID.randomUUID(), phone, "newest", Instant.now()));
        ingestService.ingest(orgId, InboundMessage.text("wamid.P" + UUID.randomUUID(), phone, "arrived late",
                Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(conversation(orgId, phone).get("last_message_preview")).isEqualTo("newest");
    }

    @Test
    void concurrentMessagesForOneNewContact_areAllKept() throws Exception {
        UUID orgId = orgIdOf(signUpAndLogin());
        String phone = randomPhone();
        int count = 12;

        List<Callable<WaIngestService.Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String wamid = "wamid.C" + i + "-" + UUID.randomUUID();
            String body = "burst " + i;
            tasks.add(() -> ingestService.ingest(orgId, InboundMessage.text(wamid, phone, body, Instant.now())));
        }
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            for (Future<WaIngestService.Outcome> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isEqualTo(WaIngestService.Outcome.STORED);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?",
                Integer.class, orgId, phone)).isEqualTo(1);
        assertThat(conversation(orgId, phone).get("unread_count")).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wa_message m JOIN wa_conversation c ON c.id = m.conversation_id "
                + "WHERE c.organization_id = ? AND c.phone_e164 = ?", Integer.class, orgId, phone)).isEqualTo(count);
    }

    @Test
    void sameWamidInTwoOrganizations_isStoredForEach() throws Exception {
        UUID orgA = orgIdOf(signUpAndLogin());
        UUID orgB = orgIdOf(signUpAndLogin());
        String wamid = "wamid.X" + UUID.randomUUID();

        assertThat(ingestService.ingest(orgA, InboundMessage.text(wamid, randomPhone(), "hi", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.STORED);
        assertThat(ingestService.ingest(orgB, InboundMessage.text(wamid, randomPhone(), "hi", Instant.now())))
                .isEqualTo(WaIngestService.Outcome.STORED);
    }

    @Test
    void reply_marksRecipientRepliedAndCancelsRelance_andReceiptsOnlyMoveForward() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String phone = randomPhone();
        String campaignId = launchCampaign(token, phone);
        UUID campaign = UUID.fromString(campaignId);

        String wamid = jdbc.queryForObject("SELECT wamid FROM wa_message WHERE campaign_id = ?", String.class, campaign);
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ingestService.applyStatus(orgId, new StatusUpdate(wamid, WaMessage.Status.READ, null, null, readAt));
        ingestService.applyStatus(orgId, new StatusUpdate(wamid, WaMessage.Status.DELIVERED, null, null, readAt.minusSeconds(5)));
        ingestService.applyStatus(orgId, new StatusUpdate(wamid, WaMessage.Status.FAILED, "131000", "late failure", readAt));

        Map<String, Object> message = jdbc.queryForMap("SELECT status, delivered_at, read_at, error_code FROM wa_message WHERE campaign_id = ?", campaign);
        assertThat(message.get("status")).isEqualTo("READ");
        assertThat(message.get("delivered_at")).as("late 'delivered' still fills its timestamp").isNotNull();
        assertThat(message.get("error_code")).isNull();
        assertThat(recipientStatus(campaign)).isEqualTo("READ");

        ingestService.ingest(orgId, InboundMessage.text("wamid.R" + UUID.randomUUID(), phone, "Oui, intéressé", Instant.now()));

        assertThat(recipientStatus(campaign)).isEqualTo("REPLIED");
        assertThat(jdbc.queryForObject("SELECT state FROM wa_followup WHERE campaign_id = ?", String.class, campaign))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE id = ?", String.class, campaign))
                .isEqualTo("COMPLETED");
    }

    @Test
    void stop_optsTheContactOut() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String phone = randomPhone();
        UUID campaign = UUID.fromString(launchCampaign(token, phone));

        ingestService.ingest(orgId, InboundMessage.text("wamid.S" + UUID.randomUUID(), phone, " Stop ", Instant.now()));

        assertThat(conversation(orgId, phone).get("opted_out_at")).isNotNull();
        assertThat(recipientStatus(campaign)).isEqualTo("OPTED_OUT");
        assertThat(jdbc.queryForObject("SELECT state FROM wa_followup WHERE campaign_id = ?", String.class, campaign))
                .isEqualTo("CANCELLED");
    }

    @Test
    void metaSimulationEndpoint_goesThroughIngest() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String phone = randomPhone();

        mockMvc.perform(post("/whatsapp/simulate/reply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"%s\", \"text\": \"via meta shape\"}".formatted(phone)))
                .andExpect(status().isOk());

        assertThat(conversation(orgId, phone).get("last_message_preview")).isEqualTo("via meta shape");
    }

    // --- helpers -----------------------------------------------------------------------------

    private String launchCampaign(String token, String phone) throws Exception {
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String partnerId = createPartner(token, "Campaign Contact", phone);
        String campaignId = JsonPath.read(mockMvc.perform(post("/campaigns/whatsapp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Ingest", "templateName": "hello_world", "bodyPreview": "Bonjour",
                                 "partnerIds": ["%s"], "followupEnabled": true, "launchNow": true}
                                """.formatted(partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        for (int i = 0; i < 50; i++) {
            String body = mockMvc.perform(get("/campaigns/" + campaignId + "/recipients")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();
            List<String> statuses = JsonPath.read(body, "$[*].status");
            if (statuses.contains("SENT")) {
                return campaignId;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("campaign never sent");
    }

    private String createPartner(String token, String name, String phone) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"LEAD\", \"name\": \"%s\", \"phone\": \"%s\"}".formatted(name, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Map<String, Object> conversation(UUID orgId, String phone) {
        return jdbc.queryForMap("SELECT * FROM wa_conversation WHERE organization_id = ? AND phone_e164 = ?", orgId, phone);
    }

    private int notificationsFor(Map<String, Object> conversation) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE related_entity_id = ?",
                Integer.class, conversation.get("id"));
    }

    private String recipientStatus(UUID campaignId) {
        return jdbc.queryForObject("SELECT status FROM campaign_recipient WHERE campaign_id = ?", String.class, campaignId);
    }

    private static String randomPhone() {
        return "+2126" + (10000000 + ThreadLocalRandom.current().nextInt(89999999));
    }
}
