package com.bento.crm.auth;

import com.bento.crm.identity.model.AppUser;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private com.bento.crm.identity.repository.AppUserRepository appUserRepository;

    @Test
    void login_withValidCredentials_returnsTokensAndSnakeCaseUser() throws Exception {
        String uniqueSuffix = System.nanoTime() + "";
        String email = "login-" + uniqueSuffix + "@example.com";
        String password = "TestPassword123!";

        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Login Test Org",
                                  "admin_email": "%s",
                                  "admin_name": "Login Admin",
                                  "admin_password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.display_name").value("Login Admin"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    /**
     * Two logins for the same user inside one wall-clock second (double-click on "Sign in", a
     * retry, two tabs). The refresh token must differ per login or the second insert hits the
     * unique token-hash constraint and the login 409s.
     */
    @Test
    void login_twiceWithinTheSameSecond_bothSucceed() throws Exception {
        String email = "twice-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Org", "admin_email": "%s", "admin_name": "Admin", "admin_password": "CorrectPassword123!"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        String body = """
                {"email": "%s", "password": "CorrectPassword123!"}
                """.formatted(email);
        String first = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstRefresh = objectMapper.readTree(first).get("refresh_token").asText();
        String secondRefresh = objectMapper.readTree(second).get("refresh_token").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstRefresh, secondRefresh,
                "each login must mint a distinct refresh token");
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        String email = "wrongpass-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Org", "admin_email": "%s", "admin_name": "Admin", "admin_password": "CorrectPassword123!"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "WrongPassword"}
                                """.formatted(email)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void refresh_withValidRefreshToken_issuesNewAccessToken() throws Exception {
        String email = "refresh-" + System.nanoTime() + "@example.com";
        String password = "TestPassword123!";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Org", "admin_email": "%s", "admin_name": "Admin", "admin_password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andReturn().getResponse().getContentAsString();

        String refreshToken = objectMapper.readTree(loginResponse).get("refresh_token").asText();

        // This is the exact request shape AuthApiService.refresh() now sends from the frontend --
        // a JSON body with a snake_case `refresh_token` key, matching RefreshTokenRequest's
        // @JsonProperty. Before that fix the frontend sent an empty body, which this request
        // would reject with 400.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refresh_token": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    @Test
    void refresh_withEmptyBody_returns401() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidToken_returnsCurrentUserAsJson() throws Exception {
        String token = signUpAndLogin();

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").isNotEmpty())
                .andExpect(jsonPath("$.display_name").value("Test Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_whenUserBelongsToMultipleOrganizationsWithSamePassword_returns409WithChoices() throws Exception {
        String uniqueSuffix = System.nanoTime() + "";
        String userEmail = "multi-" + uniqueSuffix + "@example.com";
        String sharedPassword = "SharedPassword123!";

        // 1. Create Org 1 with userEmail as Admin
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "First Org",
                                  "admin_email": "%s",
                                  "admin_name": "First Admin",
                                  "admin_password": "%s"
                                }
                                """.formatted(userEmail, sharedPassword)))
                .andExpect(status().isCreated());

        // 2. Create Org 2 with another admin
        String org2AdminEmail = "org2admin-" + uniqueSuffix + "@example.com";
        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Second Org",
                                  "admin_email": "%s",
                                  "admin_name": "Second Admin",
                                  "admin_password": "Org2Password123!"
                                }
                                """.formatted(org2AdminEmail)))
                .andExpect(status().isCreated());

        String org2Login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Org2Password123!"}
                                """.formatted(org2AdminEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String org2Token = objectMapper.readTree(org2Login).get("access_token").asText();

        // 3. Org 2 invites userEmail
        String inviteResponse = mockMvc.perform(post("/invitations")
                        .header("Authorization", "Bearer " + org2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "role": "SUPPORT",
                                  "display_name": "Invited User"
                                }
                                """.formatted(userEmail)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode invitation = objectMapper.readTree(inviteResponse);
        String rawToken = invitation.get("token").asText();
        String invitationId = invitation.get("id").asText();

        // 4. The address already has an Account, so the signed-out acceptance refuses: setting a
        //    password from the link would let whoever holds it take that Account over.
        mockMvc.perform(post("/public/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "display_name": "Invited User",
                                  "password": "SomeoneElse123!"
                                }
                                """.formatted(rawToken)))
                .andExpect(status().isConflict());

        //    Signed in, the same person accepts and keeps their one password.
        String userLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(userEmail, sharedPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/invitations/" + invitationId + "/accept-logged-in")
                        .header("Authorization", "Bearer " + objectMapper.readTree(userLogin).get("access_token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());

        // The Account now holds two active Memberships, so login asks which organization to open.
        String multiOrgResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(userEmail, sharedPassword)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.organizations").isArray())
                .andExpect(jsonPath("$.organizations.length()").value(2))
                .andExpect(jsonPath("$.organizations[0].joined_at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String org1Id = objectMapper.readTree(multiOrgResponse).get("organizations").get(0).get("organization_id").asText();

        // 5. Choosing an organization opens it without deactivating the other membership.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "organization_id": "%s"
                                }
                                """.formatted(userEmail, sharedPassword, org1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());

        assertThat(appUserRepository.findAllByEmailAcrossOrganizations(userEmail))
                .hasSize(2)
                .allMatch(u -> Boolean.TRUE.equals(u.getIsActive()))
                .extracting(AppUser::getAccountId)
                .containsOnly(appUserRepository.findAllByEmailAcrossOrganizations(userEmail).get(0).getAccountId());
    }
}
