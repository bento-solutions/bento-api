package com.bento.crm.ledger;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Cost centers and analytic dimensions (V49): the seeded cost centers, the cost center required on
 * expenses, the Mission / Deal / Customer / Vendor dimensions from expenses to entry lines, the
 * mission summary, the filters and the accountant export.
 */
class AnalyticDimensionsTest extends IntegrationTestBase {

    private static final List<String> SEEDED = List.of("DIR", "COM", "PROD", "ADM", "IT", "MKT");

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private String org() throws Exception {
        String token = signUpAndLogin();
        json(put("/ledger/posting-setup/start-date"), token,
                "{\"startDate\": \"" + (LocalDate.now().getYear() - 1) + "-01-01\"}", 200);
        return token;
    }

    private String partner(String token, String type, String name) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}", 201).get("id").asText();
    }

    private JsonNode live(String token, String type, String id) throws Exception {
        return json(get("/ledger/source/" + type + "/" + id), token, null, 200);
    }

    private static JsonNode lineOn(JsonNode entry, String accountCode) {
        for (JsonNode l : entry.get("lines")) if (l.get("accountCode").asText().equals(accountCode)) return l;
        throw new AssertionError("No line on " + accountCode + " in " + entry);
    }

    @Test
    void defaultCostCentersAreSeededOnceWithoutTouchingExistingCodes() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        // The organization already has a COM cost center of its own before the ledger is set up.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_settings WHERE organization_id = ?", Integer.class, orgId))
                .isZero();
        jdbc.update("INSERT INTO ledger_cost_center (organization_id, code, name) VALUES (?, 'COM', 'Ventes et salons')", orgId);

        JsonNode centers = json(get("/ledger/cost-centers"), token, null, 200);
        Map<String, String> byCode = new HashMap<>();
        centers.forEach(c -> byCode.put(c.get("code").asText(), c.get("name").asText()));
        assertThat(byCode).hasSize(6).containsOnlyKeys(SEEDED);
        assertThat(byCode.get("COM")).isEqualTo("Ventes et salons");
        assertThat(byCode.get("PROD")).isEqualTo("Production / prestations (coût des ventes)");
        assertThat(byCode.get("ADM")).isEqualTo("Administration et fournitures de bureau");
        assertThat(jdbc.queryForObject("SELECT setup_version FROM ledger_settings WHERE organization_id = ?", Integer.class, orgId))
                .isEqualTo(7);

        // Editable; a person can be added; later ledger calls seed nothing again.
        String it = costCenter(token, "IT");
        json(put("/ledger/cost-centers/" + it), token, "{\"name\": \"Systèmes d'information\"}", 200);
        json(post("/ledger/cost-centers"), token, "{\"code\": \"P-KARIM\", \"name\": \"Karim Alami\"}", 201);
        json(post("/ledger/repost"), token, null, 200);
        json(get("/ledger/trial-balance"), token, null, 200);
        JsonNode after = json(get("/ledger/cost-centers"), token, null, 200);
        assertThat(after).hasSize(7);
        assertThat(after.toString()).contains("Systèmes d'information").doesNotContain("Informatique et logiciels");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_cost_center WHERE organization_id = ? AND code = 'COM'",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void anExpenseNeedsACostCenter() throws Exception {
        String token = org();
        String day = LocalDate.now().toString();
        String detail = json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "OFFICE", "amount": 100, "paymentMethod": "CASH"}
                """.formatted(day), 400).get("detail").asText();
        assertThat(detail).isEqualTo("Cost center is required");
        String id = json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "OFFICE", "amount": 100, "paymentMethod": "CASH", "costCenterId": "%s"}
                """.formatted(day, costCenter(token)), 201).get("id").asText();
        assertThat(json(put("/expenses/" + id), token, """
                {"expenseDate": "%s", "category": "OFFICE", "amount": 90, "paymentMethod": "CASH"}
                """.formatted(day), 400).get("detail").asText()).isEqualTo("Cost center is required");
        assertThat(json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "OFFICE", "amount": 100, "paymentMethod": "CASH", "costCenterId": "%s",
                 "missionId": "%s"}
                """.formatted(day, costCenter(token), UUID.randomUUID()), 400).get("detail").asText()).isEqualTo("Unknown mission");
    }

    @Test
    void anExpenseOnAMissionPostsItsDimensionsAndShowsInTheMissionSummary() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas");
        String supplier = partner(token, "VENDOR", "Hôtel Mamounia");
        String deal = json(post("/deals"), token, """
                {"partnerId": "%s", "title": "Audit 2026", "stage": "CLOSED_WON", "amount": 10000, "orderNumber": "CMD-9"}
                """.formatted(customer), 201).get("id").asText();
        String mission = json(post("/deals/" + deal + "/mission"), token, null, 201).get("id").asText();
        // Creating the mission created no cost center.
        assertThat(json(get("/ledger/cost-centers"), token, null, 200)).hasSize(6);
        String prod = costCenter(token, "PROD");

        JsonNode ex = json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "TRAVEL", "description": "Hôtel", "amount": 1200, "vatAmount": 200,
                 "paymentMethod": "CASH", "vendorPartnerId": "%s", "costCenterId": "%s", "missionId": "%s"}
                """.formatted(LocalDate.now(), supplier, prod, mission), 201);
        assertThat(ex.get("missionId").asText()).isEqualTo(mission);
        assertThat(ex.get("missionName").asText()).isEqualTo("Audit 2026");
        assertThat(ex.has("dealId")).isFalse();
        assertThat(ex.get("vendorId").asText()).isEqualTo(supplier);
        assertThat(ex.get("vendorPartnerName").asText()).isEqualTo("Hôtel Mamounia");
        assertThat(ex.get("costCenterCode").asText()).isEqualTo("PROD");

        // The 6143 line carries the mission, the deal and customer filled from it, and the expense's vendor;
        // the VAT and cash lines carry none.
        JsonNode entry = live(token, "EXPENSE", ex.get("id").asText());
        JsonNode charge = lineOn(entry, "6143");
        assertThat(charge.get("missionId").asText()).isEqualTo(mission);
        assertThat(charge.get("dealId").asText()).isEqualTo(deal);
        assertThat(charge.get("dealName").asText()).isEqualTo("Audit 2026");
        assertThat(charge.get("customerId").asText()).isEqualTo(customer);
        assertThat(charge.get("customerName").asText()).isEqualTo("Atlas");
        assertThat(charge.get("vendorId").asText()).isEqualTo(supplier);
        assertThat(charge.get("costCenterCode").asText()).isEqualTo("PROD");
        for (String code : List.of("34552", "5161")) {
            JsonNode l = lineOn(entry, code);
            assertThat(l.has("missionId") || l.has("dealId") || l.has("customerId") || l.has("vendorId")).isFalse();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry_line WHERE organization_id = ? AND mission_id = ? AND deal_id = ?
                  AND customer_id = ? AND vendor_id = ?
                """, Integer.class, orgId, UUID.fromString(mission), UUID.fromString(deal), UUID.fromString(customer),
                UUID.fromString(supplier))).isEqualTo(1);

        // The mission summary counts it, by account and by cost center.
        JsonNode s = json(get("/missions/" + mission + "/summary"), token, null, 200);
        assertThat(s.get("costs")).hasSize(1);
        assertThat(s.get("costs").get(0).get("accountCode").asText()).isEqualTo("6143");
        assertThat(s.get("costsTotal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(s.get("margin").decimalValue()).isEqualByComparingTo("-1000");
        assertThat(s.get("costsByCostCenter")).hasSize(1);
        assertThat(s.get("costsByCostCenter").get(0).get("code").asText()).isEqualTo("PROD");
        assertThat(s.get("costsByCostCenter").get(0).get("name").asText()).isEqualTo("Production / prestations (coût des ventes)");
        assertThat(s.get("costsByCostCenter").get(0).get("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(s.get("documents").toString()).contains("\"EXPENSE\"");

        // A deal alone fills its customer and, having a single mission, that mission.
        JsonNode byDeal = json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "MEALS", "amount": 300, "paymentMethod": "CASH", "costCenterId": "%s",
                 "dealId": "%s"}
                """.formatted(LocalDate.now(), prod, deal), 201);
        JsonNode meal = lineOn(live(token, "EXPENSE", byDeal.get("id").asText()), "6143");
        assertThat(meal.get("missionId").asText()).isEqualTo(mission);
        assertThat(meal.get("customerId").asText()).isEqualTo(customer);
        assertThat(meal.has("vendorId")).isFalse();
        assertThat(json(get("/missions/" + mission + "/summary"), token, null, 200).get("costsTotal").decimalValue())
                .isEqualByComparingTo("1300");

        // Filters: the general ledger and the entries by dimension.
        JsonNode gl = json(get("/ledger/general-ledger?missionId=" + mission), token, null, 200).get("accounts");
        assertThat(gl).hasSize(1);
        assertThat(gl.get(0).get("totalDebit").decimalValue()).isEqualByComparingTo("1300");
        assertThat(gl.get(0).get("lines").get(0).get("missionName").asText()).isEqualTo("Audit 2026");
        assertThat(json(get("/ledger/general-ledger?vendorId=" + supplier), token, null, 200).get("accounts").get(0)
                .get("totalDebit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(json(get("/ledger/entries?customerId=" + customer), token, null, 200).get("total_elements").asInt())
                .isEqualTo(2);
        assertThat(json(get("/ledger/entries?dealId=" + deal + "&costCenterId=" + costCenter(token, "DIR")), token, null, 200)
                .get("total_elements").asInt()).isZero();

        // The accountant export carries the dimensions after SectionAnalytique.
        var res = mockMvc.perform(get("/ledger/export?format=csv").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        String csv = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] rows = csv.substring(1).split("\r\n");
        List<String> header = List.of(rows[0].split(";"));
        int sa = header.indexOf("SectionAnalytique");
        assertThat(header.subList(sa, sa + 6)).containsExactly("SectionAnalytique", "Mission", "Affaire", "Client",
                "Fournisseur", "SourceType");
        boolean found = false;
        for (String row : rows) {
            String[] v = row.split(";", -1);
            if (v.length > sa + 4 && "PROD".equals(v[sa]) && "1000.00".equals(v[header.indexOf("Debit")])) {
                assertThat(v[sa + 1]).isEqualTo("Audit 2026");
                assertThat(v[sa + 2]).isEqualTo("CMD-9");
                assertThat(v[sa + 3]).isEqualTo("Atlas");
                assertThat(v[sa + 4]).isEqualTo("Hôtel Mamounia");
                found = true;
            }
        }
        assertThat(found).as(csv).isTrue();

        // Repost re-syncs entries posted before the dimensions existed, then changes nothing.
        jdbc.update("UPDATE ledger_entry_line SET mission_id = NULL, deal_id = NULL, customer_id = NULL, vendor_id = NULL"
                + " WHERE organization_id = ?", orgId);
        JsonNode repost = json(post("/ledger/repost"), token, null, 200);
        assertThat(repost.get("updated").asInt()).isEqualTo(2);
        assertThat(repost.get("errors")).isEmpty();
        assertThat(lineOn(live(token, "EXPENSE", ex.get("id").asText()), "6143").get("missionId").asText()).isEqualTo(mission);
        assertThat(json(post("/ledger/repost"), token, null, 200).get("updated").asInt()).isZero();
        assertThat(json(get("/missions/" + mission + "/summary"), token, null, 200).get("costsTotal").decimalValue())
                .isEqualByComparingTo("1300");
    }

    @Test
    void anExpenseReportLineUsesTheReportsCostCenterAndItsOwnDimensions() throws Exception {
        String token = org();
        String customer = partner(token, "CUSTOMER", "Atlas");
        String mission = json(post("/missions"), token, "{\"partnerId\": \"" + customer + "\", \"name\": \"Inventaire\"}", 201)
                .get("mission").get("id").asText();
        LocalDate day = LocalDate.now().minusDays(2);
        String com = costCenter(token, "COM");

        // No default on the report: a line without its own cost center is refused.
        String bare = json(post("/expense-reports"), token, "{\"employeeName\": \"Karim\"}", 201).get("id").asText();
        assertThat(json(post("/expense-reports/" + bare + "/lines"), token, """
                {"expenseDate": "%s", "category": "MEALS", "amount": 120}
                """.formatted(day), 400).get("detail").asText()).isEqualTo("Cost center is required");

        String report = json(post("/expense-reports"), token, """
                {"employeeName": "Karim", "periodFrom": "%s", "periodTo": "%s", "costCenterId": "%s"}
                """.formatted(day, day, com), 201).get("id").asText();
        JsonNode r = json(post("/expense-reports/" + report + "/lines"), token, """
                {"expenseDate": "%s", "category": "MEALS", "amount": 120, "vatAmount": 20, "missionId": "%s"}
                """.formatted(day, mission), 201);
        JsonNode line = r.get("lines").get(0);
        assertThat(line.has("costCenterId")).isFalse();
        assertThat(line.get("missionId").asText()).isEqualTo(mission);
        assertThat(line.get("missionName").asText()).isEqualTo("Inventaire");
        json(post("/expense-reports/" + report + "/lines"), token, """
                {"expenseDate": "%s", "category": "TRAVEL", "amount": 50, "costCenterId": "%s"}
                """.formatted(day, costCenter(token, "DIR")), 201);

        JsonNode entry = live(token, "EXPENSE_REPORT", json(post("/expense-reports/" + report + "/post"), token, null, 200)
                .get("id").asText());
        JsonNode meals = lineOn(entry, "6143");
        for (JsonNode l : entry.get("lines")) {
            if (!l.get("accountCode").asText().equals("6143")) continue;
            if (l.get("debit").decimalValue().compareTo(new java.math.BigDecimal("100")) == 0) meals = l;
        }
        assertThat(meals.get("costCenterCode").asText()).isEqualTo("COM");
        assertThat(meals.get("missionId").asText()).isEqualTo(mission);
        assertThat(meals.get("customerId").asText()).isEqualTo(customer);
        assertThat(lineOn(entry, "4438").has("missionId")).isFalse();
        assertThat(json(get("/missions/" + mission + "/summary"), token, null, 200).get("costsTotal").decimalValue())
                .isEqualByComparingTo("100");

        // Checked again at post: a report whose default was removed cannot post a line relying on it.
        json(post("/expense-reports/" + report + "/unpost"), token, null, 200);
        json(put("/expense-reports/" + report), token, "{\"employeeName\": \"Karim\", \"periodFrom\": \"" + day
                + "\", \"periodTo\": \"" + day + "\"}", 200);
        assertThat(json(post("/expense-reports/" + report + "/post"), token, null, 400).get("detail").asText())
                .isEqualTo("Cost center is required");
    }
}
