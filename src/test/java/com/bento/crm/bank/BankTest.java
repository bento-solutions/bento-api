package com.bento.crm.bank;

import com.bento.crm.bank.statement.ParsedStatement;
import com.bento.crm.bank.statement.StatementText;
import com.bento.crm.bank.statement.TabularStatementParser;
import com.bento.crm.bank.model.BankAccount;
import com.bento.crm.bank.service.BankCategories;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.inbound.service.InboundProcessor;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"app.inbound.processing-enabled=false"})
class BankTest extends IntegrationTestBase {

    private static final String RIB = "007 780 0001234567890123 45";
    private static final String RIB_DIGITS = "007780000123456789012345";

    @Autowired
    InboundProcessor processor;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private JsonNode upload(String token, String accountId, String name, String type, byte[] content, int expected) throws Exception {
        String r = mockMvc.perform(multipart("/bank/accounts/" + accountId + "/statements")
                        .file(new MockMultipartFile("file", name, type, content))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    /** An Attijariwafa-style export: heading with the RIB, French dates and amounts, balance lines. */
    private static byte[] csv(String rib, String invoiceNumber) {
        String text = """
                ATTIJARIWAFA BANK
                Relevé de compte;;;;;
                RIB : %s;;;;;
                Date opération;Date valeur;Libellé;Débit;Crédit;Solde
                ;;SOLDE INITIAL;;;10 000,00
                02/10/2026;02/10/2026;VIR RECU CLIENT ATLAS FACT %s;;1 000,00;11 000,00
                05/10/2026;05/10/2026;PRLV CNSS SEPTEMBRE;300,00;;10 700,00
                06/10/2026;06/10/2026;FRAIS TENUE DE COMPTE;50,00;;10 650,00
                08/10/2026;08/10/2026;PAIEMENT CARTE BUREAU PLUS;120,00;;10 530,00
                08/10/2026;08/10/2026;;;;
                ;;SOLDE FINAL;;;10 530,00
                """.formatted(rib, invoiceNumber);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private String partner(String token, String type, String name) throws Exception {
        return json(post("/partners"), token, "{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}", 201).get("id").asText();
    }

    private JsonNode invoice(String token, String type, String partnerId, int amount, LocalDate due) throws Exception {
        return json(post("/invoices"), token, """
                {"type": "%s", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"description": "Prestation", "qty": 1, "unitPrice": %d, "vatRate": 0}]}
                """.formatted(type, partnerId, due, amount), 201);
    }

    @Test
    void statementTextReadsMoroccanFormats() {
        assertThat(StatementText.amount("1 234,56")).isEqualByComparingTo("1234.56");
        assertThat(StatementText.amount("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(StatementText.amount("(50,00)")).isEqualByComparingTo("-50");
        assertThat(StatementText.amount("120,00 DH")).isEqualByComparingTo("120");
        assertThat(StatementText.amount("abc")).isNull();
        assertThat(StatementText.date("05/10/26")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(StatementText.accountNumber("RIB : " + RIB)).isEqualTo(RIB_DIGITS);
        assertThat(StatementText.bank("", "011780000123456789012345")).isEqualTo(BankAccount.Bank.BANK_OF_AFRICA);
        assertThat(StatementText.bank("", "145780000123456789012345")).isEqualTo(BankAccount.Bank.BANQUE_POPULAIRE);
        assertThat(BankCategories.keyword("PRLV CNSS 09/2026", new BigDecimal("-300"))).isEqualTo("SOCIAL_CHARGES");
        assertThat(BankCategories.keyword("REDAL FACTURE EAU", new BigDecimal("-90"))).isEqualTo("UTILITIES");
        assertThat(BankCategories.keyword("VERSEMENT ESPECES", new BigDecimal("500"))).isEqualTo("SALES");
        // "Versement" paid out is not a sale.
        assertThat(BankCategories.keyword("VERSEMENT ESPECES", new BigDecimal("-500"))).isNull();
    }

    @Test
    void csvAndExcelStatementsAreParsed() throws Exception {
        ParsedStatement s = TabularStatementParser.csv(csv(RIB, "FA-2026-0001"));
        assertThat(s.bank()).isEqualTo(BankAccount.Bank.ATTIJARIWAFA);
        assertThat(s.accountNumber()).isEqualTo(RIB_DIGITS);
        assertThat(s.openingBalance()).isEqualByComparingTo("10000");
        assertThat(s.closingBalance()).isEqualByComparingTo("10530");
        assertThat(s.rows()).hasSize(4);
        assertThat(s.rows().get(0).amount()).isEqualByComparingTo("1000");
        assertThat(s.rows().get(1).amount()).isEqualByComparingTo("-300");
        assertThat(s.periodStart()).isEqualTo(LocalDate.of(2026, 10, 2));

        // A Bank of Africa style workbook: a single signed amount column, real dates and numbers.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Releve");
            sheet.createRow(0).createCell(0).setCellValue("BMCE Bank of Africa - Compte n° 0117800001234567");
            var h = sheet.createRow(2);
            String[] head = {"Date", "Libellé", "Montant", "Solde"};
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            var dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Object[][] rows = {{LocalDate.of(2026, 9, 3), "LOYER SEPTEMBRE", -4500.0, 5500.0},
                    {LocalDate.of(2026, 9, 10), "VIR RECU CLIENT", 2000.5, 7500.5}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(3 + r);
                var c = row.createCell(0);
                c.setCellValue((LocalDate) rows[r][0]);
                c.setCellStyle(dateStyle);
                row.createCell(1).setCellValue((String) rows[r][1]);
                row.createCell(2).setCellValue((Double) rows[r][2]);
                row.createCell(3).setCellValue((Double) rows[r][3]);
            }
            wb.write(out);
        }
        ParsedStatement x = TabularStatementParser.excel(out.toByteArray());
        assertThat(x.bank()).isEqualTo(BankAccount.Bank.BANK_OF_AFRICA);
        assertThat(x.accountNumber()).isEqualTo("0117800001234567");
        assertThat(x.rows()).hasSize(2);
        assertThat(x.rows().get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(x.rows().get(0).amount()).isEqualByComparingTo("-4500");
        assertThat(x.rows().get(1).amount()).isEqualByComparingTo("2000.50");
    }

    @Test
    void importCategorizeMatchAndUnmatch() throws Exception {
        String token = signUpAndLogin();
        String customer = partner(token, "CUSTOMER", "Atlas Distribution");
        JsonNode inv = invoice(token, "CUSTOMER", customer, 1000, LocalDate.now().plusDays(10));
        String invoiceId = inv.get("id").asText();
        String number = inv.get("invoiceNumber").asText();

        String account = json(post("/bank/accounts"), token,
                "{\"name\": \"Attijari principal\", \"accountNumber\": \"" + RIB + "\"}", 201).get("id").asText();
        json(post("/bank/accounts"), token, "{\"name\": \"Doublon\", \"accountNumber\": \"" + RIB + "\"}", 409);
        assertThat(json(get("/bank/accounts/" + account), token, null, 200).get("bank").asText()).isEqualTo("ATTIJARIWAFA");

        // A statement for another account is refused.
        upload(token, account, "autre.csv", "text/csv", csv("011 780 0009999999999999 11", number), 400);

        JsonNode result = upload(token, account, "releve-octobre.csv", "text/csv", csv(RIB, number), 201);
        assertThat(result.get("importSummary").get("rowsImported").asInt()).isEqualTo(4);
        assertThat(result.get("importSummary").get("balanceMismatch").asBoolean()).isFalse();
        // The transfer names the invoice and is for exactly what is owed: matched on its own.
        assertThat(result.get("autoMatched").asInt()).isEqualTo(1);
        assertThat(json(get("/invoices/" + invoiceId), token, null, 200).get("status").asText()).isEqualTo("PAID");

        // The same statement again adds nothing.
        JsonNode again = upload(token, account, "releve-octobre.csv", "text/csv", csv(RIB, number), 201);
        assertThat(again.get("importSummary").get("rowsImported").asInt()).isZero();
        assertThat(again.get("importSummary").get("rowsSkipped").asInt()).isEqualTo(4);

        JsonNode lines = json(get("/bank/transactions?accountId=" + account), token, null, 200).get("content");
        assertThat(lines.size()).isEqualTo(4);
        JsonNode in = find(lines, "VIR RECU");
        assertThat(in.get("status").asText()).isEqualTo("MATCHED");
        assertThat(in.get("category").asText()).isEqualTo("SALES");
        assertThat(in.get("invoiceNumber").asText()).isEqualTo(number);
        assertThat(find(lines, "CNSS").get("category").asText()).isEqualTo("SOCIAL_CHARGES");
        assertThat(find(lines, "FRAIS").get("category").asText()).isEqualTo("BANK_FEES");
        JsonNode card = find(lines, "BUREAU PLUS");
        assertThat(card.hasNonNull("category")).isFalse();

        // Balance: the first statement's opening balance plus the movements.
        JsonNode acc = json(get("/bank/accounts/" + account), token, null, 200);
        assertThat(acc.get("balance").decimalValue()).isEqualByComparingTo("10530");
        assertThat(acc.get("unmatched").asInt()).isEqualTo(3);

        // Unmatching removes the payment the match created: the invoice is open again.
        String inId = in.get("id").asText();
        json(post("/bank/transactions/" + inId + "/unmatch"), token, null, 200);
        assertThat(json(get("/invoices/" + invoiceId), token, null, 200).get("status").asText()).isEqualTo("SENT");
        JsonNode candidates = json(get("/bank/transactions/" + inId + "/candidates"), token, null, 200);
        assertThat(candidates.get(0).get("kind").asText()).isEqualTo("INVOICE");
        assertThat(candidates.get(0).get("id").asText()).isEqualTo(invoiceId);
        assertThat(candidates.get(0).get("reason").asText()).contains("invoice number");
        json(post("/bank/transactions/" + inId + "/match"), token, "{\"kind\": \"INVOICE\", \"id\": \"" + invoiceId + "\"}", 200);
        json(post("/bank/transactions/" + inId + "/match"), token, "{\"kind\": \"INVOICE\", \"id\": \"" + invoiceId + "\"}", 409);
        assertThat(json(get("/invoices/" + invoiceId), token, null, 200).get("status").asText()).isEqualTo("PAID");

        // Money paid out cannot settle a customer invoice.
        String cnss = find(lines, "CNSS").get("id").asText();
        json(post("/bank/transactions/" + cnss + "/match"), token, "{\"kind\": \"INVOICE\", \"id\": \"" + invoiceId + "\"}", 400);

        // The card payment matches an expense of the same amount.
        String expense = json(post("/expenses"), token, """
                {"expenseDate": "2026-10-07", "category": "OFFICE", "description": "Fournitures", "amount": 120,
                 "vatAmount": 20, "paymentMethod": "CARD"}""", 201).get("id").asText();
        String cardId = card.get("id").asText();
        JsonNode expCandidates = json(get("/bank/transactions/" + cardId + "/candidates"), token, null, 200);
        assertThat(expCandidates.findValuesAsText("id")).contains(expense);
        JsonNode matched = json(post("/bank/transactions/" + cardId + "/match"), token,
                "{\"kind\": \"EXPENSE\", \"id\": \"" + expense + "\"}", 200);
        assertThat(matched.get("category").asText()).isEqualTo("OFFICE");

        // A category chosen by hand can become a rule for later lines.
        String fees = find(lines, "FRAIS").get("id").asText();
        JsonNode set = json(put("/bank/transactions/" + fees + "/category"), token,
                "{\"category\": \"FEES\", \"remember\": true, \"containsText\": \"tenue de compte\"}", 200);
        assertThat(set.get("categorySource").asText()).isEqualTo("MANUAL");
        assertThat(json(get("/bank/rules"), token, null, 200).get(0).get("category").asText()).isEqualTo("FEES");
        json(put("/bank/transactions/" + fees + "/category"), token, "{\"category\": \"NOPE\"}", 400);

        // Ignored lines leave the to-do list; a matched line must be unmatched first.
        json(post("/bank/transactions/" + inId + "/ignore"), token, null, 409);
        json(post("/bank/transactions/" + cnss + "/ignore"), token, null, 200);
        assertThat(json(get("/bank/transactions?status=UNMATCHED"), token, null, 200).get("content").size()).isEqualTo(1);

        // A statement with matched lines cannot be removed.
        String importId = result.get("importSummary").get("id").asText();
        json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/bank/statements/" + importId), token, null, 409);

        JsonNode dash = json(get("/bank/dashboard"), token, null, 200);
        assertThat(dash.get("totalBalance").decimalValue()).isEqualByComparingTo("10530");
        assertThat(dash.get("forecast").get("weeks").size()).isEqualTo(13);
    }

    @Test
    void statementsFromTheInboxGoToTheAccountWithTheirNumber() throws Exception {
        String token = signUpAndLogin();
        String account = json(post("/bank/accounts"), token,
                "{\"name\": \"Compte courant\", \"accountNumber\": \"" + RIB_DIGITS + "\"}", 201).get("id").asText();

        String known = inboxUpload(token, "releve.csv", csv(RIB, "X-NONE"));
        String unknown = inboxUpload(token, "releve-cih.csv", csv("230 780 0005555555555555 66", "X-NONE"));
        processor.runOnce();

        JsonNode filed = json(get("/inbound/documents/" + known), token, null, 200);
        assertThat(filed.get("status").asText()).isEqualTo("FILED");
        assertThat(filed.get("extraction").get("rowsImported").asInt()).isEqualTo(4);
        assertThat(json(get("/bank/statements?accountId=" + account), token, null, 200).size()).isEqualTo(1);

        JsonNode review = json(get("/inbound/documents/" + unknown), token, null, 200);
        assertThat(review.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(review.get("reviewReason").asText()).contains("230780000555555555555566");

        // Filing it into an account with another number is refused; adding the account fixes it.
        json(post("/bank/statements/from-document/" + unknown + "?accountId=" + account), token, null, 400);
        String cih = json(post("/bank/accounts"), token,
                "{\"name\": \"CIH\", \"accountNumber\": \"230780000555555555555566\"}", 201).get("id").asText();
        JsonNode r = json(post("/bank/statements/from-document/" + unknown + "?accountId=" + cih), token, null, 200);
        assertThat(r.get("importSummary").get("rowsImported").asInt()).isEqualTo(4);
        assertThat(json(get("/inbound/documents/" + unknown), token, null, 200).get("status").asText()).isEqualTo("FILED");
        assertThat(json(get("/bank/accounts/" + cih), token, null, 200).get("bank").asText()).isEqualTo("CIH");
    }

    private String inboxUpload(String token, String name, byte[] content) throws Exception {
        return objectMapper.readTree(mockMvc.perform(multipart("/inbound/documents")
                        .file(new MockMultipartFile("file", name, "text/csv", content))
                        .param("kind", "BANK_STATEMENT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void forecastAddsOpenInvoicesBillsAndRecurringInvoices() throws Exception {
        String token = signUpAndLogin();
        String customer = partner(token, "CUSTOMER", "Client Rabat");
        String vendor = partner(token, "VENDOR", "Fournisseur Fes");
        json(post("/bank/accounts"), token, "{\"name\": \"Caisse banque\", \"accountNumber\": \"" + RIB_DIGITS
                + "\", \"openingBalance\": 5000, \"openingDate\": \"" + LocalDate.now().minusDays(1) + "\"}", 201);
        invoice(token, "CUSTOMER", customer, 3000, LocalDate.now().plusDays(10));
        invoice(token, "VENDOR", vendor, 1000, LocalDate.now().plusDays(45));
        // An overdue invoice is expected this week, not in the past.
        invoice(token, "CUSTOMER", customer, 200, LocalDate.now().minusDays(20));
        json(post("/recurring-invoices"), token, """
                {"partnerId": "%s", "name": "Abonnement", "frequency": "MONTHLY", "startDate": "%s",
                 "paymentTermsDays": 0, "lines": [{"description": "Abonnement", "qty": 1, "unitPrice": 100, "vatRate": 0.2}]}
                """.formatted(customer, LocalDate.now().plusDays(5)), 201);

        JsonNode f = json(get("/bank/dashboard"), token, null, 200).get("forecast");
        assertThat(f.get("startBalance").decimalValue()).isEqualByComparingTo("5000");
        JsonNode h30 = f.get("horizons").get(0), h90 = f.get("horizons").get(2);
        assertThat(h30.get("days").asInt()).isEqualTo(30);
        // 3000 + 200 + one recurring invoice (120 TTC) within 30 days; the bill falls after.
        assertThat(h30.get("inflow").decimalValue()).isEqualByComparingTo("3320");
        assertThat(h30.get("outflow").decimalValue()).isEqualByComparingTo("0");
        assertThat(h90.get("outflow").decimalValue()).isEqualByComparingTo("1000");
        assertThat(h90.get("inflow").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("3440"));
        assertThat(h90.get("endBalance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("5000").add(h90.get("inflow").decimalValue()).subtract(new BigDecimal("1000")));
        assertThat(f.get("weeks").get(0).get("inflow").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("200"));
    }

    private static JsonNode find(JsonNode lines, String text) {
        for (JsonNode l : lines) {
            if (l.get("description").asText().contains(text)) return l;
        }
        throw new AssertionError("No line with " + text);
    }
}
