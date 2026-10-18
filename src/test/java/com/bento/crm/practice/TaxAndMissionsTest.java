package com.bento.crm.practice;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.bento.crm.tax.model.TaxDeadline;
import com.bento.crm.tax.model.TaxProfile;
import com.bento.crm.tax.service.TaxRules;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class TaxAndMissionsTest extends IntegrationTestBase {

    private JsonNode json(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        var res = mockMvc.perform(req).andReturn().getResponse();
        String r = res.getContentAsString();
        assertThat(res.getStatus()).as(r).isEqualTo(expected);
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private static TaxProfile profile(TaxProfile.VatRegime vat, boolean employees, int fyEnd) {
        TaxProfile p = new TaxProfile();
        p.setVatRegime(vat);
        p.setCorporateTax(true);
        p.setHasEmployees(employees);
        p.setFiscalYearEndMonth(fyEnd);
        return p;
    }

    private static List<TaxRules.Deadline> of(List<TaxRules.Deadline> all, TaxDeadline.Kind kind) {
        return all.stream().filter(d -> d.kind() == kind).toList();
    }

    @Test
    void moroccanDeadlinesFollowTheRules() {
        // 2026: the calendar year is the financial year.
        List<TaxRules.Deadline> y = TaxRules.between(profile(TaxProfile.VatRegime.MONTHLY, true, 12),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // TVA of September is due by the end of October (Sat 31 → Mon 2 Nov).
        assertThat(of(y, TaxDeadline.Kind.TVA)).anySatisfy(d -> {
            assertThat(d.periodLabel()).isEqualTo("2026-09");
            assertThat(d.dueDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        });
        // CNSS of September before 10 October (a Saturday → Monday 12).
        assertThat(of(y, TaxDeadline.Kind.CNSS)).anySatisfy(d -> {
            assertThat(d.periodLabel()).isEqualTo("2026-09");
            assertThat(d.dueDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        });
        // Four IS instalments in the year, end of March, June, September, December.
        assertThat(of(y, TaxDeadline.Kind.IS_INSTALLMENT)).extracting(TaxRules.Deadline::periodLabel)
                .containsExactly("FY2026-A1", "FY2026-A2", "FY2026-A3", "FY2026-A4");
        // The 2025 return is due by 31 March 2026, the 9421 by the end of February.
        assertThat(of(y, TaxDeadline.Kind.IS_RETURN)).extracting(TaxRules.Deadline::dueDate)
                .containsExactly(LocalDate.of(2026, 3, 31));
        assertThat(of(y, TaxDeadline.Kind.IR_ANNUAL_SALARIES)).extracting(TaxRules.Deadline::dueDate)
                .containsExactly(LocalDate.of(2026, 3, 2)); // 28 February 2026 is a Saturday

        // Quarterly TVA: Q3 by the end of October; no employees means no CNSS or IR.
        List<TaxRules.Deadline> q = TaxRules.between(profile(TaxProfile.VatRegime.QUARTERLY, false, 12),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(of(q, TaxDeadline.Kind.TVA)).extracting(TaxRules.Deadline::periodLabel)
                .containsExactly("2025-T4", "2026-T1", "2026-T2", "2026-T3");
        assertThat(of(q, TaxDeadline.Kind.CNSS)).isEmpty();

        // A window early in a financial year still sees its first instalments.
        List<TaxRules.Deadline> early = TaxRules.between(profile(TaxProfile.VatRegime.NONE, false, 12),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30));
        assertThat(of(early, TaxDeadline.Kind.IS_INSTALLMENT)).extracting(TaxRules.Deadline::periodLabel)
                .containsExactly("FY2027-A1", "FY2027-A2");
        // A June year end: return due by the end of September.
        assertThat(of(TaxRules.between(profile(TaxProfile.VatRegime.NONE, false, 6), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)), TaxDeadline.Kind.IS_RETURN)).extracting(TaxRules.Deadline::dueDate)
                .containsExactly(LocalDate.of(2026, 9, 30));
    }

    @Test
    void taxCalendarForTheCompanyAndAClient() throws Exception {
        String token = signUpAndLogin();
        String client = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas SARL\"}", 201).get("id").asText();

        JsonNode own = json(post("/tax-calendar/profiles"), token, "{\"vatRegime\": \"MONTHLY\"}", 201);
        assertThat(own.hasNonNull("partnerId")).isFalse();
        json(post("/tax-calendar/profiles"), token, "{\"vatRegime\": \"MONTHLY\"}", 409);
        JsonNode theirs = json(post("/tax-calendar/profiles"), token,
                "{\"partnerId\": \"" + client + "\", \"vatRegime\": \"QUARTERLY\", \"hasEmployees\": false}", 201);
        json(post("/tax-calendar/profiles"), token, "{\"fiscalYearEndMonth\": 13, \"partnerId\": \"" + client + "\"}", 400);

        JsonNode all = json(get("/tax-calendar/deadlines"), token, null, 200);
        assertThat(all.size()).isGreaterThan(3);
        JsonNode mine = json(get("/tax-calendar/deadlines?companyOnly=true"), token, null, 200);
        mine.forEach(d -> assertThat(d.hasNonNull("partnerId")).isFalse());
        JsonNode clientDeadlines = json(get("/tax-calendar/deadlines?partnerId=" + client), token, null, 200);
        clientDeadlines.forEach(d -> {
            assertThat(d.get("partnerName").asText()).isEqualTo("Atlas SARL");
            assertThat(d.get("kind").asText()).isNotIn("CNSS", "IR_SALARIES");
        });

        // Mark one done with the amount paid.
        String id = mine.get(0).get("id").asText();
        JsonNode done = json(put("/tax-calendar/deadlines/" + id), token, "{\"status\": \"DONE\", \"amount\": 1250.50}", 200);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.hasNonNull("doneAt")).isTrue();
        assertThat(done.get("overdue").asBoolean()).isFalse();
        json(put("/tax-calendar/deadlines/" + id), token, "{\"amount\": -3}", 400);

        // Removing the company profile and creating it again does not bring the done period back as due.
        String period = done.get("periodLabel").asText(), kind = done.get("kind").asText();
        json(delete("/tax-calendar/profiles/" + own.get("id").asText()), token, null, 204);
        json(post("/tax-calendar/profiles"), token, "{\"vatRegime\": \"MONTHLY\"}", 201);
        JsonNode again = json(get("/tax-calendar/deadlines?companyOnly=true"), token, null, 200);
        assertThat(again).filteredOn(d -> d.get("kind").asText().equals(kind) && d.get("periodLabel").asText().equals(period))
                .singleElement().satisfies(d -> assertThat(d.get("status").asText()).isEqualTo("DONE"));

        // Switching the client to no VAT drops their future TVA lines; done lines stay.
        json(put("/tax-calendar/profiles/" + theirs.get("id").asText()), token, "{\"vatRegime\": \"NONE\"}", 200);
        JsonNode after = json(get("/tax-calendar/deadlines?partnerId=" + client + "&from=" + LocalDate.now()), token, null, 200);
        after.forEach(d -> assertThat(d.get("kind").asText()).isNotEqualTo("TVA"));
        json(get("/tax-calendar/deadlines?from=2026-01-01&to=2029-01-01"), token, null, 400);

        // A salesperson has no finance rights.
        String sales = tokenForNewUser(orgIdOf(token), UserRole.SALESPERSON);
        json(get("/tax-calendar/deadlines"), sales, null, 403);
        json(delete("/tax-calendar/profiles/" + theirs.get("id").asText()), token, null, 204);
    }

    @Test
    void missionsDeliverablesAndRebilledCosts() throws Exception {
        String token = signUpAndLogin();
        String client = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Atlas SARL\"}", 201).get("id").asText();
        String other = json(post("/partners"), token, "{\"type\": \"CUSTOMER\", \"name\": \"Autre\"}", 201).get("id").asText();

        JsonNode m = json(post("/missions"), token, """
                {"partnerId": "%s", "name": "Bilan 2026", "status": "IN_PROGRESS", "startDate": "2026-01-10",
                 "dueDate": "2026-03-31", "feeAmount": 12000}""".formatted(client), 201);
        String mission = m.at("/mission/id").asText();
        json(post("/missions"), token, "{\"partnerId\": \"" + client + "\", \"name\": \"X\", \"startDate\": \"2026-02-01\", \"dueDate\": \"2026-01-01\"}", 400);

        JsonNode del = json(post("/missions/" + mission + "/deliverables"), token, "{\"title\": \"Liasse fiscale\"}", 201);
        JsonNode delivered = json(put("/mission-deliverables/" + del.get("id").asText()), token, "{\"status\": \"DELIVERED\"}", 200);
        assertThat(delivered.hasNonNull("deliveredAt")).isTrue();

        json(post("/disbursements"), token, """
                {"partnerId": "%s", "missionId": "%s", "spentOn": "2026-02-01", "description": "Droits d'enregistrement", "amount": 450}
                """.formatted(client, mission), 201);
        json(post("/disbursements"), token, """
                {"partnerId": "%s", "missionId": "%s", "spentOn": "2026-02-02", "description": "Taxi", "amount": 100, "vatRate": 0.2}
                """.formatted(client, mission), 201);
        json(post("/disbursements"), token, """
                {"partnerId": "%s", "missionId": "%s", "spentOn": "2026-02-02", "description": "x", "amount": 10}
                """.formatted(other, mission), 400);
        json(post("/disbursements"), token, """
                {"partnerId": "%s", "spentOn": "2026-02-02", "description": "x", "amount": 10, "vatRate": 0.15}
                """.formatted(client), 400);

        JsonNode summary = json(get("/missions?partnerId=" + client), token, null, 200).get(0);
        assertThat(summary.get("deliverables").asInt()).isEqualTo(1);
        assertThat(summary.get("delivered").asInt()).isEqualTo(1);
        assertThat(summary.get("unbilledDisbursements").decimalValue()).isEqualByComparingTo("550");

        JsonNode bill = json(post("/disbursements/bill"), token, "{\"partnerId\": \"" + client + "\"}", 201);
        assertThat(bill.get("lines").asInt()).isEqualTo(2);
        assertThat(bill.get("total").decimalValue()).isEqualByComparingTo("570");
        JsonNode invoice = json(get("/invoices/" + bill.get("invoiceId").asText()), token, null, 200);
        assertThat(invoice.get("status").asText()).isEqualTo("DRAFT");
        assertThat(invoice.toString()).contains("Débours : Droits d'enregistrement");

        assertThat(json(get("/disbursements?partnerId=" + client + "&unbilled=true"), token, null, 200).size()).isZero();
        json(post("/disbursements/bill"), token, "{\"partnerId\": \"" + client + "\"}", 409);
        String billed = json(get("/disbursements?partnerId=" + client), token, null, 200).get(0).get("id").asText();
        json(delete("/disbursements/" + billed), token, null, 409);
        json(delete("/missions/" + mission), token, null, 409);

        // A viewer can see missions but not bill them.
        String sales = tokenForNewUser(orgIdOf(token), UserRole.VIEWER);
        json(post("/disbursements/bill"), sales, "{\"partnerId\": \"" + client + "\"}", 403);
    }
}
