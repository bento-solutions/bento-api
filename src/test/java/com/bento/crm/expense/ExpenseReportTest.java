package com.bento.crm.expense;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Expense reports (notes de frais): lines, posting to 4438, unposting, reimbursement, the PDF with its receipts. */
class ExpenseReportTest extends IntegrationTestBase {

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

    private String org() throws Exception {
        String token = signUpAndLogin();
        json(put("/ledger/posting-setup/start-date"), token,
                "{\"startDate\": \"" + (LocalDate.now().getYear() - 1) + "-01-01\"}", 200);
        return token;
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

    private String report(String token, LocalDate from, LocalDate to) throws Exception {
        return json(post("/expense-reports"), token, """
                {"employeeName": "Karim Alami", "title": "Mission Tanger", "periodFrom": "%s", "periodTo": "%s"}
                """.formatted(from, to), 201).get("id").asText();
    }

    private JsonNode line(String token, String report, String body) throws Exception {
        return json(post("/expense-reports/" + report + "/lines"), token, body, 201);
    }

    private void upload(String token, String owner, String id, String name, String type, byte[] content) throws Exception {
        var res = mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", name, type, content))
                        .param("ownerEntityType", owner)
                        .param("ownerEntityId", id)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    }

    @Test
    void aReportPostsItsLinesTo4438AndIsReimbursedFromTheBank() throws Exception {
        String token = org();
        UUID orgId = orgIdOf(token);
        LocalDate to = LocalDate.now().minusDays(1);
        LocalDate from = to.minusDays(6);
        String report = report(token, from, to);

        // A 600 TTC restaurant (TVA20: 100 computed from the code) and a 300 TTC exempt taxi.
        line(token, report, """
                {"expenseDate": "%s", "category": "MEALS", "description": "Restaurant client", "amount": 600,
                 "vatCode": "TVA20", "vendorName": "Le Détroit", "reference": "T-881"}
                """.formatted(from));
        JsonNode r = line(token, report, """
                {"expenseDate": "%s", "category": "TRAVEL", "description": "Taxi", "amount": 300,
                 "vatAmount": 0, "vatCode": "EXONERE"}
                """.formatted(from.plusDays(1)));
        assertThat(r.get("status").asText()).isEqualTo("DRAFT");
        assertThat(r.has("number")).isFalse();
        assertThat(r.get("lines")).hasSize(2);
        assertThat(r.get("totals").get("ht").decimalValue()).isEqualByComparingTo("800");
        assertThat(r.get("totals").get("vat").decimalValue()).isEqualByComparingTo("100");
        assertThat(r.get("totals").get("ttc").decimalValue()).isEqualByComparingTo("900");
        JsonNode restaurant = r.get("lines").get(0);
        assertThat(restaurant.get("vatAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(restaurant.get("amountHt").decimalValue()).isEqualByComparingTo("500");
        assertThat(restaurant.get("paymentMethod").asText()).isEqualTo("EMPLOYEE");
        assertThat(restaurant.get("attachments").asLong()).isZero();
        String restaurantId = restaurant.get("id").asText();
        String taxiId = r.get("lines").get(1).get("id").asText();

        // Posting: OD on the period end, Dr 6143 800, Dr 34552 100, Cr 4438 900. The expenses post nothing.
        JsonNode posted = json(post("/expense-reports/" + report + "/post"), token, null, 200);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        String number = posted.get("number").asText();
        assertThat(number).matches("NF-\\d{4}-\\d{4}");
        JsonNode entry = live(token, "EXPENSE_REPORT", report);
        assertThat(entry).isNotNull();
        assertThat(entry.get("journalCode").asText()).isEqualTo("OD");
        assertThat(entry.get("entryDate").asText()).isEqualTo(to.toString());
        assertThat(entry.get("sourceLabel").asText()).isEqualTo(number);
        Map<String, BigDecimal> lines = byAccount(entry);
        assertThat(lines.get("6143")).isEqualByComparingTo("800");
        assertThat(lines.get("34552")).isEqualByComparingTo("100");
        assertThat(lines.get("4438")).isEqualByComparingTo("-900");
        assertThat(lines).hasSize(3);
        boolean labelled = false;
        for (JsonNode l : entry.get("lines")) {
            if ("4438".equals(l.get("accountCode").asText())) labelled = l.get("label").asText().equals("Karim Alami - " + number);
        }
        assertThat(labelled).isTrue();
        assertThat(live(token, "EXPENSE", restaurantId)).isNull();
        assertThat(live(token, "EXPENSE", taxiId)).isNull();
        assertThat(balance(orgId, "4438")).isEqualByComparingTo("-900");

        // A posted report is read-only; its lines show in the expenses list with the number, read-only there too.
        json(post("/expense-reports/" + report + "/lines"), token, """
                {"expenseDate": "%s", "category": "OTHER", "amount": 10}
                """.formatted(from), 400);
        json(put("/expense-reports/" + report), token, "{\"employeeName\": \"X\"}", 400);
        JsonNode expenses = json(get("/expenses"), token, null, 200).get("content");
        assertThat(expenses).hasSize(2);
        assertThat(expenses.get(0).get("expenseReportNumber").asText()).isEqualTo(number);
        assertThat(expenses.get(0).get("expenseReportId").asText()).isEqualTo(report);
        json(put("/expenses/" + taxiId), token, """
                {"expenseDate": "%s", "category": "TRAVEL", "amount": 300, "paymentMethod": "CASH"}
                """.formatted(from), 400);
        json(delete("/expenses/" + taxiId), token, null, 400);

        // Unposting reverses the entry and goes back to DRAFT, keeping the number.
        JsonNode unposted = json(post("/expense-reports/" + report + "/unpost"), token, null, 200);
        assertThat(unposted.get("status").asText()).isEqualTo("DRAFT");
        assertThat(unposted.get("number").asText()).isEqualTo(number);
        assertThat(live(token, "EXPENSE_REPORT", report)).isNull();
        assertThat(balance(orgId, "4438")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "6143")).isEqualByComparingTo("0");
        assertThat(balance(orgId, "34552")).isEqualByComparingTo("0");

        // Posted again with the same number, then reimbursed by bank: 4438 back to 0.
        assertThat(json(post("/expense-reports/" + report + "/post"), token, null, 200).get("number").asText()).isEqualTo(number);
        String bank = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB + "\"}", 201)
                .get("id").asText();
        json(post("/expense-reports/" + report + "/reimburse"), token, "{\"method\": \"BANK_TRANSFER\"}", 400);
        JsonNode reimbursed = json(post("/expense-reports/" + report + "/reimburse"), token, """
                {"date": "%s", "method": "BANK_TRANSFER", "bankAccountId": "%s"}
                """.formatted(LocalDate.now(), bank), 200);
        assertThat(reimbursed.get("status").asText()).isEqualTo("REIMBURSED");
        assertThat(reimbursed.get("reimbursementDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(reimbursed.get("reimbursementBankAccountId").asText()).isEqualTo(bank);
        JsonNode reimbursement = live(token, "EXPENSE_REPORT_REIMB", report);
        assertThat(reimbursement.get("journalCode").asText()).isEqualTo("BQ");
        Map<String, BigDecimal> reimb = byAccount(reimbursement);
        assertThat(reimb.get("4438")).isEqualByComparingTo("900");
        assertThat(reimb.entrySet().stream().filter(e -> e.getKey().startsWith("5141")).findFirst().orElseThrow().getValue())
                .isEqualByComparingTo("-900");
        assertThat(balance(orgId, "4438")).isEqualByComparingTo("0");
        assertThat(live(token, "EXPENSE_REPORT", report)).isNotNull();

        // A reimbursed report cannot be unposted or cancelled; re-posting the history changes nothing.
        json(post("/expense-reports/" + report + "/unpost"), token, null, 400);
        json(post("/expense-reports/" + report + "/cancel"), token, null, 400);
        JsonNode repost = json(post("/ledger/repost"), token, null, 200);
        assertThat(repost.get("posted").asInt()).isZero();
        assertThat(repost.get("updated").asInt()).isZero();
        assertThat(repost.get("errors")).isEmpty();
        JsonNode totals = json(get("/ledger/trial-balance"), token, null, 200).get("totals");
        assertThat(totals.get("periodDebit").decimalValue()).isEqualByComparingTo(totals.get("periodCredit").decimalValue());

        // The list, with its filters.
        JsonNode list = json(get("/expense-reports?status=REIMBURSED&employee=karim"), token, null, 200);
        assertThat(list.get("content")).hasSize(1);
        JsonNode row = list.get("content").get(0);
        assertThat(row.get("number").asText()).isEqualTo(number);
        assertThat(row.get("lineCount").asInt()).isEqualTo(2);
        assertThat(row.get("totals").get("ttc").decimalValue()).isEqualByComparingTo("900");
        assertThat(json(get("/expense-reports?status=DRAFT"), token, null, 200).get("content")).isEmpty();
        assertThat(json(get("/expense-reports?employee=nobody"), token, null, 200).get("content")).isEmpty();
        assertThat(json(get("/expense-reports?from=" + to.plusDays(1)), token, null, 200).get("content")).isEmpty();
        assertThat(json(get("/expense-reports?from=" + from + "&to=" + to), token, null, 200).get("content")).hasSize(1);

        // EXPENSE_REIMBURSEMENT bank lines post to 4438.
        Map<String, String> bankCat = new HashMap<>();
        json(get("/ledger/posting-setup"), token, null, 200).get("bankCategories")
                .forEach(c -> bankCat.put(c.get("category").asText(), c.get("accountCode").asText()));
        assertThat(bankCat).containsEntry("EXPENSE_REIMBURSEMENT", "4438");
    }

    @Test
    void draftsAreGuardedAndTheLockDateApplies() throws Exception {
        String token = org();
        LocalDate day = LocalDate.now().minusDays(10);
        String report = report(token, day, day);
        json(post("/expense-reports/" + report + "/post"), token, null, 400);
        json(post("/expense-reports"), token, "{\"title\": \"No employee\"}", 400);
        json(post("/expense-reports"), token, "{\"employeeName\": \"A\", \"periodFrom\": \"" + day + "\", \"periodTo\": \""
                + day.minusDays(1) + "\"}", 400);

        // The employee can be a user of the organization: the name comes from the user.
        UUID me = userIdOf(token);
        JsonNode mine = json(post("/expense-reports"), token, "{\"employeeUserId\": \"" + me + "\"}", 201);
        assertThat(mine.get("employeeName").asText()).isNotBlank();
        assertThat(json(get("/expense-reports?employee=" + me), token, null, 200).get("content")).hasSize(1);
        json(post("/expense-reports"), token, "{\"employeeUserId\": \"" + UUID.randomUUID() + "\"}", 400);

        // Lines: added, changed, removed while DRAFT; an expense of another report is not found.
        String l = line(token, report, """
                {"expenseDate": "%s", "category": "FUEL", "amount": 240, "vatAmount": 40, "vatCode": "TVA20"}
                """.formatted(day)).get("lines").get(0).get("id").asText();
        JsonNode changed = json(put("/expense-reports/" + report + "/lines/" + l), token, """
                {"expenseDate": "%s", "category": "FUEL", "amount": 120, "vatAmount": 20, "vatCode": "TVA20"}
                """.formatted(day), 200);
        assertThat(changed.get("totals").get("ttc").decimalValue()).isEqualByComparingTo("120");
        json(put("/expense-reports/" + mine.get("id").asText() + "/lines/" + l), token, """
                {"expenseDate": "%s", "category": "FUEL", "amount": 120}
                """.formatted(day), 404);
        JsonNode removed = json(delete("/expense-reports/" + report + "/lines/" + l), token, null, 200);
        assertThat(removed.get("lines")).isEmpty();
        line(token, report, """
                {"expenseDate": "%s", "category": "FUEL", "amount": 120, "vatAmount": 20}
                """.formatted(day));

        // Paid by an employee is for report lines only.
        json(post("/expenses"), token, """
                {"expenseDate": "%s", "category": "FUEL", "amount": 50, "paymentMethod": "EMPLOYEE"}
                """.formatted(day), 400);

        // The lock date: a report dated in a locked period is refused.
        json(put("/ledger/settings"), token, "{\"lockDate\": \"" + day + "\"}", 200);
        String detail = json(post("/expense-reports/" + report + "/post"), token, null, 400).get("detail").asText();
        assertThat(detail).isEqualTo("This period is locked");
        assertThat(json(get("/expense-reports/" + report), token, null, 200).has("number")).isFalse();
        json(put("/ledger/settings"), token, "{\"lockDate\": null}", 200);

        // Cancel from DRAFT only; a cancelled report's lines leave the expenses list.
        assertThat(json(get("/expenses"), token, null, 200).get("content")).hasSize(1);
        JsonNode cancelled = json(post("/expense-reports/" + report + "/cancel"), token, null, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        json(post("/expense-reports/" + report + "/post"), token, null, 400);
        json(delete("/expense-reports/" + report), token, null, 400);
        assertThat(json(get("/expenses"), token, null, 200).get("content")).isEmpty();

        // A draft never posted is deleted.
        json(delete("/expense-reports/" + mine.get("id").asText()), token, null, 204);
        json(get("/expense-reports/" + mine.get("id").asText()), token, null, 404);
    }

    @Test
    void thePdfListsTheExpensesThenTheReceipts() throws Exception {
        String token = org();
        LocalDate day = LocalDate.now().minusDays(3);
        String report = report(token, day, day);
        JsonNode r = line(token, report, """
                {"expenseDate": "%s", "category": "MEALS", "description": "Déjeuner", "amount": 600, "vatCode": "TVA20"}
                """.formatted(day));
        String expense = r.get("lines").get(0).get("id").asText();
        upload(token, "EXPENSE", expense, "ticket.png", "image/png", png());
        upload(token, "EXPENSE", expense, "facture.pdf", "application/pdf", onePagePdf());
        upload(token, "EXPENSE_REPORT", report, "notes.txt", "text/plain", "ordre de mission".getBytes());
        JsonNode after = json(get("/expense-reports/" + report), token, null, 200);
        assertThat(after.get("lines").get(0).get("attachments").asLong()).isEqualTo(2);
        assertThat(after.get("attachments").asLong()).isEqualTo(1);
        assertThat(json(get("/files?ownerEntityType=EXPENSE_REPORT&ownerEntityId=" + report), token, null, 200)).hasSize(1);

        var res = mockMvc.perform(get("/expense-reports/" + report + "/pdf").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).isEqualTo("application/pdf");
        byte[] pdf = res.getContentAsByteArray();
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        // The table page, the receipt image's page, the receipt PDF's page.
        assertThat(reader.getNumberOfPages()).isEqualTo(3);
        String first = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(first).contains("NOTE DE FRAIS").contains("Karim Alami").contains("notes.txt");
        reader.close();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 200);
        g.setColor(Color.BLACK);
        g.drawString("TICKET 600,00", 20, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] onePagePdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document(com.lowagie.text.PageSize.A5);
        com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new com.lowagie.text.Paragraph("Facture restaurant 600,00 MAD"));
        doc.close();
        return out.toByteArray();
    }
}
