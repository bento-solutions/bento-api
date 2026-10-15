package com.bento.crm.inbound;

import com.bento.crm.ai.DocumentExtraction;
import com.bento.crm.ai.DocumentExtractor;
import com.bento.crm.assistant.AssistantTools;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.inbound.mail.MimeEmailParser;
import com.bento.crm.inbound.service.InboundProcessor;
import com.bento.crm.inbound.service.WhatsAppDocumentCollector;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.inbound.webhook-secret=test-inbound-secret",
        "app.inbound.processing-enabled=false",
        "app.inbound.domain=in.example.ma"
})
class InboundTest extends IntegrationTestBase {

    @MockitoBean
    DocumentExtractor extractor;

    @Autowired
    InboundProcessor processor;

    @Autowired
    WhatsAppDocumentCollector whatsApp;

    @Autowired
    AssistantTools assistantTools;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String token,
                          String body, int expected) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String r = mockMvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n% " + text + "\n" + "x".repeat(2000) + "\n%%EOF").getBytes();
    }

    private static byte[] email(String from, String to, String authResults, String fileName, byte[] attachment) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, to);
        m.setSubject("Facture");
        if (authResults != null) m.addHeader("Authentication-Results", authResults);
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Bonjour, ci-joint notre facture.");
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment, "application/octet-stream")));
        file.setFileName(fileName);
        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(text);
        mp.addBodyPart(file);
        m.setContent(mp);
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toByteArray();
    }

    private JsonNode postEmail(String secret, byte[] mime, int expected) throws Exception {
        return json(post("/public/inbound/email/" + secret).contentType("message/rfc822").content(mime), null, null, expected);
    }

    private static DocumentExtraction invoice(String supplier, String number, double confidence) {
        return new DocumentExtraction("vendor_invoice", supplier, "001234567000089", null, null, null, null, "Smoke SARL",
                null, number, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "MAD", new BigDecimal("1000.00"),
                new BigDecimal("200.00"), new BigDecimal("1200.00"),
                List.of(new DocumentExtraction.Line("Ramettes A4", new BigDecimal("40"), new BigDecimal("25"), new BigDecimal("20"), new BigDecimal("1000"))),
                confidence, null);
    }

    private JsonNode documents(String token, String status) throws Exception {
        return json(get("/inbound/documents?status=" + status), token, null, 200).get("content");
    }

    @Test
    void invoicesEmailedByAKnownSupplierAreBookedAndTheRestWaitForReview() throws Exception {
        String token = signUpAndLogin();
        JsonNode settings = json(get("/inbound/settings"), token, null, 200);
        assertThat(settings.get("mailboxes").size()).isEqualTo(2);
        JsonNode invoices = settings.get("mailboxes").get(0).get("kind").asText().equals("VENDOR_INVOICES")
                ? settings.get("mailboxes").get(0) : settings.get("mailboxes").get(1);
        String address = invoices.get("address").asText();
        assertThat(address).startsWith("factures-").endsWith("@in.example.ma");

        String vendor = json(post("/partners"), token,
                "{\"type\": \"VENDOR\", \"name\": \"Atlas Fournitures\", \"email\": \"compta@atlas-fournitures.ma\"}", 201)
                .get("id").asText();

        // A wrong secret looks like no endpoint at all.
        postEmail("wrong", email("compta@atlas-fournitures.ma", address, null, "f.pdf", pdf("a")), 404);

        // Known supplier, sender authenticated by the receiving server: booked without a person.
        when(extractor.available()).thenReturn(true);
        when(extractor.extract(any(), any(), any())).thenReturn(invoice("Atlas Fournitures", "F-2026-001", 0.97));
        byte[] first = email("compta@atlas-fournitures.ma", address,
                "mx.example.ma; dmarc=pass (p=none) header.from=atlas-fournitures.ma", "F-2026-001.pdf", pdf("first"));
        assertThat(postEmail("test-inbound-secret", first, 200).get("filed").asInt()).isEqualTo(1);
        // The same email delivered twice files nothing new.
        assertThat(postEmail("test-inbound-secret", first, 200).get("filed").asInt()).isEqualTo(0);
        // Other tests may have left documents queued for their own organizations.
        assertThat(processor.runOnce()).isGreaterThanOrEqualTo(1);

        JsonNode booked = documents(token, "BOOKED");
        assertThat(booked.size()).isEqualTo(1);
        assertThat(booked.get(0).get("partnerId").asText()).isEqualTo(vendor);
        assertThat(booked.get(0).get("invoiceNumber").asText()).isEqualTo("F-2026-001");
        String invoiceId = booked.get(0).get("invoiceId").asText();
        mockMvc.perform(get("/invoices/" + invoiceId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.type").value("VENDOR"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.total").value(1200.0));
        mockMvc.perform(get("/files?ownerEntityType=INVOICE&ownerEntityId=" + invoiceId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        // The same supplier, but a forged-looking sender (no authentication result): review.
        when(extractor.extract(any(), any(), any())).thenReturn(invoice("Atlas Fournitures", "F-2026-002", 0.97));
        postEmail("test-inbound-secret", email("compta@atlas-fournitures.ma", address, null, "F-2026-002.pdf", pdf("second")), 200);
        processor.runOnce();
        JsonNode review = documents(token, "NEEDS_REVIEW");
        assertThat(review.size()).isEqualTo(1);
        assertThat(review.get(0).get("reviewReason").asText()).contains("Unknown sender");
        assertThat(review.get(0).get("extraction").get("invoiceNumber").asText()).isEqualTo("F-2026-002");
        String docId = review.get(0).get("id").asText();

        // A person checks it and books it, choosing the supplier.
        String book = """
                {"partnerId": "%s", "invoiceNumber": "F-2026-002", "invoiceDate": "2026-10-02",
                 "subtotal": 1000, "tax": 200, "total": 1200}""".formatted(vendor);
        json(post("/inbound/documents/" + docId + "/book"), token, book, 200);
        json(post("/inbound/documents/" + docId + "/book"), token, book, 409);
        mockMvc.perform(get("/inbound/documents/counts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.BOOKED").value(2))
                .andExpect(jsonPath("$.NEEDS_REVIEW").value(0));

        // An invoice already booked under that number is recognised as a duplicate.
        postEmail("test-inbound-secret", email("compta@atlas-fournitures.ma", address,
                "mx; dmarc=pass header.from=atlas-fournitures.ma", "again.pdf", pdf("third")), 200);
        processor.runOnce();
        assertThat(documents(token, "DUPLICATE").size()).isEqualTo(1);
    }

    @Test
    void withoutAiEveryInvoiceWaitsForAPersonAndUploadsJoinTheQueue() throws Exception {
        String token = signUpAndLogin();
        when(extractor.available()).thenReturn(false);
        String id = objectMapper.readTree(mockMvc.perform(multipart("/inbound/documents")
                        .file(new MockMultipartFile("file", "facture.pdf", "application/pdf", pdf("upload")))
                        .param("kind", "VENDOR_INVOICE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.channel").value("UPLOAD"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(multipart("/inbound/documents")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        processor.runOnce();
        JsonNode doc = json(get("/inbound/documents/" + id), token, null, 200);
        assertThat(doc.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(doc.get("reviewReason").asText()).contains("not set up");

        json(post("/inbound/documents/" + id + "/ignore"), token, null, 200);
        json(post("/inbound/documents/" + id + "/retry"), token, null, 200);
        assertThat(json(get("/inbound/documents/" + id), token, null, 200).get("status").asText()).isEqualTo("QUEUED");

        // Salespeople cannot see supplier invoices; only admins manage the inbox addresses.
        String sales = tokenForNewUser(orgIdOf(token), UserRole.SALESPERSON);
        json(get("/inbound/documents/" + id), sales, null, 403);
        JsonNode box = json(get("/inbound/settings"), token, null, 200).get("mailboxes").get(0);
        json(put("/inbound/mailboxes/" + box.get("id").asText()), token,
                "{\"allowedSenders\": [\"Compta@Fournisseur.ma\", \"@banque.ma\"], \"autoBook\": false}", 200);
        json(put("/inbound/mailboxes/" + box.get("id").asText()), token, "{\"allowedSenders\": [\"not an email\"]}", 400);
        JsonNode rotated = json(post("/inbound/mailboxes/" + box.get("id").asText() + "/rotate"), token, null, 200);
        assertThat(rotated.get("address").asText()).isNotEqualTo(box.get("address").asText());
        assertThat(rotated.get("allowedSenders").get(0).asText()).isEqualTo("compta@fournisseur.ma");
    }

    @Test
    void documentsSentOnWhatsAppByAKnownContactAreFiledInTheirFolder() throws Exception {
        String token = signUpAndLogin();
        UUID org = orgIdOf(token);
        String customer = json(post("/partners"), token,
                "{\"type\": \"CUSTOMER\", \"name\": \"Riad Zitoun\", \"phone\": \"+212 661-223344\"}", 201).get("id").asText();
        whatsApp.collect(org, "+212661223344", "application/pdf", "cin.pdf", "wamid.1", () -> pdf("cin"));
        whatsApp.collect(org, "+212700000000", "application/pdf", "spam.pdf", "wamid.2", () -> pdf("spam"));
        JsonNode filed = documents(token, "QUEUED,FILED");
        assertThat(filed.size()).isEqualTo(1);
        assertThat(filed.get(0).get("channel").asText()).isEqualTo("WHATSAPP");
        assertThat(filed.get(0).get("kind").asText()).isEqualTo("OTHER");
        assertThat(filed.get(0).get("partnerId").asText()).isEqualTo(customer);

        json(put("/inbound/settings"), token, "{\"whatsappDocuments\": false}", 200);
        whatsApp.collect(org, "+212661223344", "application/pdf", "rib.pdf", "wamid.3", () -> pdf("rib"));
        assertThat(documents(token, "QUEUED,FILED").size()).isEqualTo(1);
    }

    @Test
    void theAssistantsLookupsRunAndTheAssistantSaysWhenItIsOff() throws Exception {
        String token = signUpAndLogin();
        mockMvc.perform(get("/assistant/status").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.enabled").value(false));
        json(post("/assistant/chat"), token, "{\"messages\": [{\"role\": \"user\", \"content\": \"Qui me doit de l'argent ?\"}]}", 409);

        // Every read-only tool's query is valid against the real schema.
        UUID org = orgIdOf(token);
        String partner = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Café Atlas\"}", 201).get("id").asText();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),
                null, com.bento.crm.common.model.Permission.class.getEnumConstants().length > 0
                ? java.util.Arrays.stream(com.bento.crm.common.model.Permission.values())
                        .map(p -> new SimpleGrantedAuthority(p.name())).toList() : List.of()));
        TenantContext.setCurrentOrganizationId(org);
        List<AssistantTools.Def> tools = assistantTools.forCurrentUser();
        assertThat(tools).extracting(AssistantTools.Def::name).contains("search_contacts", "finance_summary", "propose_task");
        for (AssistantTools.Def d : tools) {
            JsonNode input = objectMapper.readTree(switch (d.name()) {
                case "search_contacts" -> "{\"query\": \"atlas\"}";
                case "contact_overview" -> "{\"contact_id\": \"" + partner + "\"}";
                case "list_invoices" -> "{\"type\": \"CUSTOMER\", \"overdue_only\": true}";
                case "list_tasks" -> "{\"mine_only\": true, \"overdue_only\": true}";
                case "stock_levels" -> "{\"low_only\": true}";
                default -> "{}";
            });
            Object out = d.run().apply(org, input);
            assertThat(out).as(d.name()).isNotNull();
        }
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> found = (List<java.util.Map<String, Object>>) tools.stream()
                .filter(t -> t.name().equals("search_contacts")).findFirst().orElseThrow()
                .run().apply(org, objectMapper.readTree("{\"query\": \"atlas\"}"));
        assertThat(found).hasSize(1);
    }

    @Test
    void onlyAnAuthenticatedSenderDomainCountsAsVerified() {
        assertThat(MimeEmailParser.verified(new String[]{"mx; dmarc=pass header.from=atlas.ma"}, "a@atlas.ma")).isTrue();
        assertThat(MimeEmailParser.verified(new String[]{"mx; dmarc=pass header.from=evil.ma"}, "a@atlas.ma")).isFalse();
        assertThat(MimeEmailParser.verified(new String[]{"mx; spf=pass smtp.mailfrom=atlas.ma; dkim=pass header.d=atlas.ma"}, "a@atlas.ma")).isTrue();
        assertThat(MimeEmailParser.verified(new String[]{"mx; spf=pass; dkim=fail header.d=atlas.ma"}, "a@atlas.ma")).isFalse();
        assertThat(MimeEmailParser.verified(null, "a@atlas.ma")).isFalse();
    }
}
