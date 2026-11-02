package com.bento.crm.ledger;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A draft invoice settled by a payment posts; repost brings stale entries up to the current rules. */
class LedgerResyncTest extends IntegrationTestBase {

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

    private JsonNode live(String token, String type, String id) throws Exception {
        var res = mockMvc.perform(get("/ledger/source/" + type + "/" + id).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        if (res.getStatus() == 404) return null;
        return objectMapper.readTree(res.getContentAsString());
    }

    private BigDecimal balance(UUID orgId, String code) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM ledger_entry_line l
                JOIN ledger_account a ON a.id = l.account_id WHERE l.organization_id = ? AND a.code = ?
                """, BigDecimal.class, orgId, code);
    }

    @Test
    void aDraftInvoicePaidInFullPostsItsEntry() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String customer = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas\"}", 201).get("id").asText();
        LocalDate today = LocalDate.now();
        String inv = json(post("/invoices"), token, """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "DRAFT", "invoiceDate": "%s", "invoiceNumber": "FA-D-1",
                 "dueDate": "%s", "lines": [{"description": "Item", "qty": 1, "unitPrice": 1000}]}
                """.formatted(customer, today, today.plusDays(30)), 201).get("id").asText();
        assertThat(live(token, "INVOICE", inv)).isNull();

        json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"invoiceId\": \"" + inv + "\", \"paymentDate\": \""
                + today + "\", \"amount\": 1200, \"method\": \"CASH\"}", 201);
        assertThat(json(get("/invoices/" + inv), token, null, 200).get("status").asText()).isEqualTo("PAID");
        JsonNode entry = live(token, "INVOICE", inv);
        assertThat(entry).isNotNull();
        assertThat(entry.get("journalCode").asText()).isEqualTo("VT");
        assertThat(balance(orgId, "3421")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "7124")).isEqualByComparingTo("-1000");
        assertThat(balance(orgId, "44551")).isEqualByComparingTo("-200");
    }

    @Test
    void repostReplacesEntriesPostedUnderAnOlderRule() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        json(post("/bank/accounts"), token, "{\"name\": \"BMCE\", \"accountNumber\": \"" + RIB + "\"}", 201);
        String customer = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas\"}", 201).get("id").asText();
        String pay = json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"paymentDate\": \"" + LocalDate.now()
                + "\", \"amount\": 500, \"method\": \"BANK_TRANSFER\"}", 201).get("id").asText();
        String liveId = live(token, "PAYMENT", pay).get("id").asText();
        assertThat(balance(orgId, "514101")).isEqualByComparingTo("500");

        // As an old rule posted it: on the parent account 5141.
        jdbc.update("""
                UPDATE ledger_entry_line SET account_id = (SELECT id FROM ledger_account WHERE organization_id = ? AND code = '5141')
                WHERE entry_id = ? AND account_id = (SELECT id FROM ledger_account WHERE organization_id = ? AND code = '514101')
                """, orgId, UUID.fromString(liveId), orgId);
        assertThat(balance(orgId, "5141")).isEqualByComparingTo("500");

        JsonNode r = json(post("/ledger/repost"), token, null, 200);
        assertThat(r.get("updated").asInt()).isEqualTo(1);
        assertThat(r.get("posted").asInt()).isZero();
        assertThat(r.get("errors")).isEmpty();
        assertThat(balance(orgId, "5141")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "514101")).isEqualByComparingTo("500");
        assertThat(live(token, "PAYMENT", pay).get("id").asText()).isNotEqualTo(liveId);

        JsonNode again = json(post("/ledger/repost"), token, null, 200);
        assertThat(again.get("updated").asInt()).isZero();
        assertThat(again.get("posted").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isGreaterThan(0);
    }
}
