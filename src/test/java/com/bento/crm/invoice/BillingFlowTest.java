package com.bento.crm.invoice;

import com.bento.crm.invoice.service.BillingCalendar;
import com.bento.crm.invoice.service.BillingScheduler;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BillingFlowTest extends IntegrationTestBase {

    @Autowired
    private BillingScheduler billingScheduler;

    @Autowired
    private BillingCalendar billingCalendar;

    private String createCustomer(String token, String email) throws Exception {
        String emailField = email != null ? ", \"email\": \"" + email + "\"" : "";
        String response = mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"CUSTOMER\", \"name\": \"Atlas Café\"" + emailField + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode createRecurring(String token, String partnerId, LocalDate start) throws Exception {
        String response = mockMvc.perform(post("/recurring-invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "partnerId": "%s",
                                  "name": "Maintenance mensuelle",
                                  "frequency": "MONTHLY",
                                  "startDate": "%s",
                                  "paymentTermsDays": 30,
                                  "lines": [{"description": "Maintenance", "qty": 2, "unitPrice": 500, "vatRate": 0.2}]
                                }
                                """.formatted(partnerId, start)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String createInvoice(String token, String partnerId, LocalDate dueDate) throws Exception {
        String response = mockMvc.perform(post("/invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "CUSTOMER",
                                  "partnerId": "%s",
                                  "status": "SENT",
                                  "dueDate": "%s",
                                  "lines": [{"description": "Audit", "qty": 1, "unitPrice": 1000}]
                                }
                                """.formatted(partnerId, dueDate)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode runReminders(String token) throws Exception {
        String response = mockMvc.perform(post("/invoice-reminders/run")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void enableReminders(String token) throws Exception {
        mockMvc.perform(put("/invoice-reminders/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true, \"offsetsDays\": [7, 1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.offsetsDays[0]").value(1))
                .andExpect(jsonPath("$.offsetsDays[1]").value(7));
    }

    @Test
    void theSchedulerBillsADueTemplateOnceAndMovesItToTheNextMonth() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        LocalDate today = billingCalendar.today(orgId);
        String partnerId = createCustomer(token, "compta@atlas.ma");

        JsonNode template = createRecurring(token, partnerId, today);
        String id = template.get("id").asText();
        assertThat(template.get("nextRunDate").asText()).isEqualTo(today.toString());
        assertThat(template.get("total").decimalValue()).isEqualByComparingTo("1200");

        billingScheduler.generateRecurringInvoices();
        billingScheduler.generateRecurringInvoices();

        mockMvc.perform(get("/recurring-invoices/" + id + "/invoices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].recurringInvoiceId").value(id))
                .andExpect(jsonPath("$[0].invoiceDate").value(today.toString()))
                .andExpect(jsonPath("$[0].dueDate").value(today.plusDays(30).toString()))
                .andExpect(jsonPath("$[0].total").value(1200.0));

        mockMvc.perform(get("/recurring-invoices/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.generatedCount").value(1))
                .andExpect(jsonPath("$.nextRunDate").value(today.plusMonths(1).toString()));
    }

    @Test
    void withoutACustomerEmailTheGeneratedInvoiceStaysADraft() throws Exception {
        String token = signUpAndLogin();
        LocalDate start = billingCalendar.today(orgIdOf(token)).plusDays(5);
        String id = createRecurring(token, createCustomer(token, null), start).get("id").asText();

        mockMvc.perform(post("/recurring-invoices/" + id + "/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(get("/recurring-invoices/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.nextRunDate").value(start.plusMonths(1).toString()));
    }

    @Test
    void aStartDateInThePastIsRejected() throws Exception {
        String token = signUpAndLogin();
        String partnerId = createCustomer(token, "compta@atlas.ma");

        mockMvc.perform(post("/recurring-invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId": "%s", "name": "Old", "frequency": "MONTHLY", "startDate": "2020-01-01",
                                 "lines": [{"qty": 1, "unitPrice": 100}]}
                                """.formatted(partnerId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aLateInvoiceIsMarkedOverdueAndRemindedExactlyOnce() throws Exception {
        String token = signUpAndLogin();
        LocalDate today = billingCalendar.today(orgIdOf(token));
        String partnerId = createCustomer(token, "compta@atlas.ma");
        String invoiceId = createInvoice(token, partnerId, today.minusDays(2));
        enableReminders(token);

        JsonNode first = runReminders(token);
        assertThat(first.get("markedOverdue").asInt()).isEqualTo(1);
        assertThat(first.get("remindersSent").asInt()).isEqualTo(1);
        assertThat(runReminders(token).get("remindersSent").asInt()).isZero();

        mockMvc.perform(get("/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.reminderCount").value(1));

        mockMvc.perform(get("/invoice-reminders/history").param("invoiceId", invoiceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offsetDays").value(1))
                .andExpect(jsonPath("$[0].outcome").value("SENT"))
                .andExpect(jsonPath("$[0].recipient").value("compta@atlas.ma"));
    }

    @Test
    void remindersStayOffUntilTheOrganizationTurnsThemOn() throws Exception {
        String token = signUpAndLogin();
        LocalDate today = billingCalendar.today(orgIdOf(token));
        createInvoice(token, createCustomer(token, "compta@atlas.ma"), today.minusDays(10));

        mockMvc.perform(get("/invoice-reminders/policy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.offsetsDays.length()").value(4));

        JsonNode result = runReminders(token);
        assertThat(result.get("markedOverdue").asInt()).isEqualTo(1);
        assertThat(result.get("remindersSent").asInt()).isZero();
    }

    @Test
    void aCustomerWithoutEmailIsSkippedAndNotCountedAsReminded() throws Exception {
        String token = signUpAndLogin();
        LocalDate today = billingCalendar.today(orgIdOf(token));
        String invoiceId = createInvoice(token, createCustomer(token, null), today.minusDays(1));
        enableReminders(token);

        JsonNode result = runReminders(token);
        assertThat(result.get("skippedNoEmail").asInt()).isEqualTo(1);

        mockMvc.perform(get("/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.reminderCount").value(0));
    }
}
