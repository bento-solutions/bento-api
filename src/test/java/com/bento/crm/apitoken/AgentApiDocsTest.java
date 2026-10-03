package com.bento.crm.apitoken;

import com.bento.crm.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Swagger documentation for AI agents. It is public (no login: the people reading it are the
 * ones about to create a token), so what it lists must be exactly what a token may call.
 */
class AgentApiDocsTest extends IntegrationTestBase {

    private String spec(String group) throws Exception {
        return mockMvc.perform(get("/openapi/" + group))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void agentSpecIsPublicAndListsOnlyWhatATokenMayCall() throws Exception {
        String json = spec("agents");

        Map<String, Map<String, Object>> paths = JsonPath.read(json, "$.paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/whatsapp/conversations",
                "/whatsapp/conversations/{id}",
                "/whatsapp/conversations/by-partner/{partnerId}",
                "/whatsapp/conversations/lookup",
                "/whatsapp/conversations/{id}/messages",
                "/whatsapp/conversations/{id}/read",
                "/whatsapp/messages",
                "/whatsapp/messages/{id}",
                "/whatsapp/unread-summary",
                "/partners",
                "/partners/{id}",
                "/api-tokens/me");

        // Operations a token is refused (approving drafts, writing partners) must not be advertised.
        assertThat(paths.get("/partners").keySet()).containsExactly("get");
        assertThat(paths.get("/partners/{id}").keySet()).containsExactly("get");
        assertThat(paths.get("/whatsapp/messages/{id}").keySet()).containsExactlyInAnyOrder("get", "delete");
        assertThat(paths.get("/whatsapp/messages")).containsOnlyKeys("post");
    }

    @Test
    void everyAdvertisedOperationIsDocumentedInPlainLanguage() throws Exception {
        String json = spec("agents");
        Map<String, Map<String, Map<String, Object>>> paths = JsonPath.read(json, "$.paths");
        paths.forEach((path, operations) -> operations.forEach((method, operation) ->
                assertThat(operation.get("summary")).as("%s %s summary", method, path).isNotNull()));

        List<String> undocumented = paths.entrySet().stream()
                .flatMap(p -> p.getValue().entrySet().stream()
                        .filter(op -> op.getValue().get("description") == null)
                        .map(op -> op.getKey() + " " + p.getKey()))
                .toList();
        assertThat(undocumented).as("operations without a description").isEmpty();
    }

    @Test
    void agentSpecExplainsSetupAndDeclaresTokenAuth() throws Exception {
        String json = spec("agents");

        String guide = JsonPath.read(json, "$.info.description");
        assertThat(guide).contains("Settings → API tokens", "bento_pat_", "/api/v1/openapi/agents", "/api/v1/mcp");
        assertThat((String) JsonPath.read(json, "$.components.securitySchemes.apiToken.scheme")).isEqualTo("bearer");
        assertThat((List<?>) JsonPath.read(json, "$.security")).hasSize(1);
        assertThat((String) JsonPath.read(json, "$.paths['/whatsapp/conversations'].get.responses['429'].description"))
                .contains("Too many requests");
    }

    @Test
    void fullApiStaysAvailableForDevelopers() throws Exception {
        Map<String, Object> paths = JsonPath.read(spec("full-api"), "$.paths");
        assertThat(paths).containsKeys("/users", "/auth/login", "/whatsapp/conversations");
    }

    @Test
    void swaggerRouteServesWithoutLogin() throws Exception {
        // springdoc redirects /swagger to the UI; either way it must not demand a login.
        int code = mockMvc.perform(get("/swagger")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 302);

        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        String config = mockMvc.perform(get("/openapi/swagger-config"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(config).contains("AI agents (start here)").contains("Complete API (developers)");
    }
}
