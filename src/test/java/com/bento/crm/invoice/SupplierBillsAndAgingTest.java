package com.bento.crm.invoice;

import com.bento.crm.invoice.service.BillingCalendar;
import com.bento.crm.invoice.service.SupplierBillService;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierBillsAndAgingTest extends IntegrationTestBase {

    /** A 1×1 transparent PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Autowired
    private SupplierBillService supplierBillService;

    @Autowired
    private BillingCalendar billingCalendar;

    private String partner(String token, String type, String name) throws Exception {
        String response = mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String purchaseOrder(String token, String vendorId) throws Exception {
        return purchaseOrder(token, vendorId, "CONFIRMED");
    }

    private String purchaseOrder(String token, String vendorId, String status) throws Exception {
        String customer = partner(token, "CUSTOMER", "Riad Bleu");
        String deal = objectMapper.readTree(mockMvc.perform(post("/deals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\": \"" + customer + "\", \"title\": \"Équipement\", \"stage\": \"OPEN\", \"amount\": 5000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        String response = mockMvc.perform(post("/purchase-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealId": "%s", "vendorPartnerId": "%s", "status": "%s",
                                 "lines": [{"product": "Écrans", "qty": 4, "cost": 1000}]}
                                """.formatted(deal, vendorId, status)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode invoice(String token, String type, String partnerId, LocalDate dueDate, int amount) throws Exception {
        String response = mockMvc.perform(post("/invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                                 "lines": [{"description": "Service", "qty": 1, "unitPrice": %d, "vatRate": 0}]}
                                """.formatted(type, partnerId, dueDate, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void aPurchaseOrderBecomesOneSupplierBillThatCanBeScheduled() throws Exception {
        String token = signUpAndLogin();
        String vendor = partner(token, "VENDOR", "Maroc Hardware");
        String po = purchaseOrder(token, vendor);
        LocalDate today = LocalDate.now();

        // The PO's own total (4 x 1000 + 20% VAT) is what the bill will carry.
        mockMvc.perform(get("/purchase-orders/" + po).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.subtotal").value(4000.0))
                .andExpect(jsonPath("$.total").value(4800.0));

        String draft = purchaseOrder(token, vendor, "DRAFT");
        mockMvc.perform(post("/invoices/from-purchase-order/" + draft)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());

        String response = mockMvc.perform(post("/invoices/from-purchase-order/" + po)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierInvoiceNumber\": \"MH-2026-117\", \"paymentTermsDays\": 60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("VENDOR"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.invoiceNumber").value("MH-2026-117"))
                .andExpect(jsonPath("$.purchaseOrderId").value(po))
                .andExpect(jsonPath("$.dueDate").value(today.plusDays(60).toString()))
                .andExpect(jsonPath("$.total").value(4800.0))
                .andReturn().getResponse().getContentAsString();
        String billId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/purchase-orders/" + po).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("INVOICED"));

        mockMvc.perform(post("/invoices/from-purchase-order/" + po)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(patch("/invoices/" + billId + "/payment-schedule")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledPaymentDate\": \"" + today.plusDays(55) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledPaymentDate").value(today.plusDays(55).toString()));

        String customer = partner(token, "CUSTOMER", "Café Atlas");
        String customerInvoice = invoice(token, "CUSTOMER", customer, today, 100).get("id").asText();
        mockMvc.perform(patch("/invoices/" + customerInvoice + "/payment-schedule")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledPaymentDate\": \"" + today + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agingSplitsWhatIsStillOwedByDaysPastDue() throws Exception {
        String token = signUpAndLogin();
        LocalDate today = billingCalendar.today(orgIdOf(token));
        String a = partner(token, "CUSTOMER", "Alpha");
        String b = partner(token, "CUSTOMER", "Beta");

        invoice(token, "CUSTOMER", a, today.plusDays(10), 1000);
        invoice(token, "CUSTOMER", a, today.minusDays(45), 2000);
        String partlyPaid = invoice(token, "CUSTOMER", b, today.minusDays(100), 3000).get("id").asText();
        invoice(token, "VENDOR", partner(token, "VENDOR", "Gamma"), today.minusDays(5), 999);

        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId": "%s", "invoiceId": "%s", "amount": 500, "method": "BANK_TRANSFER",
                                 "paymentDate": "%s"}
                                """.formatted(b, partlyPaid, today)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/invoices/aging?type=CUSTOMER").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiced").value(6000.0))
                .andExpect(jsonPath("$.paid").value(500.0))
                .andExpect(jsonPath("$.pending").value(1000.0))
                .andExpect(jsonPath("$.late").value(4500.0))
                .andExpect(jsonPath("$.buckets[0].amount").value(1000.0))
                .andExpect(jsonPath("$.buckets[2].amount").value(2000.0))
                .andExpect(jsonPath("$.buckets[4].amount").value(2500.0))
                .andExpect(jsonPath("$.partners[0].name").value("Alpha"))
                .andExpect(jsonPath("$.partners[0].total").value(3000.0))
                .andExpect(jsonPath("$.partners[1].oldestDaysLate").value(100));

        mockMvc.perform(get("/invoices/" + partlyPaid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.amountPaid").value(500.0));
        mockMvc.perform(get("/invoices?type=CUSTOMER").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content[?(@.id == '" + partlyPaid + "')].amountPaid").value(500.0));

        mockMvc.perform(get("/invoices/aging?type=VENDOR").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.late").value(999.0))
                .andExpect(jsonPath("$.buckets[1].count").value(1));
    }

    @Test
    void dueAndLateSupplierBillsRaiseOneAlertADay() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);
        LocalDate today = billingCalendar.today(orgId);
        String vendor = partner(token, "VENDOR", "Sotrabat");
        invoice(token, "VENDOR", vendor, today.minusDays(2), 700);
        invoice(token, "VENDOR", vendor, today.plusDays(2), 300);
        invoice(token, "VENDOR", vendor, today.plusDays(20), 900);

        assertThat(supplierBillService.organizationsToAlert(today.plusDays(4))).contains(orgId);
        assertThat(supplierBillService.alertForOrganization(orgId, today)).isTrue();
        assertThat(supplierBillService.alertForOrganization(orgId, today)).isFalse();

        mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("1 supplier bill overdue"))
                .andExpect(jsonPath("$.content[0].message").value(
                        "1 bill past due (700.00 MAD), 1 bill due in the next 3 days (300.00 MAD). See Finance → Aging."));
    }

    @Test
    void theInvoiceTemplateIsSavedValidatedAndPrinted() throws Exception {
        String token = signUpAndLogin();
        UUID orgId = orgIdOf(token);

        String upload = mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
                        .param("ownerEntityType", "ORGANIZATION")
                        .param("ownerEntityId", orgId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String logoId = objectMapper.readTree(upload).get("id").asText();

        mockMvc.perform(put("/invoice-template")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ice\": \"12345\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/invoice-template")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName": "Atlas Services SARL", "ice": "001525874000045", "ifNumber": "40123456",
                                 "rc": "Casablanca 412345", "rib": "011 780 0000123456789012 34", "accentColor": "#B45309",
                                 "logoFileId": "%s", "footerText": "Merci pour votre confiance."}
                                """.formatted(logoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ice").value("001525874000045"))
                .andExpect(jsonPath("$.logoFileId").value(logoId));

        mockMvc.perform(get("/invoice-template").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.legalName").value("Atlas Services SARL"));

        byte[] pdf = mockMvc.perform(get("/invoice-template/preview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String otherToken = signUpAndLogin();
        mockMvc.perform(put("/invoice-template")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logoFileId\": \"" + logoId + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
