package com.bento.crm.invitation;

import com.bento.crm.common.mail.EmailService;
import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plaintext invitation token is never stored or returned by the API -- only its SHA-256 hash
 * is persisted -- so these tests recover it the only way anything can: by capturing the accept
 * URL handed to {@link EmailService}. That doubles as an assertion that the email is dispatched.
 */
class InvitationFlowTest extends IntegrationTestBase {

    @MockBean
    private EmailService emailService;

    @Test
    void invitedUserAcceptsAndReceivesThePreAssignedRole() throws Exception {
        String adminToken = signUpAndLogin();
        String inviteeEmail = "invitee-" + System.nanoTime() + "@example.com";

        mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "role": "MANAGER", "display_name": "Sam Invitee", "job_title": "Ops Lead"}
                                """.formatted(inviteeEmail)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.send_count").value(1));

        String token = captureAcceptToken();

        mockMvc.perform(get("/public/invitations").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(inviteeEmail))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.invited_by_name").value("Test Admin"));

        // The role comes from the invitation, not the request body, and the invitee sets their
        // own password -- the two properties that make this different from createUser.
        mockMvc.perform(post("/public/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "display_name": "Sam Invitee", "password": "InviteePassword123!"}
                                """.formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(inviteeEmail))
                .andExpect(jsonPath("$.user.role").value("MANAGER"))
                .andExpect(jsonPath("$.user.job_title").value("Ops Lead"));

        // The account is real: the password the invitee chose signs them in.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "InviteePassword123!"}
                                """.formatted(inviteeEmail)))
                .andExpect(status().isOk());
    }

    @Test
    void tokenCannotBeReusedAfterAcceptance() throws Exception {
        String adminToken = signUpAndLogin();
        String inviteeEmail = "once-" + System.nanoTime() + "@example.com";

        invite(adminToken, inviteeEmail, "VIEWER");
        String token = captureAcceptToken();

        accept(token, "First Accept").andExpect(status().isOk());
        // A second use must fail rather than create a duplicate account.
        accept(token, "Second Accept").andExpect(status().isNotFound());
    }

    @Test
    void revokingInvalidatesTheOutstandingLink() throws Exception {
        String adminToken = signUpAndLogin();
        String inviteeEmail = "revoked-" + System.nanoTime() + "@example.com";

        String invitationId = objectMapper.readTree(invite(adminToken, inviteeEmail, "SUPPORT"))
                .get("id").asText();
        String token = captureAcceptToken();

        mockMvc.perform(post("/invitations/" + invitationId + "/revoke")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        accept(token, "Too Late").andExpect(status().isNotFound());
    }

    @Test
    void resendingIssuesANewTokenAndRetiresTheOldOne() throws Exception {
        String adminToken = signUpAndLogin();
        String inviteeEmail = "resend-" + System.nanoTime() + "@example.com";

        String invitationId = objectMapper.readTree(invite(adminToken, inviteeEmail, "VIEWER"))
                .get("id").asText();
        String firstToken = captureAcceptToken();

        mockMvc.perform(post("/invitations/" + invitationId + "/resend")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send_count").value(2));

        String secondToken = captureAcceptToken();
        assertThat(secondToken).isNotEqualTo(firstToken);

        // A link that leaked before the resend must stop working.
        accept(firstToken, "Stale Link").andExpect(status().isNotFound());
        accept(secondToken, "Fresh Link").andExpect(status().isOk());
    }

    @Test
    void aSecondPendingInviteForTheSameEmailIsRejected() throws Exception {
        String adminToken = signUpAndLogin();
        String inviteeEmail = "dupe-" + System.nanoTime() + "@example.com";

        invite(adminToken, inviteeEmail, "VIEWER");

        mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "role": "VIEWER"}
                                """.formatted(inviteeEmail)))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptanceEndpointsAreReachableWithoutAuthentication() throws Exception {
        // Guards the SecurityConfig / TenantFilterInterceptor whitelists: a regression there
        // turns every invitation link into a 401 with no other test noticing.
        mockMvc.perform(get("/public/invitations").param("token", "not-a-real-token"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/public/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "not-a-real-token", "display_name": "Nobody", "password": "Password123!"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitingRequiresUserWriteAuthority() throws Exception {
        String adminToken = signUpAndLogin();
        String viewerEmail = "viewer-" + System.nanoTime() + "@example.com";

        invite(adminToken, viewerEmail, "VIEWER");
        String token = captureAcceptToken();
        String viewerToken = objectMapper.readTree(
                        accept(token, "Plain Viewer").andReturn().getResponse().getContentAsString())
                .get("access_token").asText();

        mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nope-%d@example.com", "role": "ADMIN"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSignedInMemberJoinsAnotherOrganizationOnlyWithTheEmailedToken() throws Exception {
        String memberEmail = "member-" + System.nanoTime() + "@example.com";
        String password = "MemberPassword123!";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Member Org", "admin_email": "%s", "admin_name": "Member", "admin_password": "%s"}
                                """.formatted(memberEmail, password)))
                .andExpect(status().isCreated());
        String memberToken = objectMapper.readTree(
                        login(memberEmail, password).andReturn().getResponse().getContentAsString())
                .get("access_token").asText();

        String adminToken = signUpAndLogin();
        String invitationId = objectMapper.readTree(invite(adminToken, memberEmail, "MANAGER")).get("id").asText();
        String emailedToken = captureAcceptToken();

        acceptLoggedIn(memberToken, invitationId, "{}").andExpect(status().isBadRequest());
        acceptLoggedIn(memberToken, invitationId, """
                {"token": "not-the-emailed-token"}
                """).andExpect(status().isNotFound());
        acceptLoggedIn(memberToken, invitationId, """
                {"token": "%s"}
                """.formatted(emailedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("MANAGER"));

        // Joining did not take the member out of their first organization.
        login(memberEmail, password)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.organizations.length()").value(2));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private org.springframework.test.web.servlet.ResultActions acceptLoggedIn(String bearer, String invitationId, String body)
            throws Exception {
        return mockMvc.perform(post("/invitations/" + invitationId + "/accept-logged-in")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String invite(String adminToken, String email, String role) throws Exception {
        return mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "role": "%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions accept(String token, String displayName) throws Exception {
        return mockMvc.perform(post("/public/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "display_name": "%s", "password": "InviteePassword123!"}
                        """.formatted(token, displayName)));
    }

    /** Pulls the token out of the accept URL of the most recent email dispatch. */
    @SuppressWarnings("unchecked")
    private String captureAcceptToken() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailService, atLeastOnce())
                .sendHtml(anyString(), anyString(), eq("invitation"), captor.capture());
        String acceptUrl = captor.getValue().get("acceptUrl");
        return acceptUrl.substring(acceptUrl.indexOf("token=") + "token=".length());
    }
}
