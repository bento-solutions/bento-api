package com.bento.crm.identity;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A signed-in person's Account across their Organizations: switching, creating, leaving, and their sessions. */
class AccountSessionsTest extends IntegrationTestBase {

    private static final String PASSWORD = "TestPassword123!";

    @Test
    void aPersonCreatesASecondOrganizationAndSwitchesBetweenThem() throws Exception {
        String email = signUp();
        JsonNode first = login(email, PASSWORD);
        UUID firstOrg = orgIdOf(access(first));

        JsonNode second = json(mockMvc.perform(post("/account/organizations")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Second Org", "default_currency": "mad"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("ADMIN")));
        UUID secondOrg = orgIdOf(access(second));
        assertThat(secondOrg).isNotEqualTo(firstOrg);

        mockMvc.perform(get("/account/organizations").header("Authorization", bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_organization_id").value(secondOrg.toString()))
                .andExpect(jsonPath("$.organizations.length()").value(2));

        // One password, two Organizations: signing in asks which one.
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.organizations.length()").value(2));

        JsonNode switched = json(mockMvc.perform(post("/account/switch")
                        .header("Authorization", bearer(second))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organization_id": "%s", "refresh_token": "%s"}
                                """.formatted(firstOrg, refresh(second))))
                .andExpect(status().isOk()));
        assertThat(orgIdOf(access(switched))).isEqualTo(firstOrg);

        // The session it switched away from is over.
        refreshWith(refresh(second)).andExpect(status().isUnauthorized());
        refreshWith(refresh(switched)).andExpect(status().isOk());

        mockMvc.perform(post("/account/switch")
                        .header("Authorization", bearer(switched))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organization_id": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void leavingEndsThatMembershipOnlyAndTheLastAdminCannotLeave() throws Exception {
        String email = signUp();
        JsonNode own = login(email, PASSWORD);

        // The only admin of their own Organization can't leave it.
        mockMvc.perform(post("/account/leave").header("Authorization", bearer(own)))
                .andExpect(status().isConflict());

        // Another Organization invites them as a manager; they accept while signed in.
        String otherAdmin = signUpAndLogin();
        String invitationToken = invite(otherAdmin, email);
        JsonNode invitation = json(mockMvc.perform(get("/public/invitations").param("token", invitationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_account").value(true)));
        JsonNode joined = json(mockMvc.perform(post("/invitations/" + invitation.get("id").asText() + "/accept-logged-in")
                        .header("Authorization", bearer(own))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted(invitationToken)))
                .andExpect(status().isOk()));

        mockMvc.perform(post("/account/leave").header("Authorization", bearer(joined)))
                .andExpect(status().isNoContent());
        refreshWith(refresh(joined)).andExpect(status().isUnauthorized());

        // Only their own Organization is left, so signing in opens it directly.
        JsonNode after = login(email, PASSWORD);
        assertThat(orgIdOf(access(after))).isEqualTo(orgIdOf(access(own)));

        // Having left, they can be invited back.
        invite(otherAdmin, email);
    }

    @Test
    void changingThePasswordEndsOtherSessionsAndKeepsThisDevice() throws Exception {
        String email = signUp();
        JsonNode laptop = login(email, PASSWORD);
        JsonNode phone = login(email, PASSWORD);

        changePassword(laptop, "NotMyPassword1!", "BrandNewPassword456!")
                .andExpect(status().isBadRequest());
        changePassword(laptop, PASSWORD, "short")
                .andExpect(status().isBadRequest());

        JsonNode fresh = json(changePassword(laptop, PASSWORD, "BrandNewPassword456!")
                .andExpect(status().isOk()));

        refreshWith(refresh(laptop)).andExpect(status().isUnauthorized());
        refreshWith(refresh(phone)).andExpect(status().isUnauthorized());
        refreshWith(refresh(fresh)).andExpect(status().isOk());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
        login(email, "BrandNewPassword456!");
    }

    @Test
    void signingOutEverywhereEndsSessionsInEveryOrganization() throws Exception {
        String email = signUp();
        JsonNode first = login(email, PASSWORD);
        JsonNode second = json(mockMvc.perform(post("/account/organizations")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Another Org"}
                                """))
                .andExpect(status().isCreated()));

        mockMvc.perform(post("/account/sign-out-everywhere").header("Authorization", bearer(first)))
                .andExpect(status().isNoContent());

        refreshWith(refresh(first)).andExpect(status().isUnauthorized());
        refreshWith(refresh(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutEndsThisDeviceOnly() throws Exception {
        String email = signUp();
        JsonNode laptop = login(email, PASSWORD);
        JsonNode phone = login(email, PASSWORD);

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", bearer(laptop))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refresh_token": "%s"}
                                """.formatted(refresh(laptop))))
                .andExpect(status().isNoContent());

        refreshWith(refresh(laptop)).andExpect(status().isUnauthorized());
        refreshWith(refresh(phone)).andExpect(status().isOk());
    }

    @Test
    void accountEndpointsNeedASignedInPerson() throws Exception {
        mockMvc.perform(get("/account/organizations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/account/sign-out-everywhere")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String signUp() throws Exception {
        String email = "account-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Org of %s", "admin_email": "%s", "admin_name": "Account Owner", "admin_password": "%s"}
                                """.formatted(email, email, PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private JsonNode login(String email, String password) throws Exception {
        return json(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk()));
    }

    /** Invites {@code email} as a manager and returns the link token an admin can copy. */
    private String invite(String adminToken, String email) throws Exception {
        return json(mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "role": "MANAGER", "display_name": "Invited"}
                                """.formatted(email)))
                .andExpect(status().isCreated())).get("token").asText();
    }

    private ResultActions changePassword(JsonNode session, String current, String next) throws Exception {
        return mockMvc.perform(put("/account/password")
                .header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"current_password": "%s", "new_password": "%s"}
                        """.formatted(current, next)));
    }

    private ResultActions refreshWith(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refresh_token": "%s"}
                        """.formatted(refreshToken)));
    }

    private static String credentials(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String access(JsonNode session) {
        return session.get("access_token").asText();
    }

    private static String refresh(JsonNode session) {
        return session.get("refresh_token").asText();
    }

    private static String bearer(JsonNode session) {
        return "Bearer " + access(session);
    }
}
