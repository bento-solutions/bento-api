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
 * General ledger phase 3, wave A: VAT determination, advance invoices and advances, bank lines
 * posted on validation, credit notes.
 */
class LedgerPhase3Test extends IntegrationTestBase {

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

    private String partner(String token, String type, String name, String extra) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\""
                + (extra == null ? "" : ", " + extra) + "}", 201).get("id").asText();
    }

    private static String invoiceBody(String type, String partnerId, String status, String number, String lines, String extra) {
        LocalDate d = LocalDate.now();
        return """
                {"type": "%s", "partnerId": "%s", "status": "%s", "invoiceDate": "%s", "invoiceNumber": "%s",
                 "dueDate": "%s", "lines": [%s]%s}
                """.formatted(type, partnerId, status, d, number, d.plusDays(30), lines, extra == null ? "" : ", " + extra);
    }

    /** A line with no VAT of its own unless {@code extra} gives one. */
    private static String line(int qty, int price, String extra) {
        return "{\"description\": \"Item\", \"qty\": " + qty + ", \"unitPrice\": " + price
                + (extra == null ? "" : ", " + extra) + "}";
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
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    // 1. VAT determination

    @Test
    void vatCodesComeFromTheLineThePartnerTheProductAndItsGroup() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        JsonNode s = json(get("/ledger/posting-setup"), token, null, 200);
        assertThat(s.get("bankPostingMode").asText()).isEqualTo("ON_VALIDATION");
        Map<String, JsonNode> vat = new HashMap<>(), business = new HashMap<>(), product = new HashMap<>();
        s.get("vatSetup").forEach(v -> vat.put(v.get("code").asText(), v));
        s.get("businessGroups").forEach(g -> business.put(g.get("code").asText(), g));
        s.get("productGroups").forEach(g -> product.put(g.get("code").asText(), g));
        assertThat(vat.get("EXPORT").get("mention").asText()).contains("92-I-1°");
        assertThat(vat.get("EXONERE").get("mention").asText()).contains("article 91");
        assertThat(vat.get("TVA20").has("mention")).isFalse();
        assertThat(product).containsOnlyKeys("GOODS", "SERVICES", "TRANSPORT", "SUBCONTRACTING");
        assertThat(product.get("GOODS").get("vatCode").asText()).isEqualTo("TVA20");
        assertThat(product.get("TRANSPORT").get("vatCode").asText()).isEqualTo("TVA14");
        assertThat(business.get("MAROC").get("vatRule").asText()).isEqualTo("STANDARD");
        assertThat(business.get("EXPORT").get("vatRule").asText()).isEqualTo("EXEMPT");
        assertThat(business.get("EXPORT").get("exemptVatCode").asText()).isEqualTo("EXPORT");
        assertThat(s.get("generalSetup")).hasSize(8);
        assertThat(json(get("/ledger/accounts"), token, null, 200).toString()).contains("\"4421\"", "\"3411\"");

        // Setup writes.
        JsonNode m = json(put("/ledger/posting-setup/vat/" + vat.get("TVA0").get("id").asText()), token,
                "{\"mention\": \"TVA 0 %\"}", 200);
        boolean found = false;
        for (JsonNode v : m.get("vatSetup")) if (v.get("code").asText().equals("TVA0")) found = v.get("mention").asText().equals("TVA 0 %");
        assertThat(found).isTrue();
        detail(put("/ledger/posting-setup/product-groups/" + product.get("GOODS").get("id").asText()), token,
                "{\"vatCode\": \"NOPE\"}", 400);
        assertThat(detail(put("/ledger/posting-setup/business-groups/" + business.get("MAROC").get("id").asText()), token,
                "{\"vatRule\": \"EXEMPT\"}", 400)).contains("exemptVatCode");

        String exporter = partner(token, "CUSTOMER", "Paris SARL", "\"businessGroupId\": \"" + business.get("EXPORT").get("id").asText() + "\"");
        String local = partner(token, "CUSTOMER", "Casa SARL", null);
        String transport = json(post("/products"), token, "{\"sku\": \"TR-1\", \"name\": \"Livraison\", \"unitPrice\": 100,"
                + " \"productGroupId\": \"" + product.get("TRANSPORT").get("id").asText() + "\"}", 201).get("id").asText();
        JsonNode reduced = json(post("/products"), token, "{\"sku\": \"R-1\", \"name\": \"Eau\", \"unitPrice\": 10, \"vatCode\": \"TVA7\"}", 201);
        assertThat(reduced.get("vatCode").asText()).isEqualTo("TVA7");
        assertThat(reduced.get("taxRate").decimalValue()).isEqualByComparingTo("0.07");

        // The helper endpoint.
        JsonNode d = json(get("/ledger/vat-determination?partnerId=" + exporter + "&productId=" + transport), token, null, 200);
        assertThat(d.get("vatCode").asText()).isEqualTo("EXPORT");
        assertThat(d.get("exempt").asBoolean()).isTrue();
        assertThat(d.get("mention").asText()).contains("exportation");
        d = json(get("/ledger/vat-determination?partnerId=" + local + "&productId=" + transport), token, null, 200);
        assertThat(d.get("vatCode").asText()).isEqualTo("TVA14");
        assertThat(d.get("rate").decimalValue()).isEqualByComparingTo("14");
        d = json(get("/ledger/vat-determination?partnerId=" + local + "&productId=" + reduced.get("id").asText()), token, null, 200);
        assertThat(d.get("vatCode").asText()).isEqualTo("TVA7");
        assertThat(json(get("/ledger/vat-determination"), token, null, 200).get("vatCode").asText()).isEqualTo("TVA20");

        // Export customer: EXPORT code, no VAT, mention on the invoice; a manual line override wins.
        JsonNode exp = json(post("/invoices"), token, invoiceBody("CUSTOMER", exporter, "SENT", "EX-1",
                line(2, 500, null) + "," + line(1, 100, "\"vatCode\": \"TVA10\""), null), 201);
        assertThat(exp.get("lines").get(0).get("vatCode").asText()).isEqualTo("EXPORT");
        assertThat(exp.get("lines").get(0).get("vatRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(exp.get("lines").get(1).get("vatCode").asText()).isEqualTo("TVA10");
        assertThat(exp.get("tax").decimalValue()).isEqualByComparingTo("10");
        assertThat(exp.get("total").decimalValue()).isEqualByComparingTo("1110");
        Map<String, JsonNode> rows = new HashMap<>();
        exp.get("vatBreakdown").forEach(r -> rows.put(r.get("vatCode").asText(), r));
        assertThat(rows.get("EXPORT").get("base").decimalValue()).isEqualByComparingTo("1000");
        assertThat(rows.get("EXPORT").get("vat").decimalValue()).isEqualByComparingTo("0");
        assertThat(rows.get("EXPORT").get("mention").asText()).contains("exportation");
        assertThat(rows.get("TVA10").get("rate").decimalValue()).isEqualByComparingTo("10");
        Map<String, BigDecimal> e = byAccount(live(token, "INVOICE", exp.get("id").asText()));
        assertThat(e.get("3421")).isEqualByComparingTo("1110");
        assertThat(e.get("7127")).isEqualByComparingTo("-1100");
        assertThat(e.get("44553")).isEqualByComparingTo("-10");
        assertThat(e).doesNotContainKeys("4455", "44551");
        var pdf = mockMvc.perform(get("/invoices/" + exp.get("id").asText() + "/pdf").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);

        // Local customer: the transport product's group gives TVA14; an old line with only a rate keeps it.
        JsonNode loc = json(post("/invoices"), token, invoiceBody("CUSTOMER", local, "SENT", "LO-1",
                line(1, 1000, "\"productId\": \"" + transport + "\"") + "," + line(1, 200, "\"vatRate\": 0") + ","
                        + line(1, 300, "\"vatRate\": 20"), null), 201);
        assertThat(loc.get("lines").get(0).get("vatCode").asText()).isEqualTo("TVA14");
        assertThat(loc.get("lines").get(1).get("vatCode").asText()).isEqualTo("TVA0");
        assertThat(loc.get("lines").get(2).get("vatCode").asText()).isEqualTo("TVA20");
        assertThat(loc.get("lines").get(2).get("vatRate").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(loc.get("tax").decimalValue()).isEqualByComparingTo("200");
        Map<String, BigDecimal> le = byAccount(live(token, "INVOICE", loc.get("id").asText()));
        assertThat(le.get("44552")).isEqualByComparingTo("-140");
        assertThat(le.get("44551")).isEqualByComparingTo("-60");
        assertThat(le.get("3421")).isEqualByComparingTo("1700");
        detail(post("/invoices"), token, invoiceBody("CUSTOMER", local, "DRAFT", "LO-2", line(1, 10, "\"vatCode\": \"XX\""), null), 400);

        // Expenses: the VAT code's recoverable account (TVA20 when none).
        json(post("/bank/accounts"), token, "{\"name\": \"BP\", \"accountNumber\": \"" + RIB + "\"}", 201);
        JsonNode ex = json(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"TRAVEL\", \"amount\": 114,"
                + " \"vatAmount\": 14, \"vatCode\": \"tva14\", \"paymentMethod\": \"CARD\", \"costCenterId\": \""
                + costCenter(token) + "\"}", 201);
        assertThat(ex.get("vatCode").asText()).isEqualTo("TVA14");
        assertThat(byAccount(live(token, "EXPENSE", ex.get("id").asText())).get("34552")).isEqualByComparingTo("14");
        detail(post("/expenses"), token, "{\"expenseDate\": \"" + today() + "\", \"category\": \"TRAVEL\", \"amount\": 10,"
                + " \"vatCode\": \"NOPE\", \"paymentMethod\": \"CARD\", \"costCenterId\": \"" + costCenter(token) + "\"}", 400);
        assertThat(balance(orgId, "44552", null)).isEqualByComparingTo("-140");
        assertTrialBalanceBalances(token);
    }

    // 2. Advance invoices

    @Test
    void anAdvanceInvoiceIsDeductedFromTheFinalInvoice() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas", null);
        String deal = json(post("/deals"), token, "{\"partnerId\": \"" + customer + "\", \"title\": \"Projet X\", \"stage\": \"OPEN\","
                + " \"amount\": 20000}", 201).get("id").asText();

        detail(post("/invoices/advance"), token, "{\"partnerId\": \"" + customer + "\", \"dealId\": \"" + deal + "\"}", 400);
        detail(post("/invoices/advance"), token, "{\"partnerId\": \"" + customer + "\", \"amount\": 1, \"percent\": 10}", 400);
        // 25 % of the deal before VAT, or an amount with VAT included.
        JsonNode pct = json(post("/invoices/advance"), token, "{\"partnerId\": \"" + customer + "\", \"dealId\": \"" + deal
                + "\", \"percent\": 25}", 201);
        assertThat(pct.get("subtotal").decimalValue()).isEqualByComparingTo("5000");
        assertThat(pct.get("lines").get(0).get("description").asText()).isEqualTo("Acompte de 25 % sur devis Projet X");
        JsonNode ttc = json(post("/invoices/advance"), token, "{\"partnerId\": \"" + customer + "\", \"amount\": 1200,"
                + " \"amountIncludesVat\": true}", 201);
        assertThat(ttc.get("subtotal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(ttc.get("total").decimalValue()).isEqualByComparingTo("1200");
        json(delete("/invoices/" + pct.get("id").asText()), token, null, 204);
        json(delete("/invoices/" + ttc.get("id").asText()), token, null, 204);

        // 5,000 before VAT at TVA20: 6,000. A draft posts nothing.
        JsonNode adv = json(post("/invoices/advance"), token, "{\"partnerId\": \"" + customer + "\", \"dealId\": \"" + deal
                + "\", \"amount\": 5000, \"vatCode\": \"TVA20\", \"label\": \"Acompte démarrage\"}", 201);
        String advId = adv.get("id").asText();
        assertThat(adv.get("kind").asText()).isEqualTo("ADVANCE");
        assertThat(adv.get("type").asText()).isEqualTo("CUSTOMER");
        assertThat(adv.get("status").asText()).isEqualTo("DRAFT");
        assertThat(adv.get("invoiceNumber").asText()).matches("ACP-\\d{4}-\\d{4}");
        assertThat(adv.get("tax").decimalValue()).isEqualByComparingTo("1000");
        assertThat(adv.get("total").decimalValue()).isEqualByComparingTo("6000");
        assertThat(adv.get("dealId").asText()).isEqualTo(deal);
        assertThat(live(token, "ADVANCE", advId)).isNull();

        // Sent: 3421 6,000 / 4421 5,000 / 44551 1,000, in VT.
        ObjectNode send = (ObjectNode) adv.deepCopy();
        send.put("status", "SENT");
        json(patch("/invoices/" + advId), token, send.toString(), 200);
        JsonNode e = live(token, "ADVANCE", advId);
        assertThat(e.get("journalCode").asText()).isEqualTo("VT");
        Map<String, BigDecimal> m = byAccount(e);
        assertThat(m.get("3421")).isEqualByComparingTo("6000");
        assertThat(m.get("4421")).isEqualByComparingTo("-5000");
        assertThat(m.get("44551")).isEqualByComparingTo("-1000");
        assertThat(live(token, "INVOICE", advId)).isNull();

        // Paid like any invoice; the deal shows it.
        json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"invoiceId\": \"" + advId
                + "\", \"paymentDate\": \"" + today() + "\", \"amount\": 6000, \"method\": \"CHECK\"}", 201);
        JsonNode dealNow = json(get("/deals/" + deal), token, null, 200);
        assertThat(dealNow.get("advancePaid").asBoolean()).isTrue();
        assertThat(dealNow.get("advanceTotal").decimalValue()).isEqualByComparingTo("6000");
        JsonNode open = json(get("/invoices/advances/open?partnerId=" + customer + "&dealId=" + deal), token, null, 200);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("remainingBase").decimalValue()).isEqualByComparingTo("5000");
        assertThat(open.get(0).get("remainingVat").decimalValue()).isEqualByComparingTo("1000");
        assertThat(open.get(0).get("total").decimalValue()).isEqualByComparingTo("6000");
        JsonNode aging = json(get("/invoices/aging?type=CUSTOMER"), token, null, 200);
        assertThat(aging.get("advancesTotal").decimalValue()).isEqualByComparingTo("6000");

        // The final invoice of 20,000 before VAT deducts it: net 18,000.
        JsonNode fin = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-FIN",
                line(1, 20000, null), "\"dealId\": \"" + deal + "\", \"deductAdvances\": true"), 201);
        String finId = fin.get("id").asText();
        assertThat(fin.get("advanceDeductions")).hasSize(1);
        assertThat(fin.get("advanceDeductions").get(0).get("advanceInvoiceId").asText()).isEqualTo(advId);
        assertThat(fin.get("subtotal").decimalValue()).isEqualByComparingTo("15000");
        assertThat(fin.get("tax").decimalValue()).isEqualByComparingTo("3000");
        assertThat(fin.get("total").decimalValue()).isEqualByComparingTo("18000");
        assertThat(fin.get("vatBreakdown").get(0).get("vat").decimalValue()).isEqualByComparingTo("3000");
        Map<String, BigDecimal> f = byAccount(live(token, "INVOICE", finId));
        assertThat(f.get("3421")).isEqualByComparingTo("18000");
        assertThat(f.get("4421")).isEqualByComparingTo("5000");
        assertThat(f.get("7124")).isEqualByComparingTo("-20000");
        assertThat(f.get("44551")).isEqualByComparingTo("-3000");
        assertThat(balance(orgId, "4421", customer)).isEqualByComparingTo("0");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("18000");
        assertThat(balance(orgId, "44551", null)).isEqualByComparingTo("-4000");
        assertThat(json(get("/invoices/advances/open?partnerId=" + customer), token, null, 200)).isEmpty();
        assertThat(json(get("/invoices?dealId=" + deal), token, null, 200).get("total_elements").asInt()).isEqualTo(2);
        assertThat(json(get("/invoices?dealId=" + deal + "&kind=ADVANCE"), token, null, 200).get("total_elements").asInt()).isEqualTo(1);

        // No double deduction, and a deducted advance cannot be deleted.
        assertThat(detail(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "DRAFT", "FA-FIN2", line(1, 100, null),
                "\"advanceDeductions\": [{\"advanceInvoiceId\": \"" + advId + "\"}]"), 400)).contains("deducted twice");
        detail(delete("/invoices/" + advId), token, null, 409);
        assertTrialBalanceBalances(token);
    }

    // Advance payments

    @Test
    void advancePaymentsPostOnAdvanceAccountsAndApplyToInvoices() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        json(post("/bank/accounts"), token, "{\"name\": \"BMCE\", \"accountNumber\": \"" + RIB + "\"}", 201);
        String vendor = partner(token, "VENDOR", "Maroc Hardware", null);
        String customer = partner(token, "CUSTOMER", "Client", null);

        // A supplier advance: 3411 against the bank.
        JsonNode adv = json(post("/payments"), token, "{\"partnerId\": \"" + vendor + "\", \"paymentDate\": \"" + today()
                + "\", \"amount\": 1200, \"method\": \"BANK_TRANSFER\", \"advance\": true}", 201);
        String advId = adv.get("id").asText();
        assertThat(adv.get("advance").asBoolean()).isTrue();
        Map<String, BigDecimal> p = byAccount(live(token, "PAYMENT", advId));
        assertThat(p.get("3411")).isEqualByComparingTo("1200");
        assertThat(p.get("514101")).isEqualByComparingTo("-1200");
        assertThat(json(get("/payments/advances/open?partnerId=" + vendor), token, null, 200)).hasSize(1);
        assertThat(json(get("/invoices/aging?type=VENDOR"), token, null, 200).get("advancesTotal").decimalValue())
                .isEqualByComparingTo("1200");

        // The bill comes; the advance is applied: the bill is paid and an OD moves 3411 to 4411.
        String bill = json(post("/invoices"), token, invoiceBody("VENDOR", vendor, "SENT", "MH-1", line(1, 1000, null), null), 201)
                .get("id").asText();
        assertThat(detail(post("/payments/" + advId + "/apply"), token, "{}", 400)).contains("invoice");
        JsonNode applied = json(post("/payments/" + advId + "/apply"), token, "{\"invoiceId\": \"" + bill + "\"}", 200);
        assertThat(applied.get("invoiceId").asText()).isEqualTo(bill);
        assertThat(applied.get("advanceAppliedOn").asText()).isNotBlank();
        assertThat(json(get("/invoices/" + bill), token, null, 200).get("status").asText()).isEqualTo("PAID");
        JsonNode od = live(token, "ADVANCE_APPLY", advId);
        assertThat(od.get("journalCode").asText()).isEqualTo("OD");
        assertThat(byAccount(od).get("4411")).isEqualByComparingTo("1200");
        assertThat(byAccount(od).get("3411")).isEqualByComparingTo("-1200");
        assertThat(balance(orgId, "3411", vendor)).isEqualByComparingTo("0");
        assertThat(balance(orgId, "4411", vendor)).isEqualByComparingTo("0");
        assertThat(json(get("/payments/advances/open?partnerId=" + vendor), token, null, 200)).isEmpty();
        detail(post("/payments/" + advId + "/apply"), token, "{\"invoiceId\": \"" + bill + "\"}", 400);

        // A customer advance: the bank against 4421, then 4421 to 3421 when applied.
        JsonNode cadv = json(post("/payments"), token, "{\"partnerId\": \"" + customer + "\", \"paymentDate\": \"" + today()
                + "\", \"amount\": 600, \"method\": \"BANK_TRANSFER\", \"advance\": true}", 201);
        assertThat(byAccount(live(token, "PAYMENT", cadv.get("id").asText())).get("4421")).isEqualByComparingTo("-600");
        JsonNode ledger = json(get("/partners/" + customer + "/ledger"), token, null, 200);
        assertThat(ledger.get("advances")).hasSize(1);
        String inv = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-1", line(1, 1000, null), null), 201)
                .get("id").asText();
        json(post("/payments/" + cadv.get("id").asText() + "/apply"), token, "{\"invoiceId\": \"" + inv + "\"}", 200);
        JsonNode invNow = json(get("/invoices/" + inv), token, null, 200);
        assertThat(invNow.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(invNow.get("balanceDue").decimalValue()).isEqualByComparingTo("600");
        assertThat(balance(orgId, "4421", customer)).isEqualByComparingTo("0");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("600");
        assertTrialBalanceBalances(token);
    }

    // 3. Bank journal

    private static byte[] statement(LocalDate day, String invoiceNumber) {
        String d = day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String text = """
                ATTIJARIWAFA BANK
                Relevé de compte;;;;;
                RIB : %1$s;;;;;
                Date opération;Date valeur;Libellé;Débit;Crédit;Solde
                ;;SOLDE INITIAL;;;10 000,00
                %2$s;%2$s;VIR RECU CLIENT ATLAS FACT %3$s;;1 200,00;11 200,00
                %2$s;%2$s;PRLV CNSS SEPTEMBRE;300,00;;10 900,00
                %2$s;%2$s;PAIEMENT CARTE BUREAU PLUS;120,00;;10 780,00
                %2$s;%2$s;VIR EMIS FOURNISSEUR ACOMPTE;500,00;;10 280,00
                ;;SOLDE FINAL;;;10 280,00
                """.formatted(RIB, d, invoiceNumber);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void bankLinesPostOnlyOnceValidated() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB + "\"}", 201)
                .get("id").asText();
        String customer = partner(token, "CUSTOMER", "Atlas", null);
        String vendor = partner(token, "VENDOR", "Fournisseur", null);
        json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-2026-7781", line(1, 1000, null), null), 201);

        String r = mockMvc.perform(multipart("/bank/accounts/" + bank + "/statements")
                        .file(new MockMultipartFile("file", "releve.csv", "text/csv", statement(LocalDate.now(), "FA-2026-7781")))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(r).get("autoMatched").asInt()).as(r).isEqualTo(1);

        // The match created a payment, but nothing is in the ledger until validation.
        JsonNode journal = json(get("/bank/journal?bankAccountId=" + bank + "&status=TO_VALIDATE"), token, null, 200);
        assertThat(journal.get("total_elements").asInt()).isEqualTo(4);
        assertThat(journal.get("summary").get("toValidate").asInt()).isEqualTo(4);
        assertThat(journal.get("summary").get("statementBalance").decimalValue()).isEqualByComparingTo("10280");
        assertThat(journal.get("summary").get("ledgerBalance").decimalValue()).isEqualByComparingTo("0");
        Map<String, JsonNode> lines = new HashMap<>();
        journal.get("content").forEach(l -> lines.put(l.get("description").asText(), l));
        JsonNode received = lines.get("VIR RECU CLIENT ATLAS FACT FA-2026-7781");
        assertThat(received.get("status").asText()).isEqualTo("MATCHED");
        assertThat(received.get("proposedAccountCode").asText()).isEqualTo("3421");
        assertThat(received.get("partnerId").asText()).isEqualTo(customer);
        assertThat(received.get("invoiceNumber").asText()).isEqualTo("FA-2026-7781");
        assertThat(received.has("validatedAt")).isFalse();
        assertThat(lines.get("PRLV CNSS SEPTEMBRE").get("proposedAccountCode").asText()).isEqualTo("6174");
        assertThat(lines.get("PAIEMENT CARTE BUREAU PLUS").get("proposedAccountCode").asText()).isEqualTo("4497");
        String paymentId = received.get("paymentId").asText();
        assertThat(live(token, "PAYMENT", paymentId)).isNull();
        assertThat(json(get("/ledger/entries?sourceType=PAYMENT&sourceId=" + paymentId), token, null, 200).get("content")).isEmpty();
        assertThat(live(token, "BANK_LINE", lines.get("PRLV CNSS SEPTEMBRE").get("id").asText())).isNull();
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("0");

        // A line matched as a supplier advance waits too.
        String advLine = lines.get("VIR EMIS FOURNISSEUR ACOMPTE").get("id").asText();
        JsonNode m = json(post("/bank/transactions/" + advLine + "/match"), token,
                "{\"partnerId\": \"" + vendor + "\", \"advance\": true}", 200);
        assertThat(m.get("status").asText()).isEqualTo("MATCHED");
        String advPayment = m.get("paymentId").asText();
        assertThat(live(token, "PAYMENT", advPayment)).isNull();
        JsonNode advRow = null;
        for (JsonNode l : json(get("/bank/journal?bankAccountId=" + bank), token, null, 200).get("content")) {
            if (l.get("id").asText().equals(advLine)) advRow = l;
        }
        assertThat(advRow.get("advance").asBoolean()).isTrue();
        assertThat(advRow.get("proposedAccountCode").asText()).isEqualTo("3411");

        // Validating the received line posts its payment.
        JsonNode v = json(post("/bank/journal/validate"), token, "{\"bankAccountId\": \"" + bank + "\", \"transactionIds\": [\""
                + received.get("id").asText() + "\"]}", 200);
        assertThat(v.get("validated").asInt()).isEqualTo(1);
        assertThat(v.get("posted").asInt()).isEqualTo(1);
        Map<String, BigDecimal> pay = byAccount(live(token, "PAYMENT", paymentId));
        assertThat(pay.get("514101")).isEqualByComparingTo("1200");
        assertThat(pay.get("3421")).isEqualByComparingTo("-1200");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");

        // Everything up to today: the suspense line is reported.
        JsonNode all = json(post("/bank/journal/validate"), token, "{\"bankAccountId\": \"" + bank + "\", \"upTo\": \"" + today() + "\"}", 200);
        assertThat(all.get("validated").asInt()).isEqualTo(3);
        assertThat(all.get("posted").asInt()).isEqualTo(3);
        assertThat(all.get("warnings")).hasSize(1);
        assertThat(all.get("warnings").get(0).get("transactionId").asText()).isEqualTo(lines.get("PAIEMENT CARTE BUREAU PLUS").get("id").asText());
        assertThat(byAccount(live(token, "PAYMENT", advPayment)).get("3411")).isEqualByComparingTo("500");
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("280");
        JsonNode summary = json(get("/bank/journal?bankAccountId=" + bank), token, null, 200).get("summary");
        assertThat(summary.get("toValidate").asInt()).isZero();
        // The 10 000 opening balance of the statement is not in the books (no opening entry here).
        assertThat(summary.get("ledgerBalance").decimalValue()).isEqualByComparingTo("280");
        assertThat(summary.get("difference").decimalValue()).isEqualByComparingTo("10000");
        detail(post("/bank/journal/validate"), token, "{\"bankAccountId\": \"" + bank + "\"}", 400);

        // Unvalidate reverses the postings.
        JsonNode back = json(post("/bank/journal/" + received.get("id").asText() + "/unvalidate"), token, null, 200);
        assertThat(back.has("validatedAt")).isFalse();
        assertThat(live(token, "PAYMENT", paymentId)).isNull();
        assertThat(balance(orgId, "514101", null)).isEqualByComparingTo("-920");
        detail(post("/bank/journal/" + received.get("id").asText() + "/unvalidate"), token, null, 400);

        // Posting on import: what waited posts at once.
        JsonNode setup = json(put("/ledger/posting-setup/bank-posting-mode"), token, "{\"mode\": \"ON_IMPORT\"}", 200);
        assertThat(setup.get("bankPostingMode").asText()).isEqualTo("ON_IMPORT");
        assertThat(live(token, "PAYMENT", paymentId)).isNotNull();
        detail(put("/ledger/posting-setup/bank-posting-mode"), token, "{\"mode\": \"LATER\"}", 400);
        json(put("/ledger/posting-setup/bank-posting-mode"), token, "{\"mode\": \"ON_VALIDATION\"}", 200);
        assertThat(json(get("/bank/journal?status=TO_VALIDATE"), token, null, 200).get("total_elements").asInt()).isZero();
        assertTrialBalanceBalances(token);
    }

    // 4. Credit notes

    @Test
    void creditNotesMirrorTheInvoiceAndSentInvoicesAreLocked() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        String customer = partner(token, "CUSTOMER", "Atlas", null);
        String lines = line(1, 1000, null);
        String id = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-1", lines, null), 201)
                .get("id").asText();

        // A sent customer invoice: amounts, lines and back to draft refused; the status alone ("Mark paid") and the due date are fine.
        assertThat(detail(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-1", line(2, 1000, null), null), 400))
                .isEqualTo("Issue a credit note to correct a sent invoice");
        assertThat(detail(patch("/invoices/" + id), token, invoiceBody("CUSTOMER", customer, "DRAFT", "FA-1", lines, null), 400))
                .isEqualTo("Issue a credit note to correct a sent invoice");
        JsonNode whole = json(get("/invoices/" + id), token, null, 200);
        ObjectNode markPaid = (ObjectNode) whole.deepCopy();
        markPaid.put("status", "PAID");
        assertThat(json(patch("/invoices/" + id), token, markPaid.toString(), 200).get("status").asText()).isEqualTo("PAID");
        ObjectNode unpaid = (ObjectNode) markPaid.deepCopy();
        unpaid.put("status", "SENT");
        unpaid.putNull("paidAt");
        assertThat(json(patch("/invoices/" + id), token, unpaid.toString(), 200).get("status").asText()).isEqualTo("SENT");
        ObjectNode otherPartner = (ObjectNode) unpaid.deepCopy();
        otherPartner.put("partnerId", partner(token, "CUSTOMER", "Other", null));
        detail(patch("/invoices/" + id), token, otherPartner.toString(), 400);

        // Credit note for the whole invoice: AV number, SENT, mirrored posting, original CREDITED.
        detail(post("/invoices/" + id + "/credit-note"), token, "{}", 400);
        JsonNode cn = json(post("/invoices/" + id + "/credit-note"), token, "{\"reason\": \"Erreur de prix\"}", 201);
        assertThat(cn.get("kind").asText()).isEqualTo("CREDIT_NOTE");
        assertThat(cn.get("type").asText()).isEqualTo("CUSTOMER");
        assertThat(cn.get("status").asText()).isEqualTo("SENT");
        assertThat(cn.get("creditedInvoiceId").asText()).isEqualTo(id);
        assertThat(cn.get("invoiceNumber").asText()).matches("AV-\\d{4}-\\d{4}");
        assertThat(cn.get("total").decimalValue()).isEqualByComparingTo("1200");
        assertThat(cn.get("lines").get(0).get("vatCode").asText()).isEqualTo("TVA20");
        Map<String, BigDecimal> e = byAccount(live(token, "CREDIT_NOTE", cn.get("id").asText()));
        assertThat(e.get("3421")).isEqualByComparingTo("-1200");
        assertThat(e.get("7124")).isEqualByComparingTo("1000");
        assertThat(e.get("44551")).isEqualByComparingTo("200");
        JsonNode orig = json(get("/invoices/" + id), token, null, 200);
        assertThat(orig.get("status").asText()).isEqualTo("CREDITED");
        assertThat(orig.get("amountCredited").decimalValue()).isEqualByComparingTo("1200");
        assertThat(orig.get("balanceDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("0");
        detail(post("/invoices/" + id + "/credit-note"), token, "{\"reason\": \"Again\"}", 400);
        detail(patch("/invoices/" + cn.get("id").asText()), token, invoiceBody("CUSTOMER", customer, "SENT", "X", lines, null), 400);

        // A partial credit note lowers the balance due.
        String id2 = json(post("/invoices"), token, invoiceBody("CUSTOMER", customer, "SENT", "FA-2", line(2, 500, null), null), 201)
                .get("id").asText();
        JsonNode part = json(post("/invoices/" + id2 + "/credit-note"), token,
                "{\"reason\": \"Remise\", \"lines\": [{\"description\": \"Remise\", \"qty\": 1, \"unitPrice\": 300, \"vatCode\": \"TVA20\"}]}", 201);
        assertThat(part.get("total").decimalValue()).isEqualByComparingTo("360");
        JsonNode o2 = json(get("/invoices/" + id2), token, null, 200);
        assertThat(o2.get("status").asText()).isEqualTo("SENT");
        assertThat(o2.get("balanceDue").decimalValue()).isEqualByComparingTo("840");
        detail(post("/invoices/" + id2 + "/credit-note"), token,
                "{\"reason\": \"Trop\", \"lines\": [{\"qty\": 1, \"unitPrice\": 1000}]}", 400);
        JsonNode aging = json(get("/invoices/aging?type=CUSTOMER"), token, null, 200);
        assertThat(aging.get("pending").decimalValue().add(aging.get("late").decimalValue())).isEqualByComparingTo("840");
        JsonNode partnerLedger = json(get("/partners/" + customer + "/ledger"), token, null, 200);
        assertThat(partnerLedger.get("totals").get("totalOpen").decimalValue()).isEqualByComparingTo("840");
        assertThat(partnerLedger.get("totals").get("balance").decimalValue()).isEqualByComparingTo("840");
        assertThat(balance(orgId, "3421", customer)).isEqualByComparingTo("840");

        // Supplier bills stay editable; their credit notes are AVF and mirror the AC posting.
        String vendor = partner(token, "VENDOR", "Fournisseur", null);
        String bill = json(post("/invoices"), token, invoiceBody("VENDOR", vendor, "SENT", "B-1", lines, null), 201).get("id").asText();
        json(patch("/invoices/" + bill), token, invoiceBody("VENDOR", vendor, "SENT", "B-1", line(2, 1000, null), null), 200);
        JsonNode avf = json(post("/invoices/" + bill + "/credit-note"), token, "{\"reason\": \"Retour\"}", 201);
        assertThat(avf.get("invoiceNumber").asText()).matches("AVF-\\d{4}-\\d{4}");
        Map<String, BigDecimal> be = byAccount(live(token, "CREDIT_NOTE", avf.get("id").asText()));
        assertThat(be.get("4411")).isEqualByComparingTo("2400");
        assertThat(be.get("6125")).isEqualByComparingTo("-2000");
        assertThat(be.get("34552")).isEqualByComparingTo("-400");
        assertThat(balance(orgId, "4411", vendor)).isEqualByComparingTo("0");
        assertThat(json(get("/invoices?kind=CREDIT_NOTE"), token, null, 200).get("total_elements").asInt()).isEqualTo(3);
        assertTrialBalanceBalances(token);
    }
}
