package com.bento.crm.ledger;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** General ledger phase 4: bilan and CPC, partner statements and balances, the accountant export. */
class LedgerPhase4Test extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private final int last = LocalDate.now().getYear() - 1;

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private MockHttpServletResponse raw(String url, String token) throws Exception {
        var res = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return res;
    }

    /** A new organization whose ledger starts on January 1 of last year. */
    private String org() throws Exception {
        String token = signUpAndLogin();
        json(put("/ledger/posting-setup/start-date"), token, "{\"startDate\": \"" + last + "-01-01\"}", 200);
        return token;
    }

    private String partner(String token, String type, String name) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}", 201).get("id").asText();
    }

    private String invoice(String token, String type, String partnerId, LocalDate date, LocalDate due, String number,
                           int net) throws Exception {
        return json(post("/invoices"), token, """
                {"type": "%s", "partnerId": "%s", "status": "SENT", "invoiceDate": "%s", "invoiceNumber": "%s",
                 "dueDate": "%s", "lines": [{"description": "Item", "qty": 1, "unitPrice": %d}]}
                """.formatted(type, partnerId, date, number, due, net), 201).get("id").asText();
    }

    private void pay(String token, String partnerId, String invoiceId, LocalDate date, int amount, String method)
            throws Exception {
        json(post("/payments"), token, "{\"partnerId\": \"" + partnerId + "\", \"invoiceId\": \"" + invoiceId
                + "\", \"paymentDate\": \"" + date + "\", \"amount\": " + amount + ", \"method\": \"" + method + "\"}", 201);
    }

    /** A sent advance invoice of {@code net} before VAT at TVA20. */
    private String advance(String token, String partnerId, int net, LocalDate date) throws Exception {
        JsonNode adv = json(post("/invoices/advance"), token, "{\"partnerId\": \"" + partnerId + "\", \"amount\": " + net
                + ", \"vatCode\": \"TVA20\"}", 201);
        ObjectNode send = (ObjectNode) adv.deepCopy();
        send.put("status", "SENT");
        send.put("invoiceDate", date.toString());
        send.put("dueDate", date.plusDays(15).toString());
        json(patch("/invoices/" + adv.get("id").asText()), token, send.toString(), 200);
        return adv.get("id").asText();
    }

    private static Map<String, JsonNode> rows(JsonNode sections) {
        Map<String, JsonNode> m = new HashMap<>();
        for (JsonNode s : sections) for (JsonNode r : s.get("rows")) m.put(r.get("code").asText(), r);
        return m;
    }

    private static JsonNode section(JsonNode sections, String key) {
        for (JsonNode s : sections) if (s.get("key").asText().equals(key)) return s;
        return null;
    }

    private void assertTrialBalanceBalances(String token, String query) throws Exception {
        JsonNode totals = json(get("/ledger/trial-balance" + query), token, null, 200).get("totals");
        assertThat(totals.get("closingDebit").decimalValue()).isEqualByComparingTo(totals.get("closingCredit").decimalValue());
    }

    // 1. Bilan and CPC

    @Test
    void theBilanBalancesAndItsResultIsTheCpcResult() throws Exception {
        String token = org();
        String customer = partner(token, "CUSTOMER", "Atlas");
        String vendor = partner(token, "VENDOR", "Fournisseur Sud");
        // An invoice of 10,000 (12,000 TTC), half paid; a bill of 4,000 (4,800 TTC), paid.
        String inv = invoice(token, "CUSTOMER", customer, LocalDate.of(last, 3, 1), LocalDate.of(last, 3, 31), "FA-B-1", 10000);
        pay(token, customer, inv, LocalDate.of(last, 4, 10), 6000, "CHECK");
        String bill = invoice(token, "VENDOR", vendor, LocalDate.of(last, 2, 1), LocalDate.of(last, 3, 1), "FF-B-1", 4000);
        pay(token, vendor, bill, LocalDate.of(last, 4, 12), 4800, "BANK_TRANSFER");
        // A server of 60,000 paid from the bank (an overdraft), depreciated over the year: 12,000.
        json(post("/assets"), token, """
                {"name": "Serveur", "category": "IT_EQUIPMENT", "acquisitionDate": "%d-01-01", "acquisitionCost": 60000,
                 "paidFrom": "BANK"}
                """.formatted(last), 201);
        json(post("/assets/depreciate"), token, "{\"upTo\": \"" + last + "-12-31\"}", 200);
        // An advance invoice of 5,000 (6,000 TTC), paid.
        String adv = advance(token, customer, 5000, LocalDate.of(last, 5, 2));
        pay(token, customer, adv, LocalDate.of(last, 5, 20), 6000, "BANK_TRANSFER");
        // The April VAT settlement.
        json(post("/ledger/vat/settle"), token, "{\"from\": \"" + last + "-04-01\", \"to\": \"" + last + "-04-30\"}", 200);
        assertTrialBalanceBalances(token, "?from=" + last + "-01-01&to=" + last + "-12-31");

        JsonNode bilan = json(get("/ledger/statements/bilan?year=" + last + "&comparative=true"), token, null, 200);
        assertThat(bilan.get("asOf").asText()).isEqualTo(last + "-12-31");
        assertThat(bilan.get("priorAsOf").asText()).isEqualTo((last - 1) + "-12-31");
        assertThat(bilan.get("balanced").asBoolean()).isTrue();
        assertThat(bilan.get("totalActif").decimalValue()).isEqualByComparingTo(bilan.get("totalPassif").decimalValue());
        assertThat(bilan.get("totalActifPrior").decimalValue()).isEqualByComparingTo("0");
        assertThat(bilan.get("closed").asBoolean()).isFalse();

        // Net fixed assets = gross - depreciation.
        Map<String, JsonNode> actif = rows(bilan.get("actif"));
        assertThat(actif.get("235").get("gross").decimalValue()).isEqualByComparingTo("60000");
        assertThat(actif.get("235").get("amortProv").decimalValue()).isEqualByComparingTo("12000");
        assertThat(actif.get("235").get("net").decimalValue()).isEqualByComparingTo("48000");
        JsonNode fixed = section(bilan.get("actif"), "A3");
        assertThat(fixed.get("totalNet").decimalValue()).isEqualByComparingTo("48000");
        // The customer still owes 6,000.
        assertThat(actif.get("342").get("net").decimalValue()).isEqualByComparingTo("6000");
        // The advance received is a debt.
        Map<String, JsonNode> passif = rows(bilan.get("passif"));
        assertThat(passif.get("442").get("net").decimalValue()).isEqualByComparingTo("5000");

        // The bank in overdraft stays on the actif, negative, with a warning.
        assertThat(actif.get("514").get("net").decimalValue()).isNegative();
        List<String> warned = new ArrayList<>();
        bilan.get("warnings").forEach(w -> warned.add(w.get("accountCode").asText()));
        assertThat(warned).contains("5141");

        // Result: 10,000 of sales less 4,000 of purchases and 12,000 of depreciation.
        JsonNode cpc = json(get("/ledger/statements/cpc?year=" + last + "&comparative=true"), token, null, 200);
        assertThat(cpc.get("resultatNet").decimalValue()).isEqualByComparingTo("-6000");
        assertThat(bilan.get("resultatNet").decimalValue()).isEqualByComparingTo(cpc.get("resultatNet").decimalValue());
        assertThat(passif.get("119").get("net").decimalValue()).isEqualByComparingTo("-6000");
        assertThat(section(cpc.get("sections"), "I").get("total").decimalValue()).isEqualByComparingTo("10000");
        assertThat(section(cpc.get("sections"), "II").get("total").decimalValue()).isEqualByComparingTo("16000");
        assertThat(section(cpc.get("sections"), "III").get("total").decimalValue()).isEqualByComparingTo("-6000");
        assertThat(cpc.get("resultatNetPrior").decimalValue()).isEqualByComparingTo("0");
        Map<String, JsonNode> cpcRows = rows(cpc.get("sections"));
        assertThat(cpcRows.get("619").get("current").decimalValue()).isEqualByComparingTo("12000");
        assertThat(cpcRows.get("619").get("label").asText()).isEqualTo("Dotations d'exploitation");

        // A quarter.
        JsonNode q = json(get("/ledger/statements/cpc?from=" + last + "-01-01&to=" + last + "-03-31"), token, null, 200);
        assertThat(section(q.get("sections"), "I").get("total").decimalValue()).isEqualByComparingTo("10000");

        // The Excel files.
        for (String url : List.of("/ledger/statements/bilan.xlsx?year=" + last + "&comparative=true",
                "/ledger/statements/cpc.xlsx?year=" + last)) {
            MockHttpServletResponse res = raw(url, token);
            assertThat(res.getContentType()).startsWith("application/vnd.openxmlformats");
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getContentAsByteArray()))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(1);
                assertThat(wb.getSheetAt(0).getLastRowNum()).isGreaterThan(5);
            }
        }
    }

    @Test
    void aClosedYearKeepsItsResultInTheCpcAndTheBilanStaysBalanced() throws Exception {
        String token = org();
        String customer = partner(token, "CUSTOMER", "Atlas");
        invoice(token, "CUSTOMER", customer, LocalDate.of(last, 3, 1), LocalDate.of(last, 3, 31), "FA-Y-1", 3000);
        json(post("/expenses"), token, """
                {"expenseDate": "%d-06-15", "category": "OFFICE", "amount": 1200, "vatAmount": 200, "paymentMethod": "CASH"}
                """.formatted(last), 201);
        json(post("/ledger/years/" + last + "/close"), token, null, 200);

        JsonNode cpc = json(get("/ledger/statements/cpc?year=" + last), token, null, 200);
        assertThat(cpc.get("resultatNet").decimalValue()).isEqualByComparingTo("2000");
        JsonNode bilan = json(get("/ledger/statements/bilan?year=" + last), token, null, 200);
        assertThat(bilan.get("closed").asBoolean()).isTrue();
        assertThat(bilan.get("balanced").asBoolean()).isTrue();
        assertThat(bilan.get("resultatNet").decimalValue()).isEqualByComparingTo("2000");
        assertThat(rows(bilan.get("passif")).get("119").get("net").decimalValue()).isEqualByComparingTo("2000");
        // Nothing counted twice: 3,600 receivable, 1,200 cash out, the VAT, and a result of 2,000.
        assertThat(rows(bilan.get("actif")).get("342").get("net").decimalValue()).isEqualByComparingTo("3600");

        // This year: the carried result waits for its allocation (118); last year is the comparative.
        JsonNode now = json(get("/ledger/statements/bilan?comparative=true"), token, null, 200);
        assertThat(now.get("balanced").asBoolean()).isTrue();
        Map<String, JsonNode> passif = rows(now.get("passif"));
        assertThat(passif.get("118").get("net").decimalValue()).isEqualByComparingTo("2000");
        assertThat(passif.get("119").get("net").decimalValue()).isEqualByComparingTo("0");
        assertThat(passif.get("119").get("netPrior").decimalValue()).isEqualByComparingTo("2000");
        assertThat(now.get("totalActifPrior").decimalValue()).isEqualByComparingTo(bilan.get("totalActif").decimalValue());
        JsonNode cpcNow = json(get("/ledger/statements/cpc?comparative=true"), token, null, 200);
        assertThat(cpcNow.get("resultatNetPrior").decimalValue()).isEqualByComparingTo("2000");
    }

    // 2. Partner statements and balances

    @Test
    void aCustomerStatementAddsUpAndShowsItsAdvancesAndOpenInvoices() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas");
        LocalDate today = LocalDate.now();
        String old = invoice(token, "CUSTOMER", customer, LocalDate.of(last, 6, 1), LocalDate.of(last, 7, 1), "FA-S-1", 1000);
        pay(token, customer, old, LocalDate.of(last, 6, 20), 1200, "CHECK");
        // 10,000 TTC due 15 days ago, 2,000 paid: 10,000 due, 1-30 days late.
        String inv = invoice(token, "CUSTOMER", customer, today.minusDays(45), today.minusDays(15), "FA-S-2", 10000);
        pay(token, customer, inv, today.minusDays(10), 2000, "CHECK");
        String adv = advance(token, customer, 1000, today.minusDays(5));
        pay(token, customer, adv, today.minusDays(3), 1200, "BANK_TRANSFER");

        String query = "?type=CUSTOMER&from=" + today.getYear() + "-01-01&to=" + today;
        JsonNode st = json(get("/ledger/partners/" + customer + "/statement" + query), token, null, 200);
        assertThat(st.get("partner").get("name").asText()).isEqualTo("Atlas");
        BigDecimal running = st.get("opening").decimalValue();
        for (JsonNode l : st.get("lines")) {
            running = running.add(l.get("debit").decimalValue()).subtract(l.get("credit").decimalValue());
            assertThat(l.get("balance").decimalValue()).isEqualByComparingTo(running);
        }
        assertThat(st.get("closing").decimalValue()).isEqualByComparingTo(running);
        assertThat(st.get("closing").decimalValue()).isEqualByComparingTo("10000");
        boolean documented = false;
        for (JsonNode l : st.get("lines")) {
            if ("FA-S-2".equals(l.path("documentNumber").asText())) documented = true;
        }
        assertThat(documented).isTrue();

        // The advance: 1,000 received before VAT, in its own block.
        assertThat(st.get("advances").get("closing").decimalValue()).isEqualByComparingTo("1000");
        assertThat(st.get("advances").get("lines").size()).isGreaterThan(0);

        // The partly paid invoice is open, 15 days late.
        JsonNode docs = st.get("openDocuments");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("number").asText()).isEqualTo("FA-S-2");
        assertThat(docs.get(0).get("due").decimalValue()).isEqualByComparingTo("10000");
        assertThat(docs.get(0).get("daysOverdue").asInt()).isEqualTo(15);
        assertThat(docs.get(0).get("bucket").asText()).isEqualTo("d30");
        assertThat(st.get("aging").get("d30").decimalValue()).isEqualByComparingTo("10000");

        // Across years: the opening is last year's balance (0 here), the lines add up the same way.
        JsonNode both = json(get("/ledger/partners/" + customer + "/statement?from=" + last + "-01-01&to=" + today), token, null, 200);
        assertThat(both.get("type").asText()).isEqualTo("CUSTOMER");
        assertThat(both.get("closing").decimalValue()).isEqualByComparingTo("10000");

        // The balances list agrees.
        JsonNode list = json(get("/ledger/partners/balances" + query + "&onlyOpen=true"), token, null, 200);
        assertThat(list.get("total_elements").asInt()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("partnerName").asText()).isEqualTo("Atlas");
        assertThat(item.get("closing").decimalValue()).isEqualByComparingTo("10000");
        assertThat(item.get("advances").decimalValue()).isEqualByComparingTo("1000");
        assertThat(item.get("overdue").decimalValue()).isEqualByComparingTo("10000");
        assertThat(item.get("aging").get("d30").decimalValue()).isEqualByComparingTo("10000");
        assertThat(list.get("totals").get("closing").decimalValue()).isEqualByComparingTo("10000");
        assertThat(json(get("/ledger/partners/balances" + query + "&search=zzz"), token, null, 200)
                .get("total_elements").asInt()).isZero();

        // The PDF and the Excel list.
        MockHttpServletResponse pdf = raw("/ledger/partners/" + customer + "/statement.pdf" + query, token);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        MockHttpServletResponse xlsx = raw("/ledger/partners/balances.xlsx" + query, token);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("Atlas");
        }
        assertThat(orgId).isNotNull();
    }

    @Test
    void supplierBalancesMatchThePayableLines() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        String a = partner(token, "VENDOR", "Alpha");
        String b = partner(token, "VENDOR", "Beta");
        LocalDate today = LocalDate.now();
        String bill1 = invoice(token, "VENDOR", a, today.minusDays(20), today.plusDays(10), "FF-1", 1000);
        invoice(token, "VENDOR", a, today.minusDays(10), today.plusDays(20), "FF-2", 500);
        invoice(token, "VENDOR", b, today.minusDays(100), today.minusDays(95), "FF-3", 2000);
        pay(token, a, bill1, today.minusDays(1), 1200, "BANK_TRANSFER");

        String query = "?type=SUPPLIER&from=" + last + "-01-01&to=" + today;
        JsonNode list = json(get("/ledger/partners/balances" + query), token, null, 200);
        BigDecimal payable = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.credit - l.debit), 0) FROM ledger_entry_line l
                JOIN ledger_account ac ON ac.id = l.account_id
                WHERE l.organization_id = ? AND ac.code LIKE '441%' AND l.partner_id IS NOT NULL AND l.deleted_at IS NULL
                """, BigDecimal.class, orgId);
        assertThat(list.get("totals").get("closing").decimalValue()).isEqualByComparingTo(payable);
        assertThat(payable).isEqualByComparingTo("3000");
        assertThat(list.get("total_elements").asInt()).isEqualTo(2);
        JsonNode alpha = list.get("items").get(0);
        assertThat(alpha.get("partnerName").asText()).isEqualTo("Alpha");
        assertThat(alpha.get("closing").decimalValue()).isEqualByComparingTo("600");
        assertThat(alpha.get("debit").decimalValue()).isEqualByComparingTo("1200");
        assertThat(alpha.get("credit").decimalValue()).isEqualByComparingTo("1800");
        assertThat(alpha.get("aging").get("current").decimalValue()).isEqualByComparingTo("600");
        JsonNode beta = list.get("items").get(1);
        assertThat(beta.get("aging").get("over90").decimalValue()).isEqualByComparingTo("2400");
        assertThat(beta.get("overdue").decimalValue()).isEqualByComparingTo("2400");
        assertThat(list.get("totals").get("overdue").decimalValue()).isEqualByComparingTo("2400");

        JsonNode page = json(get("/ledger/partners/balances" + query + "&size=1&page=1"), token, null, 200);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("partnerName").asText()).isEqualTo("Beta");
        assertThat(page.get("total_pages").asInt()).isEqualTo(2);
        assertThat(page.get("totals").get("closing").decimalValue()).isEqualByComparingTo("3000");

        JsonNode st = json(get("/ledger/partners/" + b + "/statement?from=" + last + "-01-01&to=" + today), token, null, 200);
        assertThat(st.get("type").asText()).isEqualTo("SUPPLIER");
        assertThat(st.get("closing").decimalValue()).isEqualByComparingTo("2400");
        assertThat(st.get("openDocuments").get(0).get("type").asText()).isEqualTo("BILL");
    }

    // 3. Accountant export

    @Test
    void theExportHoldsEveryLineAndTheReports() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas; & Co");
        String vendor = partner(token, "VENDOR", "Sud");
        LocalDate today = LocalDate.now();
        String inv = invoice(token, "CUSTOMER", customer, today.minusDays(3), today.plusDays(27), "FA-X-1", 1000);
        pay(token, customer, inv, today.minusDays(1), 1200, "CHECK");
        invoice(token, "VENDOR", vendor, today.minusDays(2), today.plusDays(28), "FF-X-1", 300);
        invoice(token, "CUSTOMER", customer, LocalDate.of(last, 5, 5), LocalDate.of(last, 6, 5), "FA-X-0", 700);

        LocalDate from = LocalDate.of(today.getYear(), 1, 1);
        Integer lines = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry_line l JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                """, Integer.class, orgId, from, today);
        assertThat(lines).isGreaterThan(0);

        MockHttpServletResponse res = raw("/ledger/export?from=" + from + "&to=" + today + "&format=xlsx", token);
        assertThat(res.getHeader("Content-Disposition")).contains("bento-export-" + from + "-" + today + ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getContentAsByteArray()))) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            assertThat(names).containsExactly("Écritures", "Balance générale", "Balance clients", "Balance fournisseurs",
                    "Bilan", "CPC", "TVA", "Plan comptable");
            Sheet e = wb.getSheet("Écritures");
            assertThat(e.getLastRowNum()).isEqualTo(lines);
            assertThat(e.getRow(0).getCell(0).getStringCellValue()).isEqualTo("JournalCode");
            assertThat(e.getRow(0).getCell(13).getStringCellValue()).isEqualTo("SectionAnalytique");
            assertThat(wb.getSheet("Plan comptable").getLastRowNum()).isGreaterThan(2);
        }

        MockHttpServletResponse csv = raw("/ledger/export?from=" + from + "&to=" + today + "&format=csv", token);
        assertThat(csv.getHeader("Content-Disposition")).contains("bento-export-" + from + "-" + today + ".csv");
        byte[] bytes = csv.getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] rows = text.split("\r\n");
        assertThat(rows[0]).isEqualTo("JournalCode;JournalLib;EcritureNum;EcritureDate;CompteNum;CompteLib;CompAuxNum;"
                + "CompAuxLib;PieceRef;PieceDate;EcritureLib;Debit;Credit;SectionAnalytique;SourceType");
        assertThat(rows).hasSize(lines + 1);
        assertThat(text).contains("\"Atlas; & Co\"").contains(today.minusDays(1).toString().replace("-", "")).contains("1200.00");

        // The default period is the current fiscal year.
        MockHttpServletResponse def = raw("/ledger/export?format=csv", token);
        assertThat(def.getHeader("Content-Disposition")).contains("bento-export-" + from + "-" + today.getYear() + "-12-31.csv");
    }
}
