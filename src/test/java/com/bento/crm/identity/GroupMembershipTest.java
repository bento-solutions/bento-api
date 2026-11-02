package com.bento.crm.identity;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GroupMembershipTest extends IntegrationTestBase {

    @Test
    void createdGroup_keepsTheChosenMembersAndItsCreator_acrossReads() throws Exception {
        String adminToken = signUpAndLogin();
        UUID colleague = userIdOf(tokenForNewUser(orgIdOf(adminToken), UserRole.SALESPERSON));

        JsonNode created = createGroup(adminToken, Map.of("name", "Finance sync", "memberUserIds", List.of(colleague)));
        assertThat(members(created)).containsExactlyInAnyOrder(userIdOf(adminToken).toString(), colleague.toString());

        JsonNode read = readJson(adminToken, "/groups/" + created.get("id").asText());
        assertThat(members(read)).containsExactlyInAnyOrder(userIdOf(adminToken).toString(), colleague.toString());

        JsonNode listed = readJson(adminToken, "/groups").get("content").get(0);
        assertThat(members(listed)).containsExactlyInAnyOrder(userIdOf(adminToken).toString(), colleague.toString());
    }

    @Test
    void createGroup_withoutMembers_stillIncludesTheCreator() throws Exception {
        String adminToken = signUpAndLogin();

        JsonNode created = createGroup(adminToken, Map.of("name", "Just me"));

        assertThat(members(created)).containsExactly(userIdOf(adminToken).toString());
    }

    @Test
    void createGroup_refusesAMemberFromAnotherOrganization() throws Exception {
        String adminToken = signUpAndLogin();
        UUID outsider = userIdOf(signUpAndLogin());

        mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Leaky", "memberUserIds", List.of(outsider)))))
                .andExpect(status().isBadRequest());

        assertThat(readJson(adminToken, "/groups").get("content")).isEmpty();
    }

    @Test
    void updateGroup_replacesMembersOnlyWhenTheyAreSent() throws Exception {
        String adminToken = signUpAndLogin();
        UUID orgId = orgIdOf(adminToken);
        UUID first = userIdOf(tokenForNewUser(orgId, UserRole.SALESPERSON));
        UUID second = userIdOf(tokenForNewUser(orgId, UserRole.SUPPORT));
        JsonNode created = createGroup(adminToken, Map.of("name", "Ops", "memberUserIds", List.of(first)));
        String id = created.get("id").asText();

        patchGroup(adminToken, id, Map.of("name", "Operations"));
        JsonNode renamed = readJson(adminToken, "/groups/" + id);
        assertThat(renamed.get("name").asText()).isEqualTo("Operations");
        assertThat(members(renamed)).containsExactlyInAnyOrder(userIdOf(adminToken).toString(), first.toString());

        patchGroup(adminToken, id, Map.of("name", "Operations", "memberUserIds", List.of(second)));
        assertThat(members(readJson(adminToken, "/groups/" + id))).containsExactly(second.toString());
    }

    @Test
    void updateGroup_refusesAMemberFromAnotherOrganization() throws Exception {
        String adminToken = signUpAndLogin();
        UUID outsider = userIdOf(signUpAndLogin());
        String id = createGroup(adminToken, Map.of("name", "Ops")).get("id").asText();

        mockMvc.perform(patch("/groups/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Ops", "memberUserIds", List.of(outsider)))))
                .andExpect(status().isBadRequest());

        assertThat(members(readJson(adminToken, "/groups/" + id))).containsExactly(userIdOf(adminToken).toString());
    }

    @Test
    void meeting_keepsItsDurationAndAttendees() throws Exception {
        String adminToken = signUpAndLogin();
        UUID colleague = userIdOf(tokenForNewUser(orgIdOf(adminToken), UserRole.SALESPERSON));
        String id = createGroup(adminToken, Map.of("name", "Ops", "memberUserIds", List.of(colleague))).get("id").asText();

        mockMvc.perform(post("/groups/" + id + "/meetings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Weekly",
                                "scheduledAt", "2030-01-15T09:00:00Z",
                                "durationMinutes", 90,
                                "attendeeUserIds", List.of(colleague)))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/groups/" + id + "/meetings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Weekly"))
                .andExpect(jsonPath("$.content[0].durationMinutes").value(90))
                .andExpect(jsonPath("$.content[0].attendeeUserIds[0]").value(colleague.toString()));
    }

    @Test
    void meeting_refusesAnAttendeeFromAnotherOrganization() throws Exception {
        String adminToken = signUpAndLogin();
        UUID outsider = userIdOf(signUpAndLogin());
        String id = createGroup(adminToken, Map.of("name", "Ops")).get("id").asText();

        mockMvc.perform(post("/groups/" + id + "/meetings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Weekly",
                                "scheduledAt", "2030-01-15T09:00:00Z",
                                "attendeeUserIds", List.of(outsider)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteGroup_removesItsMessagesAndMeetings() throws Exception {
        String adminToken = signUpAndLogin();
        String id = createGroup(adminToken, Map.of("name", "Short-lived")).get("id").asText();

        mockMvc.perform(post("/groups/" + id + "/messages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hello"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/groups/" + id + "/meetings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Weekly", "scheduledAt", "2030-01-15T09:00:00Z"))))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/groups/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/groups/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private JsonNode createGroup(String token, Map<String, ?> body) throws Exception {
        String response = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void patchGroup(String token, String id, Map<String, ?> body) throws Exception {
        mockMvc.perform(patch("/groups/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private JsonNode readJson(String token, String path) throws Exception {
        String response = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static List<String> members(JsonNode group) {
        List<String> ids = new ArrayList<>();
        group.get("memberUserIds").forEach(id -> ids.add(id.asText()));
        return ids;
    }
}
