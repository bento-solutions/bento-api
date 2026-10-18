package com.bento.crm.practice;

import com.bento.crm.checklist.service.ChecklistReminderService;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class ChecklistAndPortalTest extends IntegrationTestBase {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes();

    @MockBean
    private EmailService emailService;

    @Autowired
    private ChecklistReminderService reminders;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private JsonNode upload(String url, String token, int expected) throws Exception {
        var res = mockMvc.perform(multipart(url).file(new MockMultipartFile("file", "releve.pdf", "application/pdf", PDF))
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(expected);
        return res.getContentAsString().isEmpty() ? null : objectMapper.readTree(res.getContentAsString());
    }

    private String client(String token, String name, String email) throws Exception {
        String body = email == null ? "{\"type\": \"CUSTOMER\", \"name\": \"%s\"}".formatted(name)
                : "{\"type\": \"CUSTOMER\", \"name\": \"%s\", \"email\": \"%s\"}".formatted(name, email);
        return json(post("/partners"), token, body, 201).get("id").asText();
    }

    /** Invites a portal user for the client and signs them in; returns the portal token. */
    private String portalUser(String staff, String partnerId, String email) throws Exception {
        JsonNode inv = json(post("/client-portal/accounts"), staff,
                "{\"partnerId\": \"%s\", \"email\": \"%s\", \"displayName\": \"Karim\"}".formatted(partnerId, email), 201);
        assertThat(inv.at("/account/invitePending").asBoolean()).isTrue();
        String url = inv.get("inviteUrl").asText();
        String invite = url.substring(url.indexOf("token=") + 6);
        JsonNode session = json(post("/portal/auth/accept"), null,
                "{\"token\": \"%s\", \"password\": \"Portail-2026!\"}".formatted(invite), 200);
        assertThat(session.at("/me/partnerId").asText()).isEqualTo(partnerId);
        // The invitation works once.
        json(post("/portal/auth/accept"), null, "{\"token\": \"%s\", \"password\": \"Autre-2026!\"}".formatted(invite), 400);
        return session.get("token").asText();
    }

    @Test
    void checklistFromTemplateIsReviewedAndCompletes() throws Exception {
        String token = signUpAndLogin();
        String partner = client(token, "Atlas SARL", "compta@atlas.ma");

        JsonNode templates = json(get("/document-requests/templates"), token, null, 200);
        assertThat(templates).extracting(t -> t.get("key").asText()).contains("MONTHLY", "YEAR_END");

        JsonNode d = json(post("/document-requests"), token, """
                {"partnerId": "%s", "title": "Pièces d'octobre", "period": "2026-10", "dueDate": "%s",
                 "remindChannel": "EMAIL", "remindEveryDays": 3, "items": ["Relevé bancaire", "Factures d'achat"]}
                """.formatted(partner, LocalDate.now().plusDays(10)), 201);
        String id = d.at("/request/id").asText();
        assertThat(d.get("items")).hasSize(2);
        assertThat(d.at("/request/missing").asInt()).isEqualTo(2);
        json(post("/document-requests"), token, "{\"partnerId\": \"%s\", \"title\": \"X\", \"remindEveryDays\": 99, \"items\": [\"A\"]}"
                .formatted(partner), 400);

        String first = d.get("items").get(0).get("id").asText();
        String second = d.get("items").get(1).get("id").asText();

        // Staff file the first one themselves: accepted straight away.
        JsonNode afterStaff = upload("/document-request-items/" + first + "/file", token, 200);
        assertThat(afterStaff.get("items").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(afterStaff.at("/request/status").asText()).isEqualTo("OPEN");

        // A rejection needs a reason.
        upload("/document-request-items/" + second + "/file", token, 200);
        json(post("/document-request-items/" + second + "/review"), token, "{\"status\": \"REJECTED\"}", 400);
        JsonNode rejected = json(post("/document-request-items/" + second + "/review"), token,
                "{\"status\": \"REJECTED\", \"note\": \"Page 2 manquante\"}", 200);
        assertThat(rejected.get("items").get(1).get("reviewNote").asText()).isEqualTo("Page 2 manquante");
        assertThat(rejected.at("/request/status").asText()).isEqualTo("OPEN");

        JsonNode accepted = json(post("/document-request-items/" + second + "/review"), token, "{\"status\": \"ACCEPTED\"}", 200);
        assertThat(accepted.at("/request/status").asText()).isEqualTo("COMPLETE");

        // An optional extra item does not reopen it; a required one does.
        json(post("/document-requests/" + id + "/items"), token, "{\"label\": \"Contrat\", \"required\": false}", 201);
        assertThat(json(get("/document-requests/" + id), token, null, 200).at("/request/status").asText()).isEqualTo("COMPLETE");
        json(post("/document-requests/" + id + "/items"), token, "{\"label\": \"Bulletins de paie\"}", 201);
        assertThat(json(get("/document-requests/" + id), token, null, 200).at("/request/status").asText()).isEqualTo("OPEN");

        // Read-only staff see it but cannot change it.
        String viewer = tokenForNewUser(orgIdOf(token), UserRole.VIEWER);
        json(get("/document-requests?partnerId=" + partner), viewer, null, 200);
        json(delete("/document-requests/" + id), viewer, null, 403);
        // Another firm cannot see it at all.
        json(get("/document-requests/" + id), signUpAndLogin(), null, 404);
    }

    @Test
    void remindersGoOutOnScheduleAndFallBackToEmail() throws Exception {
        String token = signUpAndLogin();
        String partner = client(token, "Rif Négoce", "gerant@rif.ma");
        String noEmail = client(token, "Sans Email", null);

        String id = json(post("/document-requests"), token, """
                {"partnerId": "%s", "title": "Clôture 2026", "remindChannel": "WHATSAPP", "remindEveryDays": 2,
                 "items": ["Inventaire"]}
                """.formatted(partner), 201).at("/request/id").asText();

        // WhatsApp is not connected for this firm, so the reminder goes by email instead.
        clearInvocations(emailService);
        JsonNode now = json(post("/document-requests/" + id + "/remind"), token, null, 200);
        assertThat(now.get("email").asBoolean()).isTrue();
        assertThat(now.get("whatsapp").asBoolean()).isFalse();
        assertThat(now.get("problem").asText()).startsWith("WhatsApp");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendRawHtml(eq("gerant@rif.ma"), contains("Clôture 2026"), html.capture());
        assertThat(html.getValue()).contains("Inventaire");

        // The scheduled run skips it until the interval has passed.
        clearInvocations(emailService);
        reminders.run(Instant.now());
        verify(emailService, never()).sendRawHtml(eq("gerant@rif.ma"), anyString(), anyString());
        reminders.run(Instant.now().plusSeconds(3 * 86400));
        verify(emailService).sendRawHtml(eq("gerant@rif.ma"), anyString(), anyString());
        assertThat(json(get("/document-requests/" + id), token, null, 200).at("/request/reminderCount").asInt()).isEqualTo(2);

        // No channel can reach a client without email or WhatsApp: the manual reminder says why.
        String lonely = json(post("/document-requests"), token, "{\"partnerId\": \"%s\", \"title\": \"T\", \"items\": [\"A\"]}"
                .formatted(noEmail), 201).at("/request/id").asText();
        JsonNode err = json(post("/document-requests/" + lonely + "/remind"), token, null, 409);
        assertThat(err.toString()).contains("no email");
    }

    @Test
    void clientSignsInAndSeesOnlyTheirOwnData() throws Exception {
        String staff = signUpAndLogin();
        String partner = client(staff, "Atlas SARL", "compta@atlas.ma");
        String other = client(staff, "Autre SARL", null);
        String email = "karim-" + UUID.randomUUID() + "@atlas.ma";
        String portal = portalUser(staff, partner, email);

        // Their invoice, a checklist, a mission with a delivered file, a deadline.
        json(post("/invoices"), staff, """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"description": "Honoraires", "qty": 1, "unitPrice": 1200, "vatRate": 0}]}
                """.formatted(partner, LocalDate.now().plusDays(15)), 201);
        String otherInvoice = json(post("/invoices"), staff, """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT",
                 "lines": [{"description": "X", "qty": 1, "unitPrice": 50, "vatRate": 0}]}
                """.formatted(other), 201).get("id").asText();
        JsonNode list = json(post("/document-requests"), staff, "{\"partnerId\": \"%s\", \"title\": \"Octobre\", \"items\": [\"Relevé\"]}"
                .formatted(partner), 201);
        String item = list.get("items").get(0).get("id").asText();
        String otherItem = json(post("/document-requests"), staff, "{\"partnerId\": \"%s\", \"title\": \"Autre\", \"items\": [\"B\"]}"
                .formatted(other), 201).get("items").get(0).get("id").asText();
        String mission = json(post("/missions"), staff, "{\"partnerId\": \"%s\", \"name\": \"Bilan 2026\", \"status\": \"IN_PROGRESS\"}"
                .formatted(partner), 201).at("/mission/id").asText();
        String deliverable = json(post("/missions/" + mission + "/deliverables"), staff, "{\"title\": \"Liasse fiscale\"}", 201)
                .get("id").asText();
        json(post("/tax-calendar/profiles"), staff, "{\"partnerId\": \"%s\", \"vatRegime\": \"MONTHLY\"}".formatted(partner), 201);

        JsonNode home = json(get("/portal/home"), portal, null, 200);
        assertThat(home.at("/me/companyName").asText()).isEqualTo("Atlas SARL");
        assertThat(home.get("documentsMissing").asInt()).isEqualTo(1);
        assertThat(home.get("openInvoices").asInt()).isEqualTo(1);
        assertThat(home.get("missionsInProgress").asInt()).isEqualTo(1);
        assertThat(home.hasNonNull("nextTaxDeadline")).isTrue();

        JsonNode invoices = json(get("/portal/invoices"), portal, null, 200);
        assertThat(invoices).hasSize(1);
        var pdf = mockMvc.perform(get("/portal/invoices/" + invoices.get(0).get("id").asText() + "/pdf")
                .header("Authorization", "Bearer " + portal)).andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
        json(get("/portal/invoices/" + otherInvoice + "/pdf"), portal, null, 404);
        assertThat(json(get("/portal/tax-deadlines"), portal, null, 200)).isNotEmpty();

        // Uploading a document flags it for review and tells the firm.
        JsonNode sent = upload("/portal/checklist-items/" + item + "/file", portal, 200);
        assertThat(sent.get("items").get(0).get("status").asText()).isEqualTo("RECEIVED");
        Integer notified = jdbc.queryForObject("SELECT count(*) FROM notification WHERE organization_id = ? AND related_entity_type = 'DOCUMENT_REQUEST'",
                Integer.class, orgIdOf(staff));
        assertThat(notified).isPositive();
        upload("/portal/checklist-items/" + otherItem + "/file", portal, 404);

        // Deliverables appear but their file only once delivered.
        JsonNode missions = json(get("/portal/missions"), portal, null, 200);
        assertThat(missions).hasSize(1);
        assertThat(missions.get(0).get("deliverables").get(0).get("hasFile").asBoolean()).isFalse();
        json(get("/portal/deliverables/" + deliverable + "/file"), portal, null, 404);
        clearInvocations(emailService);
        json(put("/mission-deliverables/" + deliverable), staff, "{\"status\": \"DELIVERED\"}", 200);
        verify(emailService).sendRawHtml(eq(email), contains("Liasse fiscale"), anyString());

        // The two kinds of session never cross.
        json(get("/portal/me"), staff, null, 401);
        json(get("/partners"), portal, null, 401);
        json(get("/document-requests"), portal, null, 401);

        // A deactivated account is signed out and cannot sign in again.
        String account = json(get("/client-portal/accounts?partnerId=" + partner), staff, null, 200).get(0).get("id").asText();
        json(put("/client-portal/accounts/" + account), staff, "{\"active\": false}", 200);
        json(get("/portal/me"), portal, null, 401);
        json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"Portail-2026!\"}".formatted(email), 401);
    }

    @Test
    void signInPasswordResetAndFirmChoice() throws Exception {
        String firmA = signUpAndLogin();
        String firmB = signUpAndLogin();
        String email = "multi-" + UUID.randomUUID() + "@client.ma";
        portalUser(firmA, client(firmA, "Client A", null), email);

        // One firm: straight in. Wrong password: 401.
        JsonNode s = json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"Portail-2026!\"}".formatted(email), 200);
        assertThat(s.hasNonNull("token")).isTrue();
        json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"wrong\"}".formatted(email), 401);

        // A client of two firms picks one.
        portalUser(firmB, client(firmB, "Client B", null), email);
        JsonNode choose = json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"Portail-2026!\"}".formatted(email), 200);
        assertThat(choose.get("firms")).hasSize(2);
        UUID orgB = orgIdOf(firmB);
        JsonNode inB = json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"Portail-2026!\", \"organizationId\": \"%s\"}"
                .formatted(email, orgB), 200);
        assertThat(inB.at("/me/organizationId").asText()).isEqualTo(orgB.toString());

        // Forgot always answers the same; the reset link sets a new password.
        json(post("/portal/auth/forgot"), null, "{\"email\": \"nobody-" + UUID.randomUUID() + "@x.ma\"}", 204);
        clearInvocations(emailService);
        json(post("/portal/auth/forgot"), null, "{\"email\": \"%s\"}".formatted(email), 204);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendRawHtml(eq(email), anyString(), html.capture());
        String body = html.getValue();
        int at = body.indexOf("token=") + 6;
        String reset = body.substring(at, body.indexOf('"', at));
        json(post("/portal/auth/reset"), null, "{\"token\": \"%s\", \"password\": \"short\"}".formatted(reset), 400);
        Thread.sleep(1100); // sessions carry whole seconds: the reset must come after the old one
        JsonNode after = json(post("/portal/auth/reset"), null, "{\"token\": \"%s\", \"password\": \"Nouveau-2026!\"}".formatted(reset), 200);
        // The new password signs straight into that firm; the session from before the reset is over.
        assertThat(json(post("/portal/auth/login"), null, "{\"email\": \"%s\", \"password\": \"Nouveau-2026!\"}".formatted(email), 200)
                .at("/me/organizationId").asText()).isEqualTo(after.at("/me/organizationId").asText());
        String before = after.at("/me/organizationId").asText().equals(orgB.toString()) ? inB.get("token").asText() : s.get("token").asText();
        json(get("/portal/me"), before, null, 401);
        json(get("/portal/me"), after.get("token").asText(), null, 200);
    }

    @Test
    void phonesRegisterForPush() throws Exception {
        String token = signUpAndLogin();
        assertThat(json(get("/devices/push-status"), token, null, 200).get("enabled").asBoolean()).isFalse();
        String device = "fcm-" + UUID.randomUUID();
        json(post("/devices"), token, "{\"platform\": \"android\", \"token\": \"%s\"}".formatted(device), 204);
        json(post("/devices"), token, "{\"platform\": \"fax\", \"token\": \"x\"}", 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_token WHERE token = ? AND deleted_at IS NULL", Integer.class, device))
                .isEqualTo(1);
        // Someone else cannot remove it.
        json(delete("/devices"), signUpAndLogin(), "{\"token\": \"%s\"}".formatted(device), 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_token WHERE token = ? AND deleted_at IS NULL", Integer.class, device))
                .isEqualTo(1);
        json(delete("/devices"), token, "{\"token\": \"%s\"}".formatted(device), 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_token WHERE token = ? AND deleted_at IS NULL", Integer.class, device))
                .isZero();
    }
}
