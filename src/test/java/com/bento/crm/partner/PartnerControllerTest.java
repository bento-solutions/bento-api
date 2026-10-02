package com.bento.crm.partner;

import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerControllerTest extends IntegrationTestBase {

    private String createPartner(String token, String type, String name) throws Exception {
        String payload = """
                {
                  "type": "%s",
                  "name": "%s",
                  "company_name": "Acme Corp",
                  "stage": "NEW",
                  "estimated_deal_value": 15000.50,
                  "expected_close_date": "2026-12-01"
                }
                """.formatted(type, name);

        String response = mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void create_returnsSnakeCaseFieldsMatchingTheRequest() throws Exception {
        String token = signUpAndLogin();

        mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "LEAD",
                                  "name": "Jane Doe",
                                  "company_name": "Acme Corp",
                                  "stage": "NEW",
                                  "estimated_deal_value": 5000,
                                  "expected_close_date": "2026-12-01"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                // These are exactly the fields the frontend's CreatePartnerRequest sends and
                // partnerFromDto()/leadToPartnerPayload() expect back -- this is the contract
                // the earlier camelCase-entity response used to break.
                .andExpect(jsonPath("$.company_name").value("Acme Corp"))
                .andExpect(jsonPath("$.estimated_deal_value").value(5000))
                .andExpect(jsonPath("$.expected_close_date").value("2026-12-01"))
                .andExpect(jsonPath("$.companyName").doesNotExist())
                .andExpect(jsonPath("$.estimatedDealValue").doesNotExist());
    }

    @Test
    void get_returnsCreatedPartner() throws Exception {
        String token = signUpAndLogin();
        String id = createPartner(token, "LEAD", "Get Test Lead");

        mockMvc.perform(get("/partners/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Get Test Lead"));
    }

    @Test
    void list_returnsPagedContentWithCreatedPartner() throws Exception {
        String token = signUpAndLogin();
        createPartner(token, "LEAD", "List Test Lead");

        mockMvc.perform(get("/partners")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").isNotEmpty());
    }

    @Test
    void listByType_onlyReturnsMatchingType() throws Exception {
        String token = signUpAndLogin();
        createPartner(token, "LEAD", "Type Filter Lead");
        createPartner(token, "VENDOR", "Type Filter Vendor");

        // This is the endpoint ApiService.getPartnersByType() now calls from the frontend,
        // replacing the old (broken) `/partners?type=` query param that Spring silently ignored.
        mockMvc.perform(get("/partners/type/LEAD")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].type", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("LEAD"))));
    }

    @Test
    void leadKpi_countsOnlyLeadsAndBucketsThemByMonth() throws Exception {
        String token = signUpAndLogin();
        createPartner(token, "LEAD", "Kpi Lead One");
        createPartner(token, "LEAD", "Kpi Lead Two");
        createPartner(token, "VENDOR", "Kpi Vendor");

        // Fresh leads land in the current (last) bucket; vendors never count.
        mockMvc.perform(get("/partners/stats/leads")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.new_this_month").value(2))
                .andExpect(jsonPath("$.previous_month").value(0))
                .andExpect(jsonPath("$.monthly_series.length()").value(12))
                .andExpect(jsonPath("$.monthly_series[11]").value(2))
                .andExpect(jsonPath("$.monthly_series[0]").value(0));
    }

    @Test
    void update_persistsChangesAndReturnsSnakeCase() throws Exception {
        String token = signUpAndLogin();
        String id = createPartner(token, "LEAD", "Update Test Lead");

        mockMvc.perform(patch("/partners/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "LEAD",
                                  "name": "Update Test Lead",
                                  "company_name": "Renamed Corp",
                                  "stage": "QUALIFIED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company_name").value("Renamed Corp"))
                .andExpect(jsonPath("$.stage").value("QUALIFIED"));
    }

    @Test
    void delete_removesPartner() throws Exception {
        String token = signUpAndLogin();
        String id = createPartner(token, "LEAD", "Delete Test Lead");

        mockMvc.perform(delete("/partners/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/partners/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/partners"))
                .andExpect(status().isUnauthorized());
    }
}
