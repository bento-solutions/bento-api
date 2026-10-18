package com.bento.crm.bank;

import com.bento.crm.inbound.service.InboundProcessor;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Phase 4 QA round 1: forged emailed statements, the recurring-cost average, matching by search. */
@TestPropertySource(properties = {
        "app.inbound.webhook-secret=test-inbound-secret",
        "app.inbound.processing-enabled=false",
        "app.inbound.domain=in.example.ma",
        "app.inbound.trusted-authserv-ids=mx.example.ma"
})
class BankQaRoundOneTest extends IntegrationTestBase {

    private static final String RIB_DIGITS = "007780000123456789012345";
    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    InboundProcessor processor;

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private static byte[] statement(String... lines) {
        StringBuilder b = new StringBuilder("ATTIJARIWAFA BANK\nRIB : 007 780 0001234567890123 45;;;;;\n"
                + "Date opération;Date valeur;Libellé;Débit;Crédit;Solde\n");
        for (String l : lines) b.append(l).append('\n');
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] email(String from, String to, String authResults, byte[] csv) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, to);
        m.setSubject("Relevé");
        if (authResults != null) m.addHeader("Authentication-Results", authResults);
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Relevé ci-joint.");
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(csv, "text/csv")));
        file.setFileName("releve.csv");
        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(text);
        mp.addBodyPart(file);
        m.setContent(mp);
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toByteArray();
    }

    private JsonNode statementsMailbox(String token) throws Exception {
        for (JsonNode b : json(get("/inbound/settings"), token, null, 200).get("mailboxes")) {
            if (b.get("kind").asText().equals("BANK_STATEMENTS")) return b;
        }
        throw new AssertionError("no statements mailbox");
    }

    /** The one document from this sender in this status. */
    private JsonNode documentFrom(String token, String sender, String status) throws Exception {
        JsonNode found = null;
        for (JsonNode d : json(get("/inbound/documents?status=" + status), token, null, 200).get("content")) {
            if (sender.equals(d.path("sender").asText())) {
                assertThat(found).as("one document from " + sender).isNull();
                found = d;
            }
        }
        assertThat(found).as(status + " from " + sender).isNotNull();
        return found;
    }

    @Test
    void anEmailedStatementIsFiledOnlyFromAVerifiedTrustedSender() throws Exception {
        String token = signUpAndLogin();
        json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB_DIGITS + "\"}", 201);
        String customer = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Hotel Zagora\"}", 201).get("id").asText();
        JsonNode inv = json(post("/invoices"), token, """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"description": "Séjour", "qty": 1, "unitPrice": 7777, "vatRate": 0}]}
                """.formatted(customer, LocalDate.now().plusDays(10)), 201);
        String number = inv.get("invoiceNumber").asText();
        String day = LocalDate.now().minusDays(1).format(FR);
        byte[] forged = statement(day + ";" + day + ";VIR RECU HOTEL ZAGORA FACT " + number + ";;7 777,00;7 777,00");

        String address = statementsMailbox(token).get("address").asText();
        // Unauthenticated: never filed, never matched.
        json(post("/public/inbound/email/test-inbound-secret").contentType("message/rfc822")
                .content(email("someone@attacker.test", address, null, forged)), null, null, 200);
        processor.runOnce();
        JsonNode doc = documentFrom(token, "someone@attacker.test", "NEEDS_REVIEW");
        assertThat(doc.get("reviewReason").asText()).startsWith("Sender not verified");
        assertThat(json(get("/invoices/" + inv.get("id").asText()), token, null, 200).get("status").asText()).isEqualTo("SENT");
        assertThat(json(get("/bank/transactions"), token, null, 200).get("content").size()).isZero();

        // Authenticated by our server but not on the inbox's trusted list: still checked by a person.
        String pass = "mx.example.ma; dmarc=pass header.from=banque.ma";
        json(post("/public/inbound/email/test-inbound-secret").contentType("message/rfc822")
                .content(email("releves@banque.ma", address, pass, statement(day + ";" + day + ";FRAIS;10,00;;-10,00"))), null, null, 200);
        processor.runOnce();
        assertThat(documentFrom(token, "releves@banque.ma", "NEEDS_REVIEW").get("reviewReason").asText()).contains("not a trusted sender");

        // Trusted and verified: filed and imported.
        JsonNode box = statementsMailbox(token);
        json(put("/inbound/mailboxes/" + box.get("id").asText()), token, "{\"allowedSenders\": [\"@banque.ma\"]}", 200);
        json(post("/public/inbound/email/test-inbound-secret").contentType("message/rfc822")
                .content(email("releves@banque.ma", address, pass, statement(day + ";" + day + ";FRAIS TENUE;20,00;;-20,00"))), null, null, 200);
        processor.runOnce();
        JsonNode filed = documentFrom(token, "releves@banque.ma", "FILED");
        assertThat(filed.get("extraction").get("rowsImported").asInt()).isEqualTo(1);
    }

    @Test
    void recurringCostsAreAveragedOverTheMonthsSeen() throws Exception {
        String token = signUpAndLogin();
        String account = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB_DIGITS + "\"}", 201)
                .get("id").asText();
        YearMonth last = YearMonth.now().minusMonths(1), before = last.minusMonths(1);
        String d1 = before.atDay(28).format(FR), d2 = last.atDay(28).format(FR);
        byte[] csv = statement(";;SOLDE INITIAL;;;50 000,00",
                d1 + ";" + d1 + ";VIR SALAIRES;10 000,00;;40 000,00",
                d2 + ";" + d2 + ";VIR SALAIRES;10 000,00;;30 000,00");
        json(multipart("/bank/accounts/" + account + "/statements")
                .file(new MockMultipartFile("file", "releve.csv", "text/csv", csv)), token, null, 201);
        JsonNode recurring = json(get("/bank/dashboard"), token, null, 200).get("forecast").get("recurringMonthly");
        JsonNode salaries = null;
        for (JsonNode c : recurring) if (c.get("category").asText().equals("SALARIES")) salaries = c;
        assertThat(salaries).isNotNull();
        assertThat(salaries.get("amount").decimalValue()).isEqualByComparingTo("10000");
    }

    @Test
    void aLineCanBeMatchedToAnInvoiceFoundBySearch() throws Exception {
        String token = signUpAndLogin();
        String account = json(post("/bank/accounts"), token, "{\"name\": \"Attijari\", \"accountNumber\": \"" + RIB_DIGITS + "\"}", 201)
                .get("id").asText();
        String customer = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Riad Atlas\"}", 201).get("id").asText();
        JsonNode inv = json(post("/invoices"), token, """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"description": "Séjour", "qty": 1, "unitPrice": 5000, "vatRate": 0}]}
                """.formatted(customer, LocalDate.now().plusDays(10)), 201);
        String day = LocalDate.now().minusDays(1).format(FR);
        json(multipart("/bank/accounts/" + account + "/statements")
                .file(new MockMultipartFile("file", "releve.csv", "text/csv",
                        statement(day + ";" + day + ";VIR RECU INCONNU;;1 234,56;1 234,56"))), token, null, 201);
        String line = json(get("/bank/transactions"), token, null, 200).get("content").get(0).get("id").asText();

        assertThat(json(get("/bank/transactions/" + line + "/candidates"), token, null, 200).size()).isZero();
        JsonNode found = json(get("/bank/transactions/" + line + "/candidates?q=riad"), token, null, 200);
        assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).get("id").asText()).isEqualTo(inv.get("id").asText());
        assertThat(json(get("/bank/transactions/" + line + "/candidates?q=" + inv.get("invoiceNumber").asText()), token, null, 200).size())
                .isEqualTo(1);
        json(post("/bank/transactions/" + line + "/match"), token,
                "{\"kind\": \"INVOICE\", \"id\": \"" + inv.get("id").asText() + "\"}", 200);
        assertThat(json(get("/invoices/" + inv.get("id").asText()), token, null, 200).get("status").asText()).isEqualTo("PARTIALLY_PAID");
    }
}
