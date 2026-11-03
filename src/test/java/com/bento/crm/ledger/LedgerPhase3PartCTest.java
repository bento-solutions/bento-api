package com.bento.crm.ledger;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * General ledger phase 3, part C: mission and deal, delivery notes, purchase accounts per service
 * type.
 */
class LedgerPhase3PartCTest extends IntegrationTestBase {

    private static final String RIB = "007780000123456789012345";

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

    private String detail(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        return json(req, token, body, expected).get("detail").asText();
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
        var res = mockMvc.perform(get("/ledger/source/" + type + "/" + id).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        if (res.getStatus() == 404) return null;
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(res.getContentAsString());
    }

    private static Map<String, BigDecimal> byAccount(JsonNode entry) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JsonNode l : entry.get("lines")) {
            m.merge(l.get("accountCode").asText(), l.get("debit").decimalValue().subtract(l.get("credit").decimalValue()),
                    BigDecimal::add);
        }
        return m;
    }

    private BigDecimal balance(UUID orgId, String code) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM ledger_entry_line l
                JOIN ledger_account a ON a.id = l.account_id WHERE l.organization_id = ? AND a.code = ?
                """, BigDecimal.class, orgId, code);
    }

    private void assertTrialBalanceBalances(String token) throws Exception {
        JsonNode totals = json(get("/ledger/trial-balance"), token, null, 200).get("totals");
        assertThat(totals.get("periodDebit").decimalValue()).isEqualByComparingTo(totals.get("periodCredit").decimalValue());
        assertThat(totals.get("closingDebit").decimalValue()).isEqualByComparingTo(totals.get("closingCredit").decimalValue());
    }

    private void send(String token, JsonNode invoice) throws Exception {
        ObjectNode body = (ObjectNode) invoice.deepCopy();
        body.put("status", "SENT");
        json(patch("/invoices/" + invoice.get("id").asText()), token, body.toString(), 200);
    }

    // 1. Mission and deal

    @Test
    void aMissionFromADealSumsUpItsInvoicesAndCostsIntoAMargin() throws Exception {
        String token = org();
        String customer = partner(token, "CUSTOMER", "Atlas");
        String vendor = partner(token, "VENDOR", "Sous-traitant");
        String deal = json(post("/deals"), token, """
                {"partnerId": "%s", "title": "Audit 2026", "stage": "CLOSED_WON", "amount": 10000, "orderNumber": "CMD-42"}
                """.formatted(customer), 201).get("id").asText();

        JsonNode created = json(post("/deals/" + deal + "/mission"), token, null, 201);
        String mission = created.get("id").asText();
        assertThat(created.get("name").asText()).isEqualTo("Audit 2026");
        assertThat(created.get("partnerId").asText()).isEqualTo(customer);
        assertThat(created.get("status").asText()).isEqualTo("PLANNED");
        assertThat(created.get("feeAmount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(created.get("dealId").asText()).isEqualTo(deal);
        assertThat(json(get("/deals/" + deal), token, null, 200).get("missionIds").get(0).asText()).isEqualTo(mission);
        assertThat(json(get("/missions?dealId=" + deal), token, null, 200)).hasSize(1);
        assertThat(json(get("/missions?dealId=" + UUID.randomUUID()), token, null, 200)).isEmpty();

        String cc = null;
        for (JsonNode c : json(get("/ledger/cost-centers"), token, null, 200)) {
            if (mission.equals(c.path("missionId").asText())) {
                assertThat(c.get("code").asText()).isEqualTo("PRJ-CMD-42");
                cc = c.get("id").asText();
            }
        }
        assertThat(cc).isNotNull();

        // A bill on the mission's cost center and an invoice on the deal.
        json(post("/invoices"), token, """
                {"type": "VENDOR", "partnerId": "%s", "status": "SENT", "invoiceDate": "%s", "invoiceNumber": "F-77",
                 "lines": [{"description": "Mission terrain", "qty": 1, "unitPrice": 3000, "costCenterId": "%s"}]}
                """.formatted(vendor, LocalDate.now(), cc), 201);
        json(post("/invoices"), token, """
                {"type": "CUSTOMER", "partnerId": "%s", "dealId": "%s", "status": "SENT", "invoiceDate": "%s",
                 "lines": [{"description": "Audit", "qty": 1, "unitPrice": 8000}]}
                """.formatted(customer, deal, LocalDate.now()), 201);

        JsonNode s = json(get("/missions/" + mission + "/summary"), token, null, 200);
        assertThat(s.get("missionId").asText()).isEqualTo(mission);
        assertThat(s.get("dealId").asText()).isEqualTo(deal);
        assertThat(s.get("costCenterId").asText()).isEqualTo(cc);
        assertThat(s.get("quoted").decimalValue()).isEqualByComparingTo("10000");
        assertThat(s.get("invoiced").decimalValue()).isEqualByComparingTo("8000");
        assertThat(s.get("advances").decimalValue()).isEqualByComparingTo("0");
        assertThat(s.get("costs")).hasSize(1);
        assertThat(s.get("costs").get(0).get("accountCode").asText()).isEqualTo("6125");
        assertThat(s.get("costs").get(0).get("amount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(s.get("costsTotal").decimalValue()).isEqualByComparingTo("3000");
        assertThat(s.get("margin").decimalValue()).isEqualByComparingTo("5000");
        Map<String, Integer> types = new HashMap<>();
        s.get("documents").forEach(d -> types.merge(d.get("type").asText(), 1, Integer::sum));
        assertThat(types).containsEntry("DEAL", 1).containsEntry("INVOICE", 1).containsEntry("BILL", 1);

        // A mission can be linked to a deal by hand too.
        JsonNode other = json(post("/missions"), token, "{\"partnerId\": \"" + customer + "\", \"name\": \"Suivi\", \"dealId\": \""
                + deal + "\"}", 201).get("mission");
        assertThat(other.get("dealId").asText()).isEqualTo(deal);
        assertThat(json(get("/deals/" + deal), token, null, 200).get("missionIds")).hasSize(2);
        json(post("/missions"), token, "{\"partnerId\": \"" + customer + "\", \"name\": \"X\", \"dealId\": \"" + UUID.randomUUID()
                + "\"}", 404);
        // A second mission from the same deal gets the next free code.
        json(post("/deals/" + deal + "/mission"), token, null, 201);
        assertThat(json(get("/ledger/cost-centers"), token, null, 200).toString()).contains("\"PRJ-CMD-42-2\"");
        assertTrialBalanceBalances(token);
    }

    // 2. Delivery notes

    @Test
    void aDeliveredNoteIsProposedAsFaeUntilItsInvoiceIsOut() throws Exception {
        String token = org();
        String customer = partner(token, "CUSTOMER", "Atlas");
        LocalDate today = LocalDate.now();
        LocalDate delivered = today.minusDays(5);
        String deal = json(post("/deals"), token, """
                {"partnerId": "%s", "title": "Serveurs", "stage": "OPEN", "amount": 6000,
                 "orderLines": [{"description": "Serveur", "qty": 2, "unitPrice": 3000}]}
                """.formatted(customer), 201).get("id").asText();

        JsonNode note = json(post("/deals/" + deal + "/delivery-note"), token, null, 201);
        String id = note.get("id").asText();
        assertThat(note.get("status").asText()).isEqualTo("DRAFT");
        assertThat(note.has("number")).isFalse();
        assertThat(note.get("partnerId").asText()).isEqualTo(customer);
        assertThat(note.get("lines")).hasSize(1);
        assertThat(note.get("lines").get(0).get("qty").decimalValue()).isEqualByComparingTo("2");
        JsonNode edited = json(put("/delivery-notes/" + id), token,
                "{\"lines\": [{\"description\": \"Serveur rack\", \"qty\": 2, \"unit\": \"u\"}], \"notes\": \"Quai 3\"}", 200);
        assertThat(edited.get("lines").get(0).get("unit").asText()).isEqualTo("u");
        assertThat(json(get("/delivery-notes?dealId=" + deal), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        detail(post("/delivery-notes/" + id + "/invoice"), token, null, 400);

        // Delivered: numbered, the deal awaits invoicing; a delivery note posts nothing.
        JsonNode d = json(post("/delivery-notes/" + id + "/deliver"), token,
                "{\"date\": \"" + delivered + "\", \"signedBy\": \"M. Alami\"}", 200);
        assertThat(d.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(d.get("number").asText()).matches("BL-\\d{4}-\\d{4}");
        assertThat(d.get("date").asText()).isEqualTo(delivered.toString());
        assertThat(d.get("signedBy").asText()).isEqualTo("M. Alami");
        JsonNode dealNow = json(get("/deals/" + deal), token, null, 200);
        assertThat(dealNow.get("stage").asText()).isEqualTo("AWAITING_INVOICING");
        assertThat(dealNow.get("deliveryDate").asText()).isEqualTo(delivered.toString());
        detail(put("/delivery-notes/" + id), token, "{\"notes\": \"x\"}", 400);
        detail(delete("/delivery-notes/" + id), token, null, 400);
        var pdf = mockMvc.perform(get("/delivery-notes/" + id + "/pdf").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        // FAE from the note, once, in place of the deal.
        assertThat(fae(token, today.minusDays(1), id)).isNotNull();
        assertThat(fae(token, today.minusDays(1), id).get("base").decimalValue()).isEqualByComparingTo("6000");
        assertThat(fae(token, today.minusDays(1), deal)).isNull();
        assertThat(fae(token, delivered.minusDays(1), id)).isNull();

        // The invoice from the note: a draft on the deal, with the deal's lines and prices.
        JsonNode inv = json(post("/delivery-notes/" + id + "/invoice"), token, null, 201);
        assertThat(inv.get("status").asText()).isEqualTo("DRAFT");
        assertThat(inv.get("dealId").asText()).isEqualTo(deal);
        assertThat(inv.get("subtotal").decimalValue()).isEqualByComparingTo("6000");
        assertThat(inv.get("total").decimalValue()).isEqualByComparingTo("7200");
        JsonNode invoiced = json(get("/delivery-notes/" + id), token, null, 200);
        assertThat(invoiced.get("status").asText()).isEqualTo("INVOICED");
        assertThat(invoiced.get("invoiceId").asText()).isEqualTo(inv.get("id").asText());
        // A draft invoice is not out yet.
        assertThat(fae(token, today, id)).isNotNull();

        send(token, inv);
        assertThat(fae(token, today, id)).isNull();
        assertThat(fae(token, today.minusDays(1), id)).isNotNull();
        assertThat(json(get("/delivery-notes?status=INVOICED"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        assertTrialBalanceBalances(token);
    }

    private JsonNode fae(String token, LocalDate date, String sourceId) throws Exception {
        for (JsonNode f : json(get("/ledger/cutoff/proposals?date=" + date), token, null, 200).get("fae")) {
            if (sourceId.equals(f.path("sourceId").asText())) return f;
        }
        return null;
    }

    // 3. Purchase accounts per service type

    @Test
    void subcontractingAndTransportBillLinesPostToTheirPurchaseAccounts() throws Exception {
        String token = org();
        String vendor = partner(token, "VENDOR", "Prestataire");
        JsonNode setup = json(get("/ledger/posting-setup"), token, null, 200);
        Map<String, JsonNode> product = new HashMap<>();
        setup.get("productGroups").forEach(g -> product.put(g.get("code").asText(), g));
        assertThat(product.get("SUBCONTRACTING").get("name").asText()).isEqualTo("Sous-traitance et prestations");
        assertThat(product.get("SUBCONTRACTING").get("vatCode").asText()).isEqualTo("TVA20");

        String bill = json(post("/invoices"), token, """
                {"type": "VENDOR", "partnerId": "%s", "status": "SENT", "invoiceDate": "%s", "invoiceNumber": "F-9",
                 "lines": [{"description": "Étude", "qty": 1, "unitPrice": 1000, "productGroupId": "%s"},
                           {"description": "Livraison", "qty": 1, "unitPrice": 500, "productGroupId": "%s"}]}
                """.formatted(vendor, LocalDate.now(), product.get("SUBCONTRACTING").get("id").asText(),
                product.get("TRANSPORT").get("id").asText()), 201).get("id").asText();
        Map<String, BigDecimal> m = byAccount(live(token, "BILL", bill));
        assertThat(m.get("6126")).isEqualByComparingTo("1000");
        assertThat(m.get("6142")).isEqualByComparingTo("500");
        assertThat(m).doesNotContainKey("6125");
        assertThat(m.get("34552")).isEqualByComparingTo("270");
        assertThat(m.get("4411")).isEqualByComparingTo("-1770");
        assertTrialBalanceBalances(token);
    }
}
