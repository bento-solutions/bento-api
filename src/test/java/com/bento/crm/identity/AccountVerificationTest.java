package com.bento.crm.identity;

import com.bento.crm.common.mail.EmailService;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With verification required, an Account gets no session until it has shown it reads its inbox.
 * The emailed links are recovered the only way anything can recover them: from the mailer.
 */
@TestPropertySource(properties = "app.identity.require-verified-email=true")
class AccountVerificationTest extends IntegrationTestBase {

    private static final String PASSWORD = "TestPassword123!";

    @MockBean
    private EmailService emailService;

    @Test
    void aNewAccountConfirmsItsAddressBeforeItsFirstSession() throws Exception {
        String email = newAddress();
        signUp(email, "First Org")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("check_email"))
                .andExpect(jsonPath("$.email").value(email));
        String token = linkToken(email, "Confirm your email address for Bento");

        // The password is right, but the address isn't confirmed: no session, and no second
        // email while the first link is fresh.
        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Email Not Verified"));
        verify(emailService, times(1)).sendHtml(eq(email), anyString(), eq("account-link"), anyMap());

        confirm(token).andExpect(status().isNoContent());
        confirm(token).andExpect(status().isNotFound());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void signingUpAgainWithAKnownAddressAnswersTheSameAndCreatesNothing() throws Exception {
        String email = verifiedAccount();

        signUp(email, "Second Attempt Org")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("check_email"))
                .andExpect(jsonPath("$.email").value(email));
        assertThat(subjectsSentTo(email)).contains("You already have a Bento account");

        // Still one Organization, so signing in opens it directly.
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aPasswordResetEndsEverySessionAndTheLinkWorksOnce() throws Exception {
        String email = verifiedAccount();
        JsonNode session = json(login(email, PASSWORD).andExpect(status().isOk()));

        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nobody-" + UUID.randomUUID() + "@example.com\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email.toUpperCase() + "\"}"))
                .andExpect(status().isAccepted());
        String token = linkToken(email, "Choose a new Bento password");

        reset(token, "short").andExpect(status().isBadRequest());
        reset(token, "AFreshPassword789!").andExpect(status().isNoContent());
        reset(token, "AnotherPassword789!").andExpect(status().isNotFound());

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"" + session.get("refresh_token").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, "AFreshPassword789!").andExpect(status().isOk());
    }

    @Test
    void aResetLinkAlsoConfirmsTheAddress() throws Exception {
        String email = newAddress();
        signUp(email, "Unconfirmed Org").andExpect(status().isAccepted());

        mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\"}"))
                .andExpect(status().isAccepted());
        reset(linkToken(email, "Choose a new Bento password"), "AFreshPassword789!").andExpect(status().isNoContent());

        login(email, "AFreshPassword789!").andExpect(status().isOk());
    }

    @Test
    void aCopiedInvitationLinkJoinsButTheAddressStillNeedsConfirming() throws Exception {
        String adminToken = json(login(verifiedAccount(), PASSWORD)).get("access_token").asText();
        String invitee = newAddress();
        String copiedLinkToken = invite(adminToken, invitee).get("token").asText();

        mockMvc.perform(get("/public/invitations").param("token", copiedLinkToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_account").value(false));
        accept(copiedLinkToken, invitee)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("check_email"));
        assertThat(subjectsSentTo(invitee)).contains("Confirm your email address for Bento");
        login(invitee, PASSWORD).andExpect(status().isForbidden());
    }

    @Test
    void theEmailedInvitationLinkConfirmsTheAddress() throws Exception {
        String adminToken = json(login(verifiedAccount(), PASSWORD)).get("access_token").asText();
        String invitee = newAddress();
        String copiedLinkToken = invite(adminToken, invitee).get("token").asText();
        String emailedToken = invitationEmailToken(invitee);
        assertThat(emailedToken).isNotEqualTo(copiedLinkToken);

        accept(emailedToken, invitee)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
        login(invitee, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void resendingAConfirmationNeverRevealsWhetherAnAddressIsKnown() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/verification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + unknown + "\"}"))
                .andExpect(status().isAccepted());
        verify(emailService, never()).sendHtml(eq(unknown), anyString(), anyString(), anyMap());
    }

    // ------------------------------------------------------------------ helpers

    private static String newAddress() {
        return "verify-" + UUID.randomUUID() + "@example.com";
    }

    /** Signs up and confirms the address, so the Account can sign in. */
    private String verifiedAccount() throws Exception {
        String email = newAddress();
        signUp(email, "Org of " + email).andExpect(status().isAccepted());
        confirm(linkToken(email, "Confirm your email address for Bento")).andExpect(status().isNoContent());
        return email;
    }

    private ResultActions signUp(String email, String orgName) throws Exception {
        return mockMvc.perform(post("/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "admin_email": "%s", "admin_name": "Owner", "admin_password": "%s"}
                        """.formatted(orgName, email, PASSWORD)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private ResultActions confirm(String token) throws Exception {
        return mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mockMvc.perform(post("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "password": "%s"}
                        """.formatted(token, password)));
    }

    private JsonNode invite(String adminToken, String email) throws Exception {
        return json(mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "role": "SALESPERSON", "display_name": "New Person"}
                                """.formatted(email)))
                .andExpect(status().isCreated()));
    }

    private ResultActions accept(String token, String email) throws Exception {
        return mockMvc.perform(post("/public/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "display_name": "New Person", "password": "%s"}
                        """.formatted(token, PASSWORD)));
    }

    /** The token in the newest Account email with this subject sent to {@code email}. */
    @SuppressWarnings("unchecked")
    private String linkToken(String email, String subject) {
        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(emailService, atLeastOnce()).sendHtml(eq(email), subjects.capture(), eq("account-link"), variables.capture());
        for (int i = subjects.getAllValues().size() - 1; i >= 0; i--) {
            if (subjects.getAllValues().get(i).equals(subject)) {
                String url = variables.getAllValues().get(i).get("url");
                return url.substring(url.indexOf("token=") + "token=".length());
            }
        }
        throw new AssertionError("No '" + subject + "' email to " + email + "; sent: " + subjects.getAllValues());
    }

    @SuppressWarnings("unchecked")
    private String invitationEmailToken(String email) {
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(emailService, atLeastOnce()).sendHtml(eq(email), anyString(), eq("invitation"), variables.capture());
        String url = variables.getValue().get("acceptUrl");
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private List<String> subjectsSentTo(String email) {
        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendHtml(eq(email), subjects.capture(), anyString(), anyMap());
        return subjects.getAllValues();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
