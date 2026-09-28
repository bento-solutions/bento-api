package com.bento.crm.relations;

import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the optional, editable link from tasks/tickets to the record they concern, and the
 * "everything attached to this record" reads the customer/deal/proposal cards rely on.
 */
class RelatedRecordsTest extends IntegrationTestBase {

    private String currentUserId(String token) throws Exception {
        String response = mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createPartner(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "CUSTOMER", "name": "%s", "stage": "CUSTOMER"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createTask(String token, String userId, String body) throws Exception {
        String response = mockMvc.perform(post("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(userId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void task_canBeCreatedWithoutAnyLink() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);

        mockMvc.perform(post("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Standalone task", "status": "TODO", "assignedByUserId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relatedEntityType").doesNotExist())
                .andExpect(jsonPath("$.relatedEntityId").doesNotExist());
    }

    @Test
    void task_linkIsEditableAfterCreation() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);
        String partnerId = createPartner(token, "Editable Link Customer");
        String taskId = createTask(token, userId, """
                {"title": "Follow up", "status": "TODO", "assignedByUserId": "%s"}
                """);

        mockMvc.perform(patch("/tasks/" + taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Follow up", "status": "TODO", "assignedByUserId": "%s",
                                 "relatedEntityType": "PARTNER", "relatedEntityId": "%s"}
                                """.formatted(userId, partnerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedEntityType").value("PARTNER"))
                .andExpect(jsonPath("$.relatedEntityId").value(partnerId));

        // …and can be detached again.
        mockMvc.perform(patch("/tasks/" + taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Follow up", "status": "TODO", "assignedByUserId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedEntityType").doesNotExist());
    }

    @Test
    void task_halfFilledLinkIsRejected() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);

        mockMvc.perform(post("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Dangling", "status": "TODO", "assignedByUserId": "%s",
                                 "relatedEntityType": "DEAL"}
                                """.formatted(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ticket_acceptsAnOptionalLinkAndMirrorsPartnerLinksOntoPartnerId() throws Exception {
        String token = signUpAndLogin();
        String partnerId = createPartner(token, "Ticket Customer");

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Unlinked ticket", "status": "OPEN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relatedEntityType").doesNotExist())
                .andExpect(jsonPath("$.partnerId").doesNotExist());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Customer ticket", "status": "OPEN",
                                 "relatedEntityType": "PARTNER", "relatedEntityId": "%s"}
                                """.formatted(partnerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relatedEntityType").value("PARTNER"))
                .andExpect(jsonPath("$.partnerId").value(partnerId));
    }

    @Test
    void ticket_legacyPartnerIdStillCreatesAPartnerLink() throws Exception {
        String token = signUpAndLogin();
        String partnerId = createPartner(token, "Legacy Client");

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Legacy ticket", "status": "OPEN", "partnerId": "%s"}
                                """.formatted(partnerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partnerId").value(partnerId))
                .andExpect(jsonPath("$.relatedEntityType").value("PARTNER"))
                .andExpect(jsonPath("$.relatedEntityId").value(partnerId));
    }

    @Test
    void listsCanBeFilteredToOneRecord() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);
        String customerId = createPartner(token, "Filtered Customer");
        String otherId = createPartner(token, "Other Customer");

        createTask(token, userId, """
                {"title": "Task for customer", "status": "TODO", "assignedByUserId": "%s",
                 "relatedEntityType": "PARTNER", "relatedEntityId": "CUSTOMER_ID"}
                """.replace("CUSTOMER_ID", customerId));
        createTask(token, userId, """
                {"title": "Task for someone else", "status": "TODO", "assignedByUserId": "%s",
                 "relatedEntityType": "PARTNER", "relatedEntityId": "OTHER_ID"}
                """.replace("OTHER_ID", otherId));

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Ticket for customer", "status": "OPEN",
                                 "relatedEntityType": "PARTNER", "relatedEntityId": "%s"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .param("relatedEntityType", "PARTNER")
                        .param("relatedEntityId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Task for customer"));

        mockMvc.perform(get("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .param("relatedEntityType", "PARTNER")
                        .param("relatedEntityId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Ticket for customer"));

        // Unfiltered reads are unaffected by the new parameters.
        mockMvc.perform(get("/tasks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void listsCanBeFilteredByAssignee() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);

        mockMvc.perform(post("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Assigned task", "status": "TODO", "assignedByUserId": "%1$s",
                                 "assignedToUserId": "%1$s"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        createTask(token, userId, """
                {"title": "Unassigned task", "status": "TODO", "assignedByUserId": "%s"}
                """);

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Assigned ticket", "status": "OPEN", "assignedToUserId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Unassigned ticket", "status": "OPEN"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .param("assignedToUserId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Assigned task"));

        mockMvc.perform(get("/tasks")
                        .header("Authorization", "Bearer " + token)
                        .param("assignedToUserId", "none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Unassigned task"));

        mockMvc.perform(get("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .param("assignedToUserId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Assigned ticket"));

        mockMvc.perform(get("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .param("assignedToUserId", "none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Unassigned ticket"));
    }
}
