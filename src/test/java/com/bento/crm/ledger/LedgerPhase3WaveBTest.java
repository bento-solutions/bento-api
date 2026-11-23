package com.bento.crm.ledger;

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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * General ledger phase 3, wave B: lock date and year-end, VAT summary and settlement, stock
 * change, cut-off, fixed assets, cash register and recurring supplier bills.
 */
class LedgerPhase3WaveBTest extends IntegrationTestBase {

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

    /** A new organization whose ledger starts on January 1 of last year. */
    private String org() throws Exception {
        String token = signUpAndLogin();
        json(put("/ledger/posting-setup/start-date"), token,
                "{\"startDate\": \"" + (LocalDate.now().getYear() - 1) + "-01-01\"}", 200);
        return token;
    }

    private String partner(String token, String type, String name) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}", 201).get("id").asText();
    }

    private String invoice(String token, String type, String partnerId, LocalDate date, String number, int net) throws Exception {
        return json(post("/invoices"), token, """
                {"type": "%s", "partnerId": "%s", "status": "SENT", "invoiceDate": "%s", "invoiceNumber": "%s",
                 "dueDate": "%s", "lines": [{"description": "Item", "qty": 1, "unitPrice": %d}]}
                """.formatted(type, partnerId, date, number, date.plusDays(30), net), 201).get("id").asText();
    }

    private JsonNode live(String token, String type, String id) throws Exception {
        var res = mockMvc.perform(get("/ledger/source/" + type + "/" + id).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        if (res.getStatus() == 404) return null;
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(res.getContentAsString());
    }

    private JsonNode entry(String token, String id) throws Exception {
        return json(get("/ledger/entries/" + id), token, null, 200);
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

    // 5. Lock date

    private static byte[] statement(LocalDate day) {
        String d = day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        return """
                ATTIJARIWAFA BANK
                Relevé de compte;;;;;
                RIB : %1$s;;;;;
                Date opération;Date valeur;Libellé;Débit;Crédit;Solde
                ;;SOLDE INITIAL;;;10 000,00
                %2$s;%2$s;PRLV CNSS SEPTEMBRE;300,00;;9 700,00
                ;;SOLDE FINAL;;;9 700,00
                """.formatted(RIB, d).getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void theLockDateRefusesManualEntriesAndMovesAutomaticPostings() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        JsonNode settings = json(get("/ledger/settings"), token, null, 200);
        assertThat(settings.has("lockDate")).isFalse();
        assertThat(settings.get("vatRegime").asText()).isEqualTo("ENCAISSEMENT");
        assertThat(settings.get("vatPeriod").asText()).isEqualTo("MONTHLY");
        assertThat(settings.get("fiscalYearStartMonth").asInt()).isEqualTo(1);

        // A validated bank line of yesterday.
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB + "\"}", 201)
                .get("id").asText();
        mockMvc.perform(multipart("/bank/accounts/" + bank + "/statements")
                .file(new MockMultipartFile("file", "releve.csv", "text/csv", statement(yesterday)))
                .header("Authorization", "Bearer " + token)).andReturn();
        json(post("/bank/journal/validate"), token, "{\"bankAccountId\": \"" + bank + "\", \"upTo\": \"" + LocalDate.now() + "\"}", 200);
        String line = json(get("/bank/journal?bankAccountId=" + bank + "&status=VALIDATED"), token, null, 200)
                .get("content").get(0).get("id").asText();

        JsonNode locked = json(put("/ledger/settings"), token, "{\"lockDate\": \"" + yesterday + "\"}", 200);
        assertThat(locked.get("lockDate").asText()).isEqualTo(yesterday.toString());

        // A manual entry in the locked period: 400.
        JsonNode accounts = json(get("/ledger/accounts"), token, null, 200);
        Map<String, String> ids = new HashMap<>();
        accounts.forEach(a -> ids.put(a.get("code").asText(), a.get("id").asText()));
        String manual = """
                {"entryDate": "%s", "description": "Manual", "lines": [
                  {"accountId": "%s", "debit": 100}, {"accountId": "%s", "credit": 100}]}
                """;
        assertThat(detail(post("/ledger/entries"), token, manual.formatted(yesterday, ids.get("6125"), ids.get("5161")), 400))
                .isEqualTo("This period is locked");
        json(post("/ledger/entries"), token, manual.formatted(LocalDate.now(), ids.get("6125"), ids.get("5161")), 201);

        // Unvalidating a bank line of the locked period: 400.
        assertThat(detail(post("/bank/journal/" + line + "/unvalidate"), token, null, 400)).isEqualTo("This period is locked");

        // An invoice dated in the locked period posts on the day after the lock date.
        String customer = partner(token, "CUSTOMER", "Atlas");
        String inv = invoice(token, "CUSTOMER", customer, yesterday, "FA-LOCK-1", 1000);
        JsonNode e = live(token, "INVOICE", inv);
        assertThat(e.get("entryDate").asText()).isEqualTo(yesterday.plusDays(1).toString());
        assertThat(e.get("description").asText()).contains("(locked period, dated " + yesterday + ")");

        // The lock date can move back; each move is in the audit log.
        JsonNode unlocked = json(put("/ledger/settings"), token, "{\"lockDate\": null, \"vatRegime\": \"DEBIT\"}", 200);
        assertThat(unlocked.has("lockDate")).isFalse();
        assertThat(unlocked.get("vatRegime").asText()).isEqualTo("DEBIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_audit_log WHERE organization_id = ? AND action = 'LOCK_DATE'",
                Integer.class, orgId)).isEqualTo(2);
        json(post("/bank/journal/" + line + "/unvalidate"), token, null, 200);
        detail(put("/ledger/settings"), token, "{\"vatRegime\": \"NOPE\"}", 400);
        assertTrialBalanceBalances(token);
    }

    // 5. Year-end

    @Test
    void closingAYearPostsTheResultAndTheCarryForwardAndReopeningReversesThem() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        int last = LocalDate.now().getYear() - 1;
        String customer = partner(token, "CUSTOMER", "Atlas");
        invoice(token, "CUSTOMER", customer, LocalDate.of(last, 3, 1), "FA-Y-1", 3000);
        json(post("/expenses"), token, """
                {"expenseDate": "%d-06-15", "category": "OFFICE", "amount": 1200, "vatAmount": 200, "paymentMethod": "CASH",
                 "costCenterId": "%s"}
                """.formatted(last, costCenter(token)), 201);

        assertThat(detail(post("/ledger/years/" + (last + 1) + "/close"), token, null, 400)).contains("not ended");
        JsonNode closed = json(post("/ledger/years/" + last + "/close"), token, null, 200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        detail(post("/ledger/years/" + last + "/close"), token, null, 400);

        // Result: 3,000 of sales less 1,000 of charges, a profit of 2,000 in 1191.
        JsonNode result = entry(token, closed.get("resultEntryId").asText());
        assertThat(result.get("journalCode").asText()).isEqualTo("OD");
        assertThat(result.get("entryDate").asText()).isEqualTo(last + "-12-31");
        Map<String, BigDecimal> r = byAccount(result);
        assertThat(r.get("7124")).isEqualByComparingTo("3000");
        assertThat(r.get("6125")).isEqualByComparingTo("-1000");
        assertThat(r.get("1191")).isEqualByComparingTo("-2000");
        assertThat(balance(orgId, "7124")).isEqualByComparingTo("0");

        // Carry-forward on January 1 in AN.
        JsonNode opening = entry(token, closed.get("openingEntryId").asText());
        assertThat(opening.get("journalCode").asText()).isEqualTo("AN");
        assertThat(opening.get("entryDate").asText()).isEqualTo((last + 1) + "-01-01");
        Map<String, BigDecimal> o = byAccount(opening);
        assertThat(o.get("3421")).isEqualByComparingTo("3600");
        assertThat(o.get("1191")).isEqualByComparingTo("-2000");
        assertThat(o.get("5161")).isEqualByComparingTo("-1200");

        assertThat(json(get("/ledger/settings"), token, null, 200).get("lockDate").asText()).isEqualTo(last + "-12-31");
        boolean found = false;
        for (JsonNode y : json(get("/ledger/years"), token, null, 200)) {
            if (y.get("year").asInt() == last) {
                found = y.get("status").asText().equals("CLOSED") && y.has("openingEntryId");
            }
        }
        assertThat(found).isTrue();

        // No double counting: this year's trial balance holds the carry-forward once.
        JsonNode tb = json(get("/ledger/trial-balance?from=" + (last + 1) + "-01-01"), token, null, 200);
        Map<String, JsonNode> rows = new HashMap<>();
        tb.get("rows").forEach(x -> rows.put(x.get("code").asText(), x));
        assertThat(rows.get("3421").get("closingDebit").decimalValue()).isEqualByComparingTo("3600");
        assertThat(rows.get("3421").get("openingDebit").decimalValue()).isEqualByComparingTo("0");
        assertThat(rows.get("1191").get("closingCredit").decimalValue()).isEqualByComparingTo("2000");
        assertThat(rows).doesNotContainKey("7124");
        // A report across the close counts last year's movements, not the carry-forward too.
        JsonNode across = json(get("/ledger/trial-balance?from=" + last + "-01-01"), token, null, 200);
        across.get("rows").forEach(x -> rows.put(x.get("code").asText(), x));
        assertThat(rows.get("3421").get("closingDebit").decimalValue()).isEqualByComparingTo("3600");
        Map<String, String> accounts = new HashMap<>();
        json(get("/ledger/accounts"), token, null, 200).forEach(a -> accounts.put(a.get("code").asText(), a.get("balance").asText()));
        assertThat(new BigDecimal(accounts.get("3421"))).isEqualByComparingTo("3600");
        assertTrialBalanceBalances(token);

        // Locked: nothing can be posted in the closed year.
        String manual = """
                {"entryDate": "%d-12-31", "description": "Late", "lines": [
                  {"accountId": "%s", "debit": 100}, {"accountId": "%s", "credit": 100}]}
                """;
        Map<String, String> ids = new HashMap<>();
        json(get("/ledger/accounts"), token, null, 200).forEach(a -> ids.put(a.get("code").asText(), a.get("id").asText()));
        assertThat(detail(post("/ledger/entries"), token, manual.formatted(last, ids.get("6125"), ids.get("5161")), 400))
                .isEqualTo("This period is locked");

        // Reopen: both entries reversed, the lock date back to none.
        JsonNode reopened = json(post("/ledger/years/" + last + "/reopen"), token, null, 200);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(entry(token, closed.get("resultEntryId").asText()).has("reversedById")).isTrue();
        assertThat(entry(token, closed.get("openingEntryId").asText()).has("reversedById")).isTrue();
        assertThat(json(get("/ledger/settings"), token, null, 200).has("lockDate")).isFalse();
        assertThat(balance(orgId, "1191")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "7124")).isEqualByComparingTo("-3000");
        detail(post("/ledger/years/" + last + "/reopen"), token, null, 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_audit_log WHERE organization_id = ? AND action LIKE 'YEAR_%'",
                Integer.class, orgId)).isEqualTo(2);
        assertTrialBalanceBalances(token);
    }

    // 6. VAT

    @Test
    void theVatSummaryFollowsTheRegimeAndTheSettlementCarriesTheCredit() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        YearMonth now = YearMonth.now();
        YearMonth prev = now.minusMonths(1);
        String customer = partner(token, "CUSTOMER", "Atlas");

        // Last month: only an expense, 300 of recoverable VAT, so a credit of 300.
        json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "OFFICE", "amount": 1800, "vatAmount": 300, "paymentMethod": "CASH",
                 "costCenterId": "%s"}
                """.formatted(prev.atDay(15), costCenter(token)), 201);
        String prevQuery = "?from=" + prev.atDay(1) + "&to=" + prev.atEndOfMonth();
        JsonNode p = json(get("/ledger/vat" + prevQuery), token, null, 200);
        assertThat(p.get("regime").asText()).isEqualTo("ENCAISSEMENT");
        assertThat(p.get("totalRecoverable").decimalValue()).isEqualByComparingTo("300");
        assertThat(p.get("recoverable").get(0).get("vatCode").asText()).isEqualTo("TVA20");
        assertThat(p.get("recoverable").get(0).get("base").decimalValue()).isEqualByComparingTo("1500");
        assertThat(p.get("credit").decimalValue()).isEqualByComparingTo("300");
        assertThat(p.get("due").decimalValue()).isEqualByComparingTo("0");
        JsonNode ps = json(post("/ledger/vat/settle"), token,
                "{\"from\": \"" + prev.atDay(1) + "\", \"to\": \"" + prev.atEndOfMonth() + "\"}", 200);
        Map<String, BigDecimal> pe = byAccount(entry(token, ps.get("settlementEntryId").asText()));
        assertThat(pe.get("34558")).isEqualByComparingTo("300");
        assertThat(pe.get("34552")).isEqualByComparingTo("-300");

        // This month: an invoice of 1,000 + 200 VAT, half paid.
        String inv = invoice(token, "CUSTOMER", customer, now.atDay(1), "FA-VAT-1", 1000);
        json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"invoiceId\": \"" + inv + "\", \"paymentDate\": \""
                + now.atDay(1) + "\", \"amount\": 600, \"method\": \"BANK_TRANSFER\"}", 201);
        String query = "?from=" + now.atDay(1) + "&to=" + now.atEndOfMonth();
        JsonNode enc = json(get("/ledger/vat" + query), token, null, 200);
        assertThat(enc.get("collected")).hasSize(1);
        assertThat(enc.get("collected").get(0).get("vatCode").asText()).isEqualTo("TVA20");
        assertThat(enc.get("collected").get(0).get("rate").decimalValue()).isEqualByComparingTo("20");
        assertThat(enc.get("collected").get(0).get("base").decimalValue()).isEqualByComparingTo("500");
        assertThat(enc.get("collected").get(0).get("vat").decimalValue()).isEqualByComparingTo("100");
        assertThat(enc.get("creditCarriedIn").decimalValue()).isEqualByComparingTo("300");
        assertThat(enc.get("credit").decimalValue()).isEqualByComparingTo("200");

        // Under DEBIT the whole invoice VAT counts.
        json(put("/ledger/settings"), token, "{\"vatRegime\": \"DEBIT\"}", 200);
        JsonNode deb = json(get("/ledger/vat" + query), token, null, 200);
        assertThat(deb.get("regime").asText()).isEqualTo("DEBIT");
        assertThat(deb.get("collected").get(0).get("base").decimalValue()).isEqualByComparingTo("1000");
        assertThat(deb.get("totalCollected").decimalValue()).isEqualByComparingTo("200");
        assertThat(deb.get("due").decimalValue()).isEqualByComparingTo("0");
        assertThat(deb.get("credit").decimalValue()).isEqualByComparingTo("100");

        // Settlement: 44551 cleared, 200 of the credit used; posting it again changes nothing.
        String body = "{\"from\": \"" + now.atDay(1) + "\", \"to\": \"" + now.atEndOfMonth() + "\"}";
        JsonNode s1 = json(post("/ledger/vat/settle"), token, body, 200);
        JsonNode s2 = json(post("/ledger/vat/settle"), token, body, 200);
        assertThat(s2.get("settlementEntryId").asText()).isEqualTo(s1.get("settlementEntryId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE organization_id = ? AND source_type = 'VAT_SETTLEMENT'",
                Integer.class, orgId)).isEqualTo(2);
        Map<String, BigDecimal> se = byAccount(entry(token, s1.get("settlementEntryId").asText()));
        assertThat(se.get("44551")).isEqualByComparingTo("200");
        assertThat(se.get("34558")).isEqualByComparingTo("-200");
        assertThat(s1.get("creditCarriedIn").decimalValue()).isEqualByComparingTo("300");
        assertThat(balance(orgId, "34558")).isEqualByComparingTo("100");
        assertThat(balance(orgId, "44551")).isEqualByComparingTo("0");

        // Cancelling reverses it.
        JsonNode cancelled = json(delete("/ledger/vat/settle" + query), token, null, 200);
        assertThat(cancelled.has("settlementEntryId")).isFalse();
        assertThat(balance(orgId, "34558")).isEqualByComparingTo("300");
        assertTrialBalanceBalances(token);
    }

    @Test
    void aDueVatGoesTo4456AndMarkingItsTaxDeadlineDonePostsIt() throws Exception {
        String token = org();
        json(put("/ledger/settings"), token, "{\"vatRegime\": \"DEBIT\"}", 200);
        json(post("/tax-calendar/profiles"), token, "{\"vatRegime\": \"MONTHLY\"}", 201);
        String customer = partner(token, "CUSTOMER", "Atlas");
        YearMonth prev = YearMonth.now().minusMonths(1);
        invoice(token, "CUSTOMER", customer, prev.atDay(1), "FA-VAT-2", 1000);
        String query = "?from=" + prev.atDay(1) + "&to=" + prev.atEndOfMonth();
        JsonNode before = json(get("/ledger/vat" + query), token, null, 200);
        assertThat(before.get("due").decimalValue()).isEqualByComparingTo("200");
        assertThat(before.has("settlementEntryId")).isFalse();
        String deadline = before.get("taxDeadlineId").asText();
        json(put("/tax-calendar/deadlines/" + deadline), token, "{\"status\": \"DONE\"}", 200);
        JsonNode s = json(get("/ledger/vat" + query), token, null, 200);
        Map<String, BigDecimal> e = byAccount(entry(token, s.get("settlementEntryId").asText()));
        assertThat(e.get("4456")).isEqualByComparingTo("-200");
        assertThat(e.get("44551")).isEqualByComparingTo("200");
    }

    // 7. Stock change

    @Test
    void theStockChangePostsTheVariationUpAndDown() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        String product = json(post("/products"), token, """
                {"sku": "SKU-1", "name": "Widget", "unitPrice": 80, "costPrice": 50, "trackStock": true}
                """, 201).get("id").asText();
        json(post("/stock/receipts"), token, "{\"productId\": \"" + product + "\", \"quantity\": 10}", 201);
        LocalDate today = LocalDate.now();
        JsonNode preview = json(get("/ledger/stock-change?date=" + today), token, null, 200);
        assertThat(preview.get("opening").decimalValue()).isEqualByComparingTo("0");
        assertThat(preview.get("closing").decimalValue()).isEqualByComparingTo("500");
        assertThat(preview.get("change").decimalValue()).isEqualByComparingTo("500");
        assertThat(preview.has("entryId")).isFalse();

        JsonNode up = json(post("/ledger/stock-change"), token, "{\"date\": \"" + today + "\"}", 200);
        Map<String, BigDecimal> e = byAccount(entry(token, up.get("entryId").asText()));
        assertThat(e.get("3111")).isEqualByComparingTo("500");
        assertThat(e.get("6114")).isEqualByComparingTo("-500");
        JsonNode again = json(post("/ledger/stock-change"), token, "{\"date\": \"" + today + "\"}", 200);
        assertThat(again.get("entryId").asText()).isEqualTo(up.get("entryId").asText());

        // Four out: the next stock change is a decrease of 200.
        json(post("/stock/issues"), token, "{\"productId\": \"" + product + "\", \"quantity\": 4}", 201);
        LocalDate tomorrow = today.plusDays(1);
        JsonNode down = json(post("/ledger/stock-change"), token, "{\"date\": \"" + tomorrow + "\"}", 200);
        assertThat(down.get("opening").decimalValue()).isEqualByComparingTo("500");
        assertThat(down.get("closing").decimalValue()).isEqualByComparingTo("300");
        assertThat(down.get("change").decimalValue()).isEqualByComparingTo("-200");
        Map<String, BigDecimal> d = byAccount(entry(token, down.get("entryId").asText()));
        assertThat(d.get("3111")).isEqualByComparingTo("-200");
        assertThat(d.get("6114")).isEqualByComparingTo("200");
        assertThat(balance(orgId, "3111")).isEqualByComparingTo("300");

        json(delete("/ledger/stock-change?date=" + tomorrow), token, null, 200);
        assertThat(balance(orgId, "3111")).isEqualByComparingTo("500");
        assertThat(json(get("/ledger/stock-change?date=" + tomorrow), token, null, 200).has("entryId")).isFalse();
        assertTrialBalanceBalances(token);
    }

    // 8. Cut-off

    @Test
    void theCutoffPostsFnpAndFaeAndReversesThemTheNextDay() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        LocalDate today = LocalDate.now();
        String vendor = partner(token, "VENDOR", "Fournisseur");
        String customer = partner(token, "CUSTOMER", "Atlas");
        json(post("/deals"), token, """
                {"partnerId": "%s", "title": "Website", "stage": "CLOSED_WON", "amount": 4000, "orderDate": "%s"}
                """.formatted(customer, today), 201);
        String buyDeal = json(post("/deals"), token, """
                {"partnerId": "%s", "title": "Paper supply", "stage": "OPEN"}
                """.formatted(customer), 201).get("id").asText();
        json(post("/purchase-orders"), token, """
                {"dealId": "%s", "vendorPartnerId": "%s", "status": "DELIVERED", "orderNumber": "PO-1", "orderDate": "%s",
                 "deliveryDate": "%s", "lines": [{"description": "Paper", "qty": 2, "unitPrice": 500, "vatRate": 20}]}
                """.formatted(buyDeal, vendor, today, today), 201);

        JsonNode proposals = json(get("/ledger/cutoff/proposals?date=" + today), token, null, 200);
        assertThat(proposals.get("fnp")).hasSize(1);
        JsonNode fnp = proposals.get("fnp").get(0);
        assertThat(fnp.get("base").decimalValue()).isEqualByComparingTo("1000");
        assertThat(fnp.get("vat").decimalValue()).isEqualByComparingTo("200");
        assertThat(fnp.get("vatCode").asText()).isEqualTo("TVA20");
        assertThat(fnp.get("partnerId").asText()).isEqualTo(vendor);
        JsonNode fae = null;
        for (JsonNode f : proposals.get("fae")) if ("DEAL".equals(f.get("sourceType").asText())) fae = f;
        assertThat(fae).isNotNull();
        assertThat(fae.get("base").decimalValue()).isEqualByComparingTo("4000");
        assertThat(fae.get("vat").decimalValue()).isEqualByComparingTo("800");
        assertThat(fae.get("accountCode").asText()).isEqualTo("7124");
        assertThat(proposals.get("cca")).isEmpty();
        assertThat(proposals.get("pca")).isEmpty();

        String body = """
                {"date": "%s", "lines": [
                  {"type": "FNP", "accountId": "%s", "partnerId": "%s", "base": 1000, "vatCode": "TVA20", "label": "FNP PO-1"},
                  {"type": "FAE", "accountId": "%s", "partnerId": "%s", "base": 4000, "vatCode": "TVA20", "label": "FAE Website"}]}
                """.formatted(today, fnp.get("accountId").asText(), vendor, fae.get("accountId").asText(), customer);
        JsonNode cut = json(post("/ledger/cutoff"), token, body, 201);
        Map<String, BigDecimal> e = byAccount(cut.get("entry"));
        assertThat(cut.get("entry").get("entryDate").asText()).isEqualTo(today.toString());
        assertThat(e.get(fnp.get("accountCode").asText())).isEqualByComparingTo("1000");
        assertThat(e.get("3458")).isEqualByComparingTo("200");
        assertThat(e.get("4417")).isEqualByComparingTo("-1200");
        assertThat(e.get("3427")).isEqualByComparingTo("4800");
        assertThat(e.get("7124")).isEqualByComparingTo("-4000");
        assertThat(e.get("4458")).isEqualByComparingTo("-800");
        assertThat(cut.get("reversalDate").asText()).isEqualTo(today.plusDays(1).toString());
        Map<String, BigDecimal> rv = byAccount(cut.get("reversal"));
        assertThat(rv.get("4417")).isEqualByComparingTo("1200");
        assertThat(rv.get("3427")).isEqualByComparingTo("-4800");
        assertThat(cut.get("reversal").get("sourceType").asText()).isEqualTo("CUTOFF_REVERSAL");
        assertThat(balance(orgId, "4417")).isEqualByComparingTo("0");

        assertThat(detail(post("/ledger/cutoff"), token, body, 400)).contains("already posted");
        assertThat(json(get("/ledger/cutoff?date=" + today), token, null, 200).get("entryId").asText())
                .isEqualTo(cut.get("entryId").asText());

        JsonNode cancelled = json(delete("/ledger/cutoff?date=" + today), token, null, 200);
        assertThat(cancelled.has("entryId")).isFalse();
        assertThat(entry(token, cut.get("entryId").asText()).has("reversedById")).isTrue();
        assertThat(entry(token, cut.get("reversalEntryId").asText()).has("reversedById")).isTrue();
        assertThat(balance(orgId, "3427")).isEqualByComparingTo("0");
        json(post("/ledger/cutoff"), token, body, 201);
        assertTrialBalanceBalances(token);
    }

    // 9. Fixed assets

    @Test
    void fixedAssetsDepreciateLinearlyOrDecliningAndPostTheirDisposal() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        int last = LocalDate.now().getYear() - 1;
        JsonNode categories = json(get("/assets/categories"), token, null, 200);
        assertThat(categories).hasSize(7);
        assertThat(categories.toString()).contains("\"SOFTWARE\"", "\"2822\"", "\"28355\"");

        JsonNode linear = json(post("/assets"), token, """
                {"name": "Serveur", "category": "IT_EQUIPMENT", "acquisitionDate": "%d-01-01", "acquisitionCost": 60000,
                 "paidFrom": "BANK"}
                """.formatted(last), 201);
        String id = linear.get("id").asText();
        assertThat(linear.get("code").asText()).isEqualTo("IMM-0001");
        assertThat(linear.get("method").asText()).isEqualTo("LINEAR");
        assertThat(linear.get("usefulLifeMonths").asInt()).isEqualTo(60);
        assertThat(linear.get("assetAccountCode").asText()).isEqualTo("2355");
        Map<String, BigDecimal> acq = byAccount(live(token, "ASSET", id));
        assertThat(acq.get("2355")).isEqualByComparingTo("60000");
        assertThat(acq.get("5141")).isEqualByComparingTo("-60000");

        JsonNode schedule = json(get("/assets/" + id + "/schedule"), token, null, 200);
        assertThat(schedule).hasSize(60);
        assertThat(schedule.get(0).get("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(schedule.get(0).get("year").asInt()).isEqualTo(last);
        assertThat(schedule.get(0).get("month").asInt()).isEqualTo(1);
        assertThat(schedule.get(59).get("netBookValue").decimalValue()).isEqualByComparingTo("0");

        // Declining: 5 years, coefficient 2, so 40 % a year, then linear on the rest.
        JsonNode declining = json(post("/assets"), token, """
                {"name": "Camion", "category": "VEHICLES", "acquisitionDate": "%d-01-01", "acquisitionCost": 60000,
                 "method": "DECLINING", "paidFrom": "SUSPENSE"}
                """.formatted(last), 201);
        assertThat(declining.get("coefficient").decimalValue()).isEqualByComparingTo("2");
        JsonNode ds = json(get("/assets/" + declining.get("id").asText() + "/schedule"), token, null, 200);
        assertThat(ds).hasSize(60);
        assertThat(ds.get(0).get("amount").decimalValue()).isEqualByComparingTo("2000");
        assertThat(ds.get(12).get("amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(ds.get(24).get("amount").decimalValue()).isEqualByComparingTo("720");
        assertThat(ds.get(36).get("amount").decimalValue()).isEqualByComparingTo("540");
        assertThat(ds.get(59).get("cumulated").decimalValue()).isEqualByComparingTo("60000");
        detail(post("/assets"), token, """
                {"name": "Logiciel", "category": "SOFTWARE", "acquisitionDate": "%d-01-01", "acquisitionCost": 1000,
                 "method": "DECLINING", "usefulLifeMonths": 24}
                """.formatted(last), 400);

        // The run posts January to March, one entry per month; again, nothing.
        JsonNode run = json(post("/assets/depreciate"), token, "{\"upTo\": \"" + last + "-03-31\"}", 200);
        assertThat(run.get("months").asInt()).isEqualTo(6);
        assertThat(run.get("entries").asInt()).isEqualTo(3);
        assertThat(run.get("total").decimalValue()).isEqualByComparingTo("9000");
        JsonNode rerun = json(post("/assets/depreciate"), token, "{\"upTo\": \"" + last + "-03-31\"}", 200);
        assertThat(rerun.get("months").asInt()).isZero();
        assertThat(balance(orgId, "28355")).isEqualByComparingTo("-3000");
        assertThat(balance(orgId, "6193")).isEqualByComparingTo("9000");
        JsonNode got = json(get("/assets/" + id), token, null, 200);
        assertThat(got.get("accumulatedDepreciation").decimalValue()).isEqualByComparingTo("3000");
        assertThat(got.get("netBookValue").decimalValue()).isEqualByComparingTo("57000");
        assertThat(json(get("/assets/" + id + "/schedule"), token, null, 200).get(2).has("entryId")).isTrue();
        detail(put("/assets/" + id), token, """
                {"name": "Serveur", "category": "IT_EQUIPMENT", "acquisitionDate": "%d-01-01", "acquisitionCost": 50000}
                """.formatted(last), 400);
        detail(delete("/assets/" + id), token, null, 400);

        // Disposal in April for 50,000: April's depreciation first, then the disposal entry.
        JsonNode disposed = json(post("/assets/" + id + "/dispose"), token,
                "{\"date\": \"" + last + "-04-15\", \"price\": 50000}", 200);
        assertThat(disposed.get("status").asText()).isEqualTo("DISPOSED");
        assertThat(disposed.get("accumulatedDepreciation").decimalValue()).isEqualByComparingTo("4000");
        Map<String, BigDecimal> d = byAccount(entry(token, disposed.get("disposalEntryId").asText()));
        assertThat(d.get("28355")).isEqualByComparingTo("4000");
        assertThat(d.get("2355")).isEqualByComparingTo("-60000");
        assertThat(d.get("6513")).isEqualByComparingTo("56000");
        assertThat(d.get("5141")).isEqualByComparingTo("50000");
        assertThat(d.get("7513")).isEqualByComparingTo("-50000");
        assertThat(balance(orgId, "2355")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "28355")).isEqualByComparingTo("0");

        JsonNode register = json(get("/assets/register?year=" + last), token, null, 200);
        assertThat(register.get("assets")).hasSize(2);
        JsonNode row = null;
        for (JsonNode x : register.get("assets")) if (x.get("code").asText().equals("IMM-0001")) row = x;
        assertThat(row.get("depreciationYear").decimalValue()).isEqualByComparingTo("4000");
        assertThat(row.get("rate").decimalValue()).isEqualByComparingTo("20");
        assertThat(row.get("disposal").get("gainOrLoss").decimalValue()).isEqualByComparingTo("-6000");
        assertThat(register.get("categories")).hasSize(2);
        assertThat(register.get("totals").get("cost").decimalValue()).isEqualByComparingTo("120000");

        // From a supplier bill: no acquisition entry, the bill line moves to the asset account.
        String vendor = partner(token, "VENDOR", "Dell");
        String bill = invoice(token, "VENDOR", vendor, LocalDate.of(last, 5, 2), "DELL-1", 10000);
        assertThat(byAccount(live(token, "BILL", bill)).get("6125")).isEqualByComparingTo("10000");
        JsonNode fromBill = json(post("/assets"), token, """
                {"name": "Laptops", "category": "IT_EQUIPMENT", "acquisitionDate": "%d-05-02", "acquisitionCost": 10000,
                 "supplierInvoiceId": "%s", "supplierInvoiceLineIndex": 0}
                """.formatted(last, bill), 201);
        assertThat(fromBill.get("paidFrom").asText()).isEqualTo("SUPPLIER_BILL");
        assertThat(live(token, "ASSET", fromBill.get("id").asText())).isNull();
        Map<String, BigDecimal> b = byAccount(live(token, "BILL", bill));
        assertThat(b.get("2355")).isEqualByComparingTo("10000");
        assertThat(b).doesNotContainKey("6125");
        json(delete("/assets/" + fromBill.get("id").asText()), token, null, 204);
        assertThat(byAccount(live(token, "BILL", bill)).get("6125")).isEqualByComparingTo("10000");
        assertTrialBalanceBalances(token);
    }

    // 10. Cash register and recurring supplier bills

    @Test
    void cashMovementsPostInTheCashJournalAndCanBeReversed() throws Exception {
        String token = org();
        LocalDate today = LocalDate.now();
        JsonNode in = json(post("/cash/movements"), token, """
                {"date": "%s", "kind": "IN", "amount": 1000, "label": "Vente comptoir", "category": "SALES"}
                """.formatted(today), 201);
        assertThat(in.get("kind").asText()).isEqualTo("IN");
        JsonNode e = entry(token, in.get("entryId").asText());
        assertThat(e.get("journalCode").asText()).isEqualTo("CA");
        assertThat(e.get("sourceType").asText()).isEqualTo("CASH");
        assertThat(byAccount(e).get("5161")).isEqualByComparingTo("1000");
        assertThat(byAccount(e).get("4497")).isEqualByComparingTo("-1000");
        JsonNode out = json(post("/cash/movements"), token, """
                {"date": "%s", "kind": "OUT", "amount": 200, "label": "Dépôt en banque", "category": "TRANSFER"}
                """.formatted(today), 201);
        assertThat(byAccount(entry(token, out.get("entryId").asText())).get("5115")).isEqualByComparingTo("200");

        JsonNode register = json(get("/cash/register"), token, null, 200);
        assertThat(register.get("balance").decimalValue()).isEqualByComparingTo("800");
        assertThat(register.get("accountCode").asText()).isEqualTo("5161");
        JsonNode movements = json(get("/cash/movements"), token, null, 200);
        assertThat(movements.get("total_elements").asInt()).isEqualTo(2);
        assertThat(movements.get("content").toString()).contains(in.get("id").asText());

        json(delete("/cash/movements/" + in.get("id").asText()), token, null, 204);
        assertThat(entry(token, in.get("entryId").asText()).has("reversedById")).isTrue();
        assertThat(json(get("/cash/register"), token, null, 200).get("balance").decimalValue()).isEqualByComparingTo("-200");
        assertThat(json(get("/cash/movements"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        json(delete("/cash/movements/" + in.get("id").asText()), token, null, 404);
        detail(post("/cash/movements"), token, """
                {"date": "%s", "kind": "SIDEWAYS", "amount": 1, "label": "x"}
                """.formatted(today), 400);
        assertTrialBalanceBalances(token);
    }

    @Test
    void aVendorRecurringTemplateGeneratesABillThatPostsInAc() throws Exception {
        String token = org();
        String vendor = partner(token, "VENDOR", "Loueur");
        JsonNode template = json(post("/recurring-invoices"), token, """
                {"partnerId": "%s", "type": "VENDOR", "name": "Loyer", "autoSend": false, "frequency": "MONTHLY", "startDate": "%s",
                 "lines": [{"description": "Loyer", "qty": 1, "unitPrice": 500, "vatCode": "TVA20"}]}
                """.formatted(vendor, LocalDate.now()), 201);
        assertThat(template.get("type").asText()).isEqualTo("VENDOR");
        assertThat(json(get("/recurring-invoices?type=VENDOR"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);
        assertThat(json(get("/recurring-invoices?type=CUSTOMER"), token, null, 200).get("total_elements").asInt()).isZero();

        JsonNode bill = json(post("/recurring-invoices/" + template.get("id").asText() + "/generate"), token, null, 201);
        assertThat(bill.get("type").asText()).isEqualTo("VENDOR");
        assertThat(bill.get("status").asText()).isEqualTo("SENT");
        JsonNode e = live(token, "BILL", bill.get("id").asText());
        assertThat(e.get("journalCode").asText()).isEqualTo("AC");
        Map<String, BigDecimal> m = byAccount(e);
        assertThat(m.get("4411")).isEqualByComparingTo("-600");
        assertThat(m.get("6125")).isEqualByComparingTo("500");
        assertThat(m.get("34552")).isEqualByComparingTo("100");
    }
}
