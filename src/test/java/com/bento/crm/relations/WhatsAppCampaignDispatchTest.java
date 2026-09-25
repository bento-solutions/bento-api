package com.bento.crm.relations;

import com.bento.crm.support.IntegrationTestBase;
import com.bento.crm.whatsapp.service.WaFollowupWorker;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end dispatch of a WhatsApp campaign on the MOCK provider: create with launchNow, let the
 * background executor send, and check the recipient actually reaches SENT.
 *
 * <p>Dispatch runs off-request with detached entities, and every tenant entity carries an
 * {@code @Version}. This is the path that broke when a detached recipient was saved twice with a
 * stale version, so it is exercised through the real executor rather than by calling the service
 * inside a test transaction.
 */
class WhatsAppCampaignDispatchTest extends IntegrationTestBase {

    @Autowired
    private WaFollowupWorker followupWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void launchedCampaign_sendsToEachReachableRecipient() throws Exception {
        String token = signUpAndLogin();
        String campaignId = launchCampaignToOneContact(token, "+212600000123");

        assertThat(awaitRecipientStatuses(token, campaignId)).containsExactly("SENT");

        String stats = mockMvc.perform(get("/campaigns/" + campaignId + "/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(stats, "$.sent")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(stats, "$.followupsPending")).isEqualTo(1);
    }

    @Test
    void dueRelance_isSentByTheWorker() throws Exception {
        String token = signUpAndLogin();
        String campaignId = launchCampaignToOneContact(token, "+212600000456");
        assertThat(awaitRecipientStatuses(token, campaignId)).containsExactly("SENT");

        jdbcTemplate.update("UPDATE wa_followup SET due_at = now() - interval '1 minute' WHERE campaign_id = ?",
                UUID.fromString(campaignId));

        // The business-hours gate would otherwise make this depend on the wall clock.
        WaFollowupWorker target = AopTestUtils.getTargetObject(followupWorker);
        ReflectionTestUtils.setField(target, "businessHoursEnabled", false);
        try {
            for (UUID id : followupWorker.claimBatch()) {
                followupWorker.processOne(id);
            }
        } finally {
            ReflectionTestUtils.setField(target, "businessHoursEnabled", true);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM wa_followup WHERE campaign_id = ?", String.class, UUID.fromString(campaignId)))
                .isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT followup_count FROM campaign_recipient WHERE campaign_id = ?", Integer.class,
                UUID.fromString(campaignId)))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM campaign WHERE id = ?", String.class, UUID.fromString(campaignId)))
                .isEqualTo("COMPLETED");
    }

    private String launchCampaignToOneContact(String token, String phone) throws Exception {
        mockMvc.perform(post("/whatsapp/account/mock").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String partnerId = JsonPath.read(mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "LEAD", "name": "Dispatch Target", "phone": "%s"}
                                """.formatted(phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        String campaignId = JsonPath.read(mockMvc.perform(post("/campaigns/whatsapp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Dispatch", "templateName": "hello_world", "templateLang": "fr",
                                 "bodyPreview": "Bonjour", "partnerIds": ["%s"], "followupEnabled": true,
                                 "launchNow": true}
                                """.formatted(partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        return campaignId;
    }

    private List<String> awaitRecipientStatuses(String token, String campaignId) throws Exception {
        List<String> statuses = List.of();
        for (int i = 0; i < 50; i++) {
            String body = mockMvc.perform(get("/campaigns/" + campaignId + "/recipients")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            statuses = JsonPath.read(body, "$[*].status");
            if (!statuses.contains("PENDING")) {
                break;
            }
            Thread.sleep(100);
        }
        return statuses;
    }
}
