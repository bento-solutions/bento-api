package com.bento.crm.ledger;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.common.service.EntityPurgeScheduler;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** General ledger phase 2: the posting setup and the automatic postings of documents. */
class LedgerPostingTest extends IntegrationTestBase {

    private static final String RIB = "007780000123456789012345";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityPurgeScheduler purgeScheduler;

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

    private Map<String, String> accounts(String token) throws Exception {
        Map<String, String> ids = new HashMap<>();
        for (JsonNode a : json(get("/ledger/accounts?includeInactive=true"), token, null, 200)) {
            ids.put(a.get("code").asText(), a.get("id").asText());
        }
        return ids;
    }

    private String partner(String token, String type, String name, String extra) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\""
                + (extra == null ? "" : ", " + extra) + "}", 201).get("id").asText();
    }

    private static String invoiceBody(String type, String partnerId, String status, LocalDate date, String number,
                                      String lines) {
        return """
                {"type": "%s", "partnerId": "%s", "status": "%s", "invoiceDate": "%s", "invoiceNumber": "%s",
                 "dueDate": "%s", "lines": [%s]}
                """.formatted(type, partnerId, status, date, number, date.plusDays(30), lines);
    }

    private static String line(int qty, int price, String vat, String extra) {
        return "{\"description\": \"Item\", \"qty\": " + qty + ", \"unitPrice\": " + price + ", \"vatRate\": " + vat
                + (extra == null ? "" : ", " + extra) + "}";
    }

    /** The live entry of a document, or null. */
    private JsonNode live(String token, String type, String id) throws Exception {
        var res = mockMvc.perform(get("/ledger/source/" + type + "/" + id).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        if (res.getStatus() == 404) return null;
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(res.getContentAsString());
    }

    /** account code -> debit - credit of an entry's lines. */
    private static Map<String, BigDecimal> byAccount(JsonNode entry) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JsonNode l : entry.get("lines")) {
            m.merge(l.get("accountCode").asText(), l.get("debit").decimalValue().subtract(l.get("credit").decimalValue()),
                    BigDecimal::add);
        }
        return m;
    }

    private static void assertBalanced(JsonNode entry) {
        assertThat(entry.get("totalDebit").decimalValue()).isEqualByComparingTo(entry.get("totalCredit").decimalValue());
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (JsonNode l : entry.get("lines")) {
            d = d.add(l.get("debit").decimalValue());
            c = c.add(l.get("credit").decimalValue());
        }
        assertThat(d).isEqualByComparingTo(c);
    }

    /** Every entry (live or reversal) recorded for a document. */
    private JsonNode entriesOf(String token, String type, String id) throws Exception {
        return json(get("/ledger/entries?sourceType=" + type + "&sourceId=" + id), token, null, 200).get("content");
    }

    private BigDecimal balance(UUID orgId, String code, String partnerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM ledger_entry_line l
                JOIN ledger_account a ON a.id = l.account_id
                WHERE l.organization_id = ? AND a.code = ? AND (CAST(? AS uuid) IS NULL OR l.partner_id = CAST(? AS uuid))
                """, BigDecimal.class, orgId, code, partnerId, partnerId);
    }

    private void assertTrialBalanceBalances(String token) throws Exception {
        JsonNode totals = json(get("/ledger/trial-balance"), token, null, 200).get("totals");
        assertThat(totals.get("periodDebit").decimalValue()).isEqualByComparingTo(totals.get("periodCredit").decimalValue());
        assertThat(totals.get("closingDebit").decimalValue()).isEqualByComparingTo(totals.get("closingCredit").decimalValue());
    }

    private static LocalDate today() {
        return LocalDate.now();
    }

    // Setup

    @Test
    void postingSetupIsSeededAndEditableWithItsRules() throws Exception {
        String token = signUpAndLogin();
        Map<String, String> acc = accounts(token);
        assertThat(acc).containsKeys("3481", "4481", "7121", "7127", "44551", "44552", "44553", "44554", "5161");

        JsonNode s = json(get("/ledger/posting-setup"), token, null, 200);
        assertThat(s.get("partnerGroups")).hasSize(4);
        Map<String, JsonNode> groups = new HashMap<>();
        s.get("partnerGroups").forEach(g -> groups.put(g.get("side").asText() + "/" + g.get("code").asText(), g));
        assertThat(groups.get("CUSTOMER/LOCAL").get("accountCode").asText()).isEqualTo("3421");
        assertThat(groups.get("CUSTOMER/LOCAL").get("isDefault").asBoolean()).isTrue();
        assertThat(groups.get("CUSTOMER/GROUP").get("accountCode").asText()).isEqualTo("3481");
        assertThat(groups.get("SUPPLIER/LOCAL").get("accountCode").asText()).isEqualTo("4411");
        assertThat(groups.get("SUPPLIER/GROUP").get("accountCode").asText()).isEqualTo("4481");
        Map<String, String> business = new HashMap<>(), product = new HashMap<>();
        s.get("businessGroups").forEach(g -> business.put(g.get("code").asText(), g.get("id").asText()));
        s.get("productGroups").forEach(g -> product.put(g.get("code").asText(), g.get("id").asText()));
        assertThat(business).containsOnlyKeys("MAROC", "EXPORT");
        assertThat(product).containsOnlyKeys("GOODS", "SERVICES");
        assertThat(s.get("businessGroups").get(0).get("code").asText()).isEqualTo("MAROC");
        assertThat(s.get("businessGroups").get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(s.get("productGroups").get(0).get("code").asText()).isEqualTo("SERVICES");
        Map<String, String> general = new HashMap<>();
        Map<String, String> generalIds = new HashMap<>();
        for (JsonNode r : s.get("generalSetup")) {
            String key = r.get("businessGroupId").asText() + "x" + r.get("productGroupId").asText();
            general.put(key, r.get("salesAccountCode").asText() + "/" + r.get("purchaseAccountCode").asText());
            generalIds.put(key, r.get("id").asText());
        }
        assertThat(general).hasSize(4)
                .containsEntry(business.get("MAROC") + "x" + product.get("GOODS"), "7111/6111")
                .containsEntry(business.get("MAROC") + "x" + product.get("SERVICES"), "7124/6125")
                .containsEntry(business.get("EXPORT") + "x" + product.get("GOODS"), "7121/6111")
                .containsEntry(business.get("EXPORT") + "x" + product.get("SERVICES"), "7127/6125");
        Map<String, JsonNode> vat = new HashMap<>();
        s.get("vatSetup").forEach(v -> vat.put(v.get("code").asText(), v));
        assertThat(vat).containsOnlyKeys("TVA20", "TVA14", "TVA10", "TVA7", "TVA0", "EXONERE", "EXPORT");
        assertThat(vat.get("TVA20").get("rate").decimalValue()).isEqualByComparingTo("20");
        assertThat(vat.get("TVA20").get("collectedAccountCode").asText()).isEqualTo("44551");
        assertThat(vat.get("TVA7").get("collectedAccountCode").asText()).isEqualTo("44554");
        assertThat(vat.get("TVA20").get("recoverableAccountCode").asText()).isEqualTo("34552");
        assertThat(vat.get("EXPORT").get("exempt").asBoolean()).isTrue();
        Map<String, String> bankCat = new HashMap<>();
        s.get("bankCategories").forEach(c -> bankCat.put(c.get("category").asText(), c.get("accountCode").asText()));
        assertThat(bankCat).hasSize(20).containsEntry("SALARIES", "6171").containsEntry("RENT", "6131")
                .containsEntry("BANK_FEES", "6147").containsEntry("TAXES", "6161").containsEntry("CASH", "5161")
                .containsEntry("TRANSFER", "5115").containsEntry("OTHER", "4497");
        assertThat(s.get("expenseCategories").size()).isGreaterThanOrEqualTo(13);
        assertThat(s.get("cashAccountCode").asText()).isEqualTo("5161");
        assertThat(s.get("startDate").asText()).isEqualTo(LocalDate.of(today().getYear(), 1, 1).toString());
        assertThat(s.get("bankAccounts")).isEmpty();

        // Partner groups: create, duplicates and bad sides refused, default moves, defaults and used groups stay.
        JsonNode after = json(post("/ledger/posting-setup/partner-groups"), token,
                "{\"code\": \"export\", \"name\": \"Clients export\", \"side\": \"CUSTOMER\", \"accountId\": \"" + acc.get("3421") + "\"}", 201);
        assertThat(after.get("partnerGroups")).hasSize(5);
        String exportGroup = null;
        for (JsonNode g : after.get("partnerGroups")) if (g.get("code").asText().equals("EXPORT")) exportGroup = g.get("id").asText();
        assertThat(exportGroup).isNotNull();
        detail(post("/ledger/posting-setup/partner-groups"), token,
                "{\"code\": \"EXPORT\", \"name\": \"Again\", \"side\": \"CUSTOMER\", \"accountId\": \"" + acc.get("3421") + "\"}", 400);
        assertThat(detail(post("/ledger/posting-setup/partner-groups"), token,
                "{\"code\": \"X\", \"name\": \"X\", \"side\": \"BOTH\", \"accountId\": \"" + acc.get("3421") + "\"}", 400))
                .contains("CUSTOMER or SUPPLIER");
        json(post("/ledger/posting-setup/partner-groups"), token, "{\"code\": \"X\", \"side\": \"CUSTOMER\"}", 400);
        json(post("/ledger/posting-setup/partner-groups"), token, "{\"code\": \"Y\", \"name\": \"Y\", \"side\": \"CUSTOMER\", \"accountId\": \""
                + UUID.randomUUID() + "\"}", 404);

        JsonNode moved = json(put("/ledger/posting-setup/partner-groups/" + exportGroup), token,
                "{\"name\": \"Export\", \"isDefault\": true}", 200);
        for (JsonNode g : moved.get("partnerGroups")) {
            if (g.get("side").asText().equals("CUSTOMER")) {
                assertThat(g.get("isDefault").asBoolean()).isEqualTo(g.get("id").asText().equals(exportGroup));
            }
        }
        assertThat(detail(put("/ledger/posting-setup/partner-groups/" + exportGroup), token, "{\"isDefault\": false}", 400))
                .contains("another group");
        assertThat(detail(delete("/ledger/posting-setup/partner-groups/" + exportGroup), token, null, 400)).contains("default");
        String local = groups.get("CUSTOMER/LOCAL").get("id").asText();
        json(put("/ledger/posting-setup/partner-groups/" + local), token, "{\"isDefault\": true}", 200);
        String supplierLocal = groups.get("SUPPLIER/LOCAL").get("id").asText();
        // A group of the other side, or another organization's, cannot be given to a partner.
        assertThat(detail(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Bad\", \"customerGroupId\": \""
                + supplierLocal + "\"}", 400)).contains("suppliers");
        String other = signUpAndLogin();
        String otherGroup = json(get("/ledger/posting-setup"), other, null, 200).get("partnerGroups").get(0).get("id").asText();
        detail(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Bad\", \"customerGroupId\": \"" + otherGroup + "\"}", 400);
        String assigned = partner(token, "CUSTOMER", "Atlas Export", "\"customerGroupId\": \"" + exportGroup
                + "\", \"businessGroupId\": \"" + business.get("EXPORT") + "\"");
        JsonNode p = json(get("/partners/" + assigned), token, null, 200);
        assertThat(p.get("customerGroupId").asText()).isEqualTo(exportGroup);
        assertThat(p.get("businessGroupId").asText()).isEqualTo(business.get("EXPORT"));
        assertThat(detail(delete("/ledger/posting-setup/partner-groups/" + exportGroup), token, null, 400)).contains("assigned");
        assertThat(detail(delete("/ledger/posting-setup/business-groups/" + business.get("EXPORT")), token, null, 400))
                .contains("assigned");
        // An update that leaves the groups out keeps them; an explicit null goes back to the default.
        json(patch("/partners/" + assigned), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas Export SA\"}", 200);
        assertThat(json(get("/partners/" + assigned), token, null, 200).get("customerGroupId").asText()).isEqualTo(exportGroup);
        json(patch("/partners/" + assigned), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas Export SA\", \"customerGroupId\": null}", 200);
        assertThat(json(get("/partners/" + assigned), token, null, 200).has("customerGroupId")).isFalse();
        JsonNode deleted = json(delete("/ledger/posting-setup/partner-groups/" + exportGroup), token, null, 200);
        assertThat(deleted.get("partnerGroups")).hasSize(4);

        // Business and product groups get their general-setup rows from the default group's.
        JsonNode withUe = json(post("/ledger/posting-setup/business-groups"), token, "{\"code\": \"UE\", \"name\": \"Union européenne\"}", 201);
        String ue = null;
        for (JsonNode g : withUe.get("businessGroups")) if (g.get("code").asText().equals("UE")) ue = g.get("id").asText();
        assertThat(withUe.get("generalSetup")).hasSize(6);
        for (JsonNode r : withUe.get("generalSetup")) {
            if (r.get("businessGroupId").asText().equals(ue)) {
                boolean goods = r.get("productGroupId").asText().equals(product.get("GOODS"));
                assertThat(r.get("salesAccountCode").asText()).isEqualTo(goods ? "7111" : "7124");
            }
        }
        detail(post("/ledger/posting-setup/business-groups"), token, "{\"code\": \"UE\", \"name\": \"Again\"}", 400);
        detail(post("/ledger/posting-setup/business-groups"), token, "{\"code\": \"bad code!\", \"name\": \"x\"}", 400);
        JsonNode withSoft = json(post("/ledger/posting-setup/product-groups"), token, "{\"code\": \"SOFT\", \"name\": \"Logiciels\"}", 201);
        assertThat(withSoft.get("generalSetup")).hasSize(9);
        String soft = null;
        for (JsonNode g : withSoft.get("productGroups")) if (g.get("code").asText().equals("SOFT")) soft = g.get("id").asText();
        String softExport = null;
        for (JsonNode r : withSoft.get("generalSetup")) {
            if (r.get("productGroupId").asText().equals(soft) && r.get("businessGroupId").asText().equals(business.get("EXPORT"))) {
                assertThat(r.get("salesAccountCode").asText()).isEqualTo("7127");
                softExport = r.get("id").asText();
            }
        }
        json(put("/ledger/posting-setup/product-groups/" + soft), token, "{\"name\": \"Software\"}", 200);
        String productId = json(post("/products"), token, "{\"sku\": \"SW-1\", \"name\": \"Licence\", \"unitPrice\": 100, \"productGroupId\": \""
                + soft + "\"}", 201).get("id").asText();
        assertThat(json(get("/products/" + productId), token, null, 200).get("productGroupId").asText()).isEqualTo(soft);
        assertThat(detail(delete("/ledger/posting-setup/product-groups/" + soft), token, null, 400)).contains("assigned");
        assertThat(detail(delete("/ledger/posting-setup/product-groups/" + product.get("SERVICES")), token, null, 400))
                .contains("default");
        detail(post("/products"), token, "{\"sku\": \"SW-2\", \"name\": \"Bad\", \"unitPrice\": 1, \"productGroupId\": \""
                + UUID.randomUUID() + "\"}", 400);
        JsonNode noUe = json(delete("/ledger/posting-setup/business-groups/" + ue), token, null, 200);
        assertThat(noUe.get("generalSetup")).hasSize(6);

        // Accounts on the setup rows: inactive accounts refused.
        json(post("/ledger/accounts"), token, "{\"code\": \"71271\", \"name\": \"Export logiciels\"}", 201);
        acc = accounts(token);
        JsonNode g = json(put("/ledger/posting-setup/general/" + softExport), token,
                "{\"salesAccountId\": \"" + acc.get("71271") + "\"}", 200);
        boolean found = false;
        for (JsonNode r : g.get("generalSetup")) {
            if (r.get("id").asText().equals(softExport)) {
                assertThat(r.get("salesAccountCode").asText()).isEqualTo("71271");
                assertThat(r.get("purchaseAccountCode").asText()).isEqualTo("6125");
                found = true;
            }
        }
        assertThat(found).isTrue();
        json(put("/ledger/accounts/" + acc.get("71271")), token, "{\"active\": false}", 200);
        assertThat(detail(put("/ledger/posting-setup/general/" + softExport), token,
                "{\"salesAccountId\": \"" + acc.get("71271") + "\"}", 400)).contains("inactive");
        detail(put("/ledger/posting-setup/general/" + softExport), token, "{}", 400);
        JsonNode v = json(put("/ledger/posting-setup/vat/" + vat.get("TVA14").get("id").asText()), token,
                "{\"collectedAccountId\": \"" + acc.get("4455") + "\"}", 200);
        for (JsonNode r : v.get("vatSetup")) {
            if (r.get("code").asText().equals("TVA14")) assertThat(r.get("collectedAccountCode").asText()).isEqualTo("4455");
        }
        JsonNode rent = json(put("/ledger/posting-setup/bank-categories/rent"), token, "{\"accountId\": \"" + acc.get("6136") + "\"}", 200);
        for (JsonNode c : rent.get("bankCategories")) {
            if (c.get("category").asText().equals("RENT")) assertThat(c.get("accountCode").asText()).isEqualTo("6136");
        }
        assertThat(detail(put("/ledger/posting-setup/bank-categories/NOPE"), token, "{\"accountId\": \"" + acc.get("6136") + "\"}", 400))
                .contains("Unknown bank category");
        json(put("/ledger/posting-setup/bank-categories/RENT"), token, "{}", 400);
        JsonNode exp = json(put("/ledger/posting-setup/expense-categories/PARKING"), token, "{\"accountId\": \"" + acc.get("6142") + "\"}", 200);
        assertThat(exp.toString()).contains("PARKING");
        json(put("/ledger/posting-setup/cash"), token, "{\"accountId\": \"" + acc.get("5161") + "\"}", 200);

        // Bank accounts get their own 5141 sub-account.
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"Attijari principal\", \"accountNumber\": \"" + RIB + "\"}", 201)
                .get("id").asText();
        JsonNode withBank = json(get("/ledger/posting-setup"), token, null, 200);
        assertThat(withBank.get("bankAccounts").get(0).get("bankAccountId").asText()).isEqualTo(bank);
        assertThat(withBank.get("bankAccounts").get(0).get("accountCode").asText()).isEqualTo("514101");
        assertThat(accounts(token)).containsKey("514101");
        json(put("/ledger/posting-setup/bank/" + UUID.randomUUID()), token, "{\"accountId\": \"" + acc.get("5141") + "\"}", 404);

        // The start date moves freely until something has posted.
        JsonNode moveStart = json(put("/ledger/posting-setup/start-date"), token, "{\"startDate\": \"" + today().minusDays(5) + "\"}", 200);
        assertThat(moveStart.get("startDate").asText()).isEqualTo(today().minusDays(5).toString());
        json(put("/ledger/posting-setup/start-date"), token, "{}", 400);
        String customer = partner(token, "CUSTOMER", "Client", null);
        json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-1", line(1, 100, "0.2", null)), 201);
        assertThat(detail(put("/ledger/posting-setup/start-date"), token, "{\"startDate\": \"" + today().minusDays(30) + "\"}", 400))
                .isEqualTo("Re-post the history to change the start date");

        // Reading needs LEDGER_READ, changing the setup LEDGER_WRITE.
        String sales = tokenForNewUser(orgIdOf(token), UserRole.SALESPERSON);
        mockMvc.perform(get("/ledger/posting-setup").header("Authorization", "Bearer " + sales))
                .andReturn().getResponse().getStatus();
        assertThat(mockMvc.perform(post("/ledger/posting-setup/business-groups").header("Authorization", "Bearer " + sales)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"Z\", \"name\": \"Z\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // Customer invoices

    @Test
    void customerInvoicePostsOnIssueAndEditsAndDeletesLeaveReversals() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas", null);
        String cc = json(post("/ledger/cost-centers"), token, "{\"code\": \"PRJ1\", \"name\": \"Projet 1\"}", 201).get("id").asText();
        String lines = line(2, 500, "0.2", null) + "," + line(1, 500, "0.1", "\"costCenterId\": \"" + cc + "\"");

        // A draft posts nothing.
        String id = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "DRAFT", today(), "FA-100", lines), 201)
                .get("id").asText();
        assertThat(live(token, "INVOICE", id)).isNull();
        assertThat(detail(get("/ledger/source/INVOICE/" + id), token, null, 404)).isNotBlank();

        // Sent: receivable incl. VAT on the partner, sales per line excl. VAT, VAT per rate.
        json(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-100", lines), 200);
        JsonNode e = live(token, "INVOICE", id);
        assertThat(e.get("journalCode").asText()).isEqualTo("VT");
        assertThat(e.get("entryDate").asText()).isEqualTo(today().toString());
        assertThat(e.get("sourceLabel").asText()).isEqualTo("FA-100");
        assertBalanced(e);
        assertThat(e.get("totalDebit").decimalValue()).isEqualByComparingTo("1750");
        Map<String, BigDecimal> m = byAccount(e);
        assertThat(m.get("3421")).isEqualByComparingTo("1750");
        assertThat(m.get("7124")).isEqualByComparingTo("-1500");
        assertThat(m.get("44551")).isEqualByComparingTo("-200");
        assertThat(m.get("44553")).isEqualByComparingTo("-50");
        boolean costCenterLine = false;
        for (JsonNode l : e.get("lines")) {
            if (l.get("accountCode").asText().equals("3421")) assertThat(l.get("partnerId").asText()).isEqualTo(customer);
            if (l.has("costCenterId")) {
                assertThat(l.get("costCenterId").asText()).isEqualTo(cc);
                assertThat(l.get("credit").decimalValue()).isEqualByComparingTo("500");
                costCenterLine = true;
            }
        }
        assertThat(costCenterLine).isTrue();
        String first = e.get("id").asText();

        // An edit that changes nothing in the books posts nothing new.
        String sameBooks = invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-100", lines).replace(
                "\"dueDate\": \"" + today().plusDays(30) + "\"", "\"dueDate\": \"" + today().plusDays(60) + "\"");
        json(patch("/invoices/" + id), token, sameBooks, 200);
        assertThat(live(token, "INVOICE", id).get("id").asText()).isEqualTo(first);

        // An amount edit reverses the entry and posts the new one.
        String newLines = line(3, 500, "0.2", null);
        json(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-100", newLines), 200);
        JsonNode second = live(token, "INVOICE", id);
        assertThat(second.get("id").asText()).isNotEqualTo(first);
        assertThat(byAccount(second).get("3421")).isEqualByComparingTo("1800");
        JsonNode firstNow = json(get("/ledger/entries/" + first), token, null, 200);
        assertThat(firstNow.has("reversedById")).isTrue();
        JsonNode reversal = json(get("/ledger/entries/" + firstNow.get("reversedById").asText()), token, null, 200);
        assertThat(reversal.get("sourceType").asText()).isEqualTo("REVERSAL");
        assertThat(byAccount(reversal).get("3421")).isEqualByComparingTo("-1750");
        assertThat(entriesOf(token, "INVOICE", id)).hasSize(2);
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("1800");

        // Automatic entries are corrected through their document, not reversed by hand.
        assertThat(detail(post("/ledger/entries/" + second.get("id").asText() + "/reverse"), token, "{}", 400))
                .contains("document");

        // Back to draft: reversal only.
        json(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "DRAFT", today(), "FA-100", newLines), 200);
        assertThat(live(token, "INVOICE", id)).isNull();
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");

        // Sent again, then deleted: reversal only; restored: posted again.
        json(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-100", newLines), 200);
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("1800");
        json(delete("/invoices/" + id), token, null, 204);
        assertThat(live(token, "INVOICE", id)).isNull();
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");
        json(post("/invoices/" + id + "/restore"), token, null, 200);
        assertThat(live(token, "INVOICE", id)).isNotNull();
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("1800");
        assertThat(entriesOf(token, "INVOICE", id)).hasSize(4);
        assertTrialBalanceBalances(token);

        // Another organization sees none of it.
        String other = signUpAndLogin();
        assertThat(live(other, "INVOICE", id)).isNull();
        assertThat(entriesOf(other, "INVOICE", id)).isEmpty();
    }

    @Test
    void salesAccountsFollowTheBusinessAndProductGroups() throws Exception {
        String token = signUpAndLogin();
        JsonNode s = json(get("/ledger/posting-setup"), token, null, 200);
        Map<String, String> business = new HashMap<>(), product = new HashMap<>();
        s.get("businessGroups").forEach(g -> business.put(g.get("code").asText(), g.get("id").asText()));
        s.get("productGroups").forEach(g -> product.put(g.get("code").asText(), g.get("id").asText()));
        String groupCustomer = null;
        for (JsonNode g : s.get("partnerGroups")) {
            if (g.get("side").asText().equals("CUSTOMER") && g.get("code").asText().equals("GROUP")) groupCustomer = g.get("id").asText();
        }
        String exporter = partner(token, "CUSTOMER", "Paris SARL", "\"businessGroupId\": \"" + business.get("EXPORT")
                + "\", \"customerGroupId\": \"" + groupCustomer + "\"");
        String goods = json(post("/products"), token, "{\"sku\": \"G-1\", \"name\": \"Chaise\", \"unitPrice\": 300, \"trackStock\": true}", 201)
                .get("id").asText();
        String service = json(post("/products"), token, "{\"sku\": \"S-1\", \"name\": \"Pose\", \"unitPrice\": 100}", 201)
                .get("id").asText();
        String lines = line(1, 300, "0", "\"productId\": \"" + goods + "\"") + ","
                + line(1, 100, "0", "\"productId\": \"" + service + "\"") + ","
                + line(1, 50, "0", "\"productId\": \"" + goods + "\", \"productGroupId\": \"" + product.get("SERVICES") + "\"");
        String id = json(post("/invoices"), token, invoiceBody("CUSTOMER", exporter, "SENT", today(), "EX-1", lines), 201)
                .get("id").asText();
        Map<String, BigDecimal> m = byAccount(live(token, "INVOICE", id));
        assertThat(m.get("3481")).isEqualByComparingTo("450");
        assertThat(m.get("7121")).isEqualByComparingTo("-300");
        assertThat(m.get("7127")).isEqualByComparingTo("-150");
        assertThat(m).doesNotContainKeys("3421", "4455", "44551");

        // An unknown product group on a line is refused, and nothing changes.
        String bad = line(1, 10, "0", "\"productGroupId\": \"" + UUID.randomUUID() + "\"");
        detail(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", exporter, "SENT", today(), "EX-1", bad), 400);
        assertThat(byAccount(live(token, "INVOICE", id)).get("3481")).isEqualByComparingTo("450");
    }

    // Supplier bills

    @Test
    void supplierBillsPostToPurchasesRecoverableVatAndPayables() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String vendor = partner(token, "VENDOR", "Maroc Hardware", null);
        String customer = partner(token, "CUSTOMER", "Client", null);
        String deal = json(post("/deals"), token, "{\"partnerId\": \"" + customer + "\", \"title\": \"Équipement\", \"stage\": \"OPEN\", \"amount\": 5000}", 201)
                .get("id").asText();
        JsonNode po = json(post("/purchase-orders"), token, """
                {"dealId": "%s", "vendorPartnerId": "%s", "status": "SENT",
                 "lines": [{"product": "Câbles", "qty": 2, "cost": 1000}, {"product": "Livres", "qty": 1, "cost": 500, "vatRate": 7}]}
                """.formatted(deal, vendor), 201);
        // Order lines carry their VAT rate (20 unless given), and the order's VAT follows them.
        assertThat(po.get("lines").get(0).get("vatRate").asInt()).isEqualTo(20);
        assertThat(po.get("tax").decimalValue()).isEqualByComparingTo("435");
        JsonNode bill = json(post("/invoices/from-purchase-order/" + po.get("id").asText()), token, "{\"supplierInvoiceNumber\": \"MH-9\"}", 201);
        assertThat(bill.get("total").decimalValue()).isEqualByComparingTo("2935");
        JsonNode e = live(token, "BILL", bill.get("id").asText());
        assertThat(e.get("journalCode").asText()).isEqualTo("AC");
        assertBalanced(e);
        Map<String, BigDecimal> m = byAccount(e);
        assertThat(m.get("6125")).isEqualByComparingTo("2500");
        assertThat(m.get("34552")).isEqualByComparingTo("435");
        assertThat(m.get("4411")).isEqualByComparingTo("-2935");
        assertThat(balance(orgId, "4411", vendor)).isEqualByComparingTo("-2935");

        // A manual bill, saved as sent, posts the same way; no customer-side entry.
        String manual = json(post("/invoices"), token, invoiceBody("VENDOR", vendor, "SENT", today(), "MH-10", line(1, 1000, "0.2", null)), 201)
                .get("id").asText();
        assertThat(live(token, "INVOICE", manual)).isNull();
        assertThat(byAccount(live(token, "BILL", manual)).get("4411")).isEqualByComparingTo("-1200");

        // Paying the supplier clears the payable.
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"BMCE\", \"accountNumber\": \"" + RIB + "\"}", 201).get("id").asText();
        JsonNode pay = json(post("/payments"), token, "{\"partnerId\": \"" + vendor + "\", \"invoiceId\": \"" + manual
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 1200, \"method\": \"BANK_TRANSFER\"}", 201);
        assertThat(pay.get("bankAccountId").asText()).isEqualTo(bank);
        assertThat(pay.get("cashRegister").asBoolean()).isFalse();
        Map<String, BigDecimal> pm = byAccount(live(token, "PAYMENT", pay.get("id").asText()));
        assertThat(pm.get("4411")).isEqualByComparingTo("1200");
        assertThat(pm.get("514101")).isEqualByComparingTo("-1200");
        assertThat(balance(orgId, "4411", vendor)).isEqualByComparingTo("-2935");
        assertTrialBalanceBalances(token);
    }

    // Payments

    @Test
    void paymentsPostOnTheirBankOrCashAndReceivablesMatchWhatCustomersOwe() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"CIH\", \"accountNumber\": \"" + RIB + "\"}", 201).get("id").asText();
        String a = partner(token, "CUSTOMER", "Alpha", null);
        String b = partner(token, "CUSTOMER", "Beta", null);
        String invA = json(post("/invoices"), token, invoiceBody("CUSTOMER", a, "SENT", today(), "A-1", line(1, 1000, "0.2", null)), 201)
                .get("id").asText();
        json(post("/invoices"), token, invoiceBody("CUSTOMER", b, "SENT", today(), "B-1", line(1, 500, "0", null)), 201);

        JsonNode p1 = json(post("/payments"), token, "{\"partnerId\": \"" + a + "\", \"invoiceId\": \"" + invA
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 500, \"method\": \"BANK_TRANSFER\"}", 201);
        JsonNode e1 = live(token, "PAYMENT", p1.get("id").asText());
        assertThat(e1.get("journalCode").asText()).isEqualTo("BQ");
        assertBalanced(e1);
        assertThat(byAccount(e1).get("514101")).isEqualByComparingTo("500");
        assertThat(byAccount(e1).get("3421")).isEqualByComparingTo("-500");
        assertThat(p1.get("bankAccountId").asText()).isEqualTo(bank);

        // Cash goes through the cash register and the CA journal.
        JsonNode p2 = json(post("/payments"), token, "{\"partnerId\": \"" + a + "\", \"invoiceId\": \"" + invA
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 100, \"method\": \"CASH\"}", 201);
        assertThat(p2.get("cashRegister").asBoolean()).isTrue();
        assertThat(p2.has("bankAccountId")).isFalse();
        JsonNode e2 = live(token, "PAYMENT", p2.get("id").asText());
        assertThat(e2.get("journalCode").asText()).isEqualTo("CA");
        assertThat(byAccount(e2).get("5161")).isEqualByComparingTo("100");

        // A payment without an invoice still credits the customer's receivable.
        JsonNode p3 = json(post("/payments"), token, "{\"partnerId\": \"" + b + "\", \"paymentDate\": \"" + today()
                + "\", \"amount\": 200, \"method\": \"CHECK\"}", 201);
        JsonNode e3 = live(token, "PAYMENT", p3.get("id").asText());
        for (JsonNode l : e3.get("lines")) {
            if (l.get("accountCode").asText().equals("3421")) assertThat(l.get("partnerId").asText()).isEqualTo(b);
        }

        // 3421 per partner = invoiced - paid.
        assertThat(balance(orgId, "3421", a)).isEqualByComparingTo("600");
        assertThat(balance(orgId, "3421", b)).isEqualByComparingTo("300");

        // Edit: reversal + new entry; delete: reversal only.
        json(patch("/payments/" + p1.get("id").asText()), token, "{\"partnerId\": \"" + a + "\", \"invoiceId\": \"" + invA
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 700, \"method\": \"BANK_TRANSFER\"}", 200);
        assertThat(entriesOf(token, "PAYMENT", p1.get("id").asText())).hasSize(2);
        assertThat(balance(orgId, "3421", a)).isEqualByComparingTo("400");
        // 700 by transfer and the 200 cheque, both through the main bank account.
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("900");
        json(delete("/payments/" + p3.get("id").asText()), token, null, 204);
        assertThat(live(token, "PAYMENT", p3.get("id").asText())).isNull();
        assertThat(balance(orgId, "3421", b)).isEqualByComparingTo("500");
        assertTrialBalanceBalances(token);

        JsonNode tb = json(get("/ledger/trial-balance"), token, null, 200);
        BigDecimal receivable = BigDecimal.ZERO;
        for (JsonNode r : tb.get("rows")) {
            if (r.get("code").asText().equals("3421")) {
                receivable = r.get("closingDebit").decimalValue().subtract(r.get("closingCredit").decimalValue());
            }
        }
        assertThat(receivable).isEqualByComparingTo("900");
    }

    // Expenses

    @Test
    void expensesPostToTheirCategoryWithRecoverableVat() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        json(post("/bank/accounts"), token, "{\"name\": \"BP\", \"accountNumber\": \"" + RIB + "\"}", 201);
        String cc = json(post("/ledger/cost-centers"), token, "{\"code\": \"SALON\", \"name\": \"Salon\"}", 201).get("id").asText();
        JsonNode ex = json(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"travel\", \"amount\": 1200,"
                + " \"vatAmount\": 200, \"paymentMethod\": \"CARD\", \"costCenterId\": \"" + cc + "\"}", 201);
        assertThat(ex.get("costCenterId").asText()).isEqualTo(cc);
        JsonNode e = live(token, "EXPENSE", ex.get("id").asText());
        assertThat(e.get("journalCode").asText()).isEqualTo("BQ");
        assertBalanced(e);
        Map<String, BigDecimal> m = byAccount(e);
        assertThat(m.get("6143")).isEqualByComparingTo("1000");
        assertThat(m.get("34552")).isEqualByComparingTo("200");
        assertThat(m.get("514101")).isEqualByComparingTo("-1200");

        JsonNode cash = json(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"Parking\", \"amount\": 20,"
                + " \"paymentMethod\": \"CASH\"}", 201);
        JsonNode ce = live(token, "EXPENSE", cash.get("id").asText());
        assertThat(ce.get("journalCode").asText()).isEqualTo("CA");
        assertThat(byAccount(ce).get("5161")).isEqualByComparingTo("-20");
        assertThat(byAccount(ce).get("6125")).isEqualByComparingTo("20");

        json(put("/expenses/" + ex.get("id").asText()), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"TRAVEL\", \"amount\": 600,"
                + " \"vatAmount\": 100, \"paymentMethod\": \"CARD\"}", 200);
        assertThat(entriesOf(token, "EXPENSE", ex.get("id").asText())).hasSize(2);
        assertThat(balance(orgId, "6143", null)).isEqualByComparingTo("500");
        json(delete("/expenses/" + ex.get("id").asText()), token, null, 204);
        assertThat(live(token, "EXPENSE", ex.get("id").asText())).isNull();
        assertThat(balance(orgId, "6143", null)).isEqualByComparingTo("0");
        detail(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"TRAVEL\", \"amount\": 10,"
                + " \"paymentMethod\": \"CASH\", \"costCenterId\": \"" + UUID.randomUUID() + "\"}", 400);
        assertTrialBalanceBalances(token);
    }

    // Bank lines

    private static byte[] statement(LocalDate day, String invoiceNumber) {
        String d = day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String text = """
                ATTIJARIWAFA BANK
                Relevé de compte;;;;;
                RIB : %1$s;;;;;
                Date opération;Date valeur;Libellé;Débit;Crédit;Solde
                ;;SOLDE INITIAL;;;10 000,00
                %2$s;%2$s;VIR RECU CLIENT ATLAS FACT %3$s;;1 000,00;11 000,00
                %2$s;%2$s;PRLV CNSS SEPTEMBRE;300,00;;10 700,00
                %2$s;%2$s;FRAIS TENUE DE COMPTE;50,00;;10 650,00
                %2$s;%2$s;PAIEMENT CARTE BUREAU PLUS;120,00;;10 530,00
                %2$s;%2$s;PRLV DGI TVA SEPTEMBRE;400,00;;10 130,00
                ;;SOLDE FINAL;;;10 130,00
                """.formatted(RIB, d, invoiceNumber);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void bankLinesPostOnceAndMatchedLinesDoNotDoublePost() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB + "\"}", 201)
                .get("id").asText();
        String customer = partner(token, "CUSTOMER", "Atlas", null);
        String inv = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "FA-2026-7781",
                line(1, 1000, "0", null)), 201).get("id").asText();
        JsonNode expense = json(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"OFFICE\", \"amount\": 120,"
                + " \"paymentMethod\": \"CASH\"}", 201);
        String expenseId = expense.get("id").asText();
        assertThat(live(token, "EXPENSE", expenseId).get("journalCode").asText()).isEqualTo("CA");

        String r = mockMvc.perform(multipart("/bank/accounts/" + bank + "/statements")
                        .file(new MockMultipartFile("file", "releve.csv", "text/csv", statement(today(), "FA-2026-7781")))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(r).get("autoMatched").asInt()).as(r).isEqualTo(1);
        Map<String, JsonNode> lines = new HashMap<>();
        for (JsonNode t : json(get("/bank/transactions?accountId=" + bank), token, null, 200).get("content")) {
            lines.put(t.get("description").asText(), t);
        }
        JsonNode received = lines.get("VIR RECU CLIENT ATLAS FACT FA-2026-7781");
        assertThat(received.get("status").asText()).isEqualTo("MATCHED");

        // The matched line posts nothing; its payment posts on the line's bank account.
        assertThat(live(token, "BANK_LINE", received.get("id").asText())).isNull();
        Map<String, BigDecimal> pay = byAccount(live(token, "PAYMENT", received.get("paymentId").asText()));
        assertThat(pay.get("514101")).isEqualByComparingTo("1000");
        assertThat(pay.get("3421")).isEqualByComparingTo("-1000");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");

        // Unmatched lines post against their category's account.
        Map<String, BigDecimal> cnss = byAccount(live(token, "BANK_LINE", lines.get("PRLV CNSS SEPTEMBRE").get("id").asText()));
        assertThat(cnss.get("6174")).isEqualByComparingTo("300");
        assertThat(cnss.get("514101")).isEqualByComparingTo("-300");
        assertThat(byAccount(live(token, "BANK_LINE", lines.get("FRAIS TENUE DE COMPTE").get("id").asText())).get("6147"))
                .isEqualByComparingTo("50");
        assertThat(byAccount(live(token, "BANK_LINE", lines.get("PRLV DGI TVA SEPTEMBRE").get("id").asText())).get("4456"))
                .isEqualByComparingTo("400");
        String carte = lines.get("PAIEMENT CARTE BUREAU PLUS").get("id").asText();
        JsonNode carteEntry = live(token, "BANK_LINE", carte);
        assertThat(byAccount(carteEntry).get("4497")).isEqualByComparingTo("120");

        // The bank account's ledger balance is exactly the statement's movements: nothing counted twice.
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("130");

        // A categorized line matched to an expense: its entry is reversed, the expense moves to the bank.
        json(post("/bank/transactions/" + carte + "/match"), token, "{\"kind\": \"EXPENSE\", \"id\": \"" + expenseId + "\"}", 200);
        assertThat(live(token, "BANK_LINE", carte)).isNull();
        assertThat(json(get("/ledger/entries/" + carteEntry.get("id").asText()), token, null, 200).has("reversedById")).isTrue();
        JsonNode moved = live(token, "EXPENSE", expenseId);
        assertThat(moved.get("journalCode").asText()).isEqualTo("BQ");
        assertThat(byAccount(moved).get("514101")).isEqualByComparingTo("-120");
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("130");
        assertThat(balance(orgId, "5161", null)).isEqualByComparingTo("0");
        assertThat(balance(orgId, "4497", null)).isEqualByComparingTo("0");

        // Unmatching puts both back.
        json(post("/bank/transactions/" + carte + "/unmatch"), token, null, 200);
        assertThat(live(token, "BANK_LINE", carte)).isNotNull();
        assertThat(live(token, "EXPENSE", expenseId).get("journalCode").asText()).isEqualTo("CA");
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("130");

        // Re-categorizing re-posts; setting a line aside unposts it.
        String fees = lines.get("FRAIS TENUE DE COMPTE").get("id").asText();
        json(put("/bank/transactions/" + fees + "/category"), token, "{\"category\": \"FEES\"}", 200);
        assertThat(byAccount(live(token, "BANK_LINE", fees)).get("6136")).isEqualByComparingTo("50");
        assertThat(balance(orgId, "6147", null)).isEqualByComparingTo("0");
        json(post("/bank/transactions/" + fees + "/ignore"), token, null, 200);
        assertThat(live(token, "BANK_LINE", fees)).isNull();
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("180");

        // Unmatching the invoice line removes the payment it created and posts the line itself.
        json(post("/bank/transactions/" + received.get("id").asText() + "/unmatch"), token, null, 200);
        assertThat(live(token, "PAYMENT", received.get("paymentId").asText())).isNull();
        assertThat(live(token, "BANK_LINE", received.get("id").asText())).isNotNull();
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("1000");
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("180");

        // Matched by hand to an existing cash payment: the payment moves to the line's bank.
        JsonNode cashPay = json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"invoiceId\": \"" + inv
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 1000, \"method\": \"CASH\"}", 201);
        assertThat(live(token, "PAYMENT", cashPay.get("id").asText()).get("journalCode").asText()).isEqualTo("CA");
        json(post("/bank/transactions/" + received.get("id").asText() + "/match"), token,
                "{\"kind\": \"PAYMENT\", \"id\": \"" + cashPay.get("id").asText() + "\"}", 200);
        JsonNode bankPay = live(token, "PAYMENT", cashPay.get("id").asText());
        assertThat(bankPay.get("journalCode").asText()).isEqualTo("BQ");
        assertThat(live(token, "BANK_LINE", received.get("id").asText())).isNull();
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("180");
        assertThat(balance(orgId, "5161", null)).isEqualByComparingTo("-120");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");
        assertTrialBalanceBalances(token);
    }

    // Start date, repost, purge, currency

    @Test
    void nothingPostsBeforeTheStartDateAndRepostIsIdempotent() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        LocalDate start = today().minusDays(10);
        json(put("/ledger/posting-setup/start-date"), token, "{\"startDate\": \"" + start + "\"}", 200);
        String customer = partner(token, "CUSTOMER", "Client", null);
        String old = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", start.minusDays(1), "OLD-1",
                line(1, 100, "0.2", null)), 201).get("id").asText();
        assertThat(live(token, "INVOICE", old)).isNull();
        String oldPay = json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"paymentDate\": \"" + start.minusDays(1)
                + "\", \"amount\": 50, \"method\": \"CASH\"}", 201).get("id").asText();
        assertThat(live(token, "PAYMENT", oldPay)).isNull();
        String onStart = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", start, "NEW-1",
                line(1, 100, "0.2", null)), 201).get("id").asText();
        assertThat(live(token, "INVOICE", onStart)).isNotNull();

        // Documents with no entry (here: moved past the start date behind the API's back) post on repost.
        jdbc.update("UPDATE invoice SET invoice_date = ? WHERE id = ?", start.plusDays(1), UUID.fromString(old));
        String broken = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", start.minusDays(2), "BAD-1",
                line(1, 100, "0.2", null)), 201).get("id").asText();
        jdbc.update("UPDATE invoice SET invoice_date = ?, lines = jsonb_set(lines, '{0,productGroupId}', to_jsonb(?::text)) WHERE id = ?",
                start.plusDays(1), UUID.randomUUID().toString(), UUID.fromString(broken));

        String manager = tokenForNewUser(orgId, UserRole.MANAGER);
        assertThat(mockMvc.perform(post("/ledger/repost").header("Authorization", "Bearer " + manager))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        JsonNode first = json(post("/ledger/repost"), token, null, 200);
        assertThat(first.get("posted").asInt()).isEqualTo(1);
        assertThat(first.get("skipped").asInt()).isEqualTo(1);
        assertThat(first.get("errors")).hasSize(1);
        assertThat(first.get("errors").get(0).get("sourceId").asText()).isEqualTo(broken);
        assertThat(first.get("errors").get(0).get("sourceType").asText()).isEqualTo("INVOICE");
        assertThat(first.get("errors").get(0).get("label").asText()).isEqualTo("BAD-1");
        assertThat(live(token, "INVOICE", old).get("entryDate").asText()).isEqualTo(start.plusDays(1).toString());
        assertThat(live(token, "PAYMENT", oldPay)).isNull();

        JsonNode second = json(post("/ledger/repost"), token, null, 200);
        assertThat(second.get("posted").asInt()).isEqualTo(0);
        assertThat(second.get("skipped").asInt()).isEqualTo(2);
        assertThat(second.get("errors")).hasSize(1);
        Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE organization_id = ?", Integer.class, orgId);
        json(post("/ledger/repost"), token, null, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE organization_id = ?", Integer.class, orgId))
                .isEqualTo(entries);
        assertTrialBalanceBalances(token);
    }

    @Test
    void postedDocumentsAreNotPurgedAndNewOrganizationsUseDirhams() throws Exception {
        String token = signUpAndLogin();
        String customer = partner(token, "CUSTOMER", "Client", null);
        String posted = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", today(), "P-1",
                line(1, 100, "0.2", null)), 201).get("id").asText();
        String draft = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "DRAFT", today(), "D-1",
                line(1, 100, "0.2", null)), 201).get("id").asText();
        String pay = json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"paymentDate\": \"" + today()
                + "\", \"amount\": 10, \"method\": \"CASH\"}", 201).get("id").asText();
        json(delete("/invoices/" + posted), token, null, 204);
        json(delete("/invoices/" + draft), token, null, 204);
        json(delete("/payments/" + pay), token, null, 204);
        json(delete("/partners/" + customer), token, null, 204);
        jdbc.update("UPDATE invoice SET deleted_at = now() - interval '90 days' WHERE id IN (?, ?)",
                UUID.fromString(posted), UUID.fromString(draft));
        jdbc.update("UPDATE payment SET deleted_at = now() - interval '90 days' WHERE id = ?", UUID.fromString(pay));
        jdbc.update("UPDATE partner SET deleted_at = now() - interval '90 days' WHERE id = ?", UUID.fromString(customer));
        purgeScheduler.purgeExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE id = ?", Integer.class, UUID.fromString(posted))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE id = ?", Integer.class, UUID.fromString(pay))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner WHERE id = ?", Integer.class, UUID.fromString(customer))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE id = ?", Integer.class, UUID.fromString(draft))).isEqualTo(0);

        String email = "mad-" + System.nanoTime() + "@example.com";
        json(post("/organizations"), token, """
                {"name": "Dirham Org", "admin_email": "%s", "admin_name": "Admin", "admin_password": "TestPassword123!"}
                """.formatted(email), 201);
        List<String> currency = jdbc.queryForList("SELECT o.default_currency FROM organization o JOIN app_user u"
                + " ON u.organization_id = o.id WHERE u.email = ?", String.class, email);
        assertThat(currency).containsExactly("MAD");
    }
}
