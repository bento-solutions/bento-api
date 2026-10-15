package com.bento.crm.payment;

import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayByLinkTest extends IntegrationTestBase {

    private String partner(String token, String type, String name) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/partners")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"" + type + "\", \"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String invoice(String token, String type, String partnerId, String status, int amount) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "%s", "partnerId": "%s", "status": "%s", "dueDate": "%s",
                                 "lines": [{"description": "Maintenance annuelle", "qty": 2, "unitPrice": %d, "vatRate": 0}]}
                                """.formatted(type, partnerId, status, LocalDate.now().plusDays(30), amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private void provider(String token, String provider, int expected) throws Exception {
        mockMvc.perform(put("/payment-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"" + provider + "\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    void aCustomerPaysAnInvoiceThroughItsLinkOnce() throws Exception {
        String token = signUpAndLogin();
        String customer = partner(token, "CUSTOMER", "Riad Bleu");
        String invoiceId = invoice(token, "CUSTOMER", customer, "SENT", 600);

        // Off by default; Stripe cannot be chosen without server keys; the test gateway can.
        mockMvc.perform(get("/payment-settings").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.provider").value("NONE"))
                .andExpect(jsonPath("$.available.length()").value(2));
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-link").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
        provider(token, "STRIPE", 400);
        provider(token, "MOCK", 200);

        JsonNode link = objectMapper.readTree(mockMvc.perform(post("/invoices/" + invoiceId + "/payment-link")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString());
        String url = link.get("url").asText();
        assertThat(url).startsWith("http://localhost:3000/pay/");
        String payToken = url.substring(url.lastIndexOf('/') + 1);

        // Asking again returns the same link.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-link").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.url").value(url));

        // The public page needs no login and shows only this invoice.
        mockMvc.perform(get("/public/pay/" + payToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Riad Bleu"))
                .andExpect(jsonPath("$.remaining").value(1200.0))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.testMode").value(true))
                .andExpect(jsonPath("$.lines[0].description").value("Maintenance annuelle"))
                .andExpect(jsonPath("$.partnerId").doesNotExist());
        mockMvc.perform(get("/public/pay/" + payToken + "/pdf")).andExpect(status().isOk());

        String checkoutUrl = objectMapper.readTree(mockMvc.perform(post("/public/pay/" + payToken + "/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("url").asText();
        String sessionId = checkoutUrl.substring(checkoutUrl.indexOf("mock=") + 5);

        mockMvc.perform(post("/public/pay/" + payToken + "/mock-complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\": \"wrong\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/public/pay/" + payToken + "/mock-complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\": \"" + sessionId + "\"}"))
                .andExpect(status().isNoContent());
        // A repeated confirmation records nothing more.
        mockMvc.perform(post("/public/pay/" + payToken + "/mock-complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\": \"" + sessionId + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/invoices/" + invoiceId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amountPaid").value(1200.0));
        mockMvc.perform(get("/payments/invoice/" + invoiceId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].method").value("CARD"));
        mockMvc.perform(get("/public/pay/" + payToken)).andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/public/pay/" + payToken + "/checkout")).andExpect(status().is4xxClientError());
    }

    @Test
    void linksAreOnlyForSentCustomerInvoicesAndCanBeCancelled() throws Exception {
        String token = signUpAndLogin();
        provider(token, "MOCK", 200);
        String customer = partner(token, "CUSTOMER", "Café Atlas");
        String draft = invoice(token, "CUSTOMER", customer, "DRAFT", 100);
        String bill = invoice(token, "VENDOR", partner(token, "VENDOR", "Fournisseur"), "SENT", 100);
        mockMvc.perform(post("/invoices/" + draft + "/payment-link").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/invoices/" + bill + "/payment-link").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        String sent = invoice(token, "CUSTOMER", customer, "SENT", 100);
        String url = objectMapper.readTree(mockMvc.perform(post("/invoices/" + sent + "/payment-link")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString()).get("url").asText();
        String payToken = url.substring(url.lastIndexOf('/') + 1);
        mockMvc.perform(delete("/invoices/" + sent + "/payment-link").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/public/pay/" + payToken)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/public/pay/" + payToken + "/checkout")).andExpect(status().is4xxClientError());

        mockMvc.perform(get("/public/pay/not-a-real-token-at-all")).andExpect(status().isNotFound());
    }
    private void staffPays(String token, String customer, String invoiceId, int amount) throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId": "%s", "invoiceId": "%s", "amount": %d, "method": "BANK_TRANSFER",
                                 "paymentDate": "%s"}
                                """.formatted(customer, invoiceId, amount, LocalDate.now())))
                .andExpect(status().isCreated());
    }

    private String[] openCheckout(String token, String invoiceId) throws Exception {
        String url = objectMapper.readTree(mockMvc.perform(post("/invoices/" + invoiceId + "/payment-link")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString()).get("url").asText();
        String payToken = url.substring(url.lastIndexOf('/') + 1);
        String checkoutUrl = objectMapper.readTree(mockMvc.perform(post("/public/pay/" + payToken + "/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("url").asText();
        return new String[]{payToken, checkoutUrl.substring(checkoutUrl.indexOf("mock=") + 5)};
    }

    @Test
    void aPaymentRecordedByStaffDuringCheckoutIsNotChargedTwice() throws Exception {
        String token = signUpAndLogin();
        provider(token, "MOCK", 200);
        String customer = partner(token, "CUSTOMER", "Dar Salam");

        // Paid in full by transfer while the customer is on the card page: the link closes, nothing more is recorded.
        String full = invoice(token, "CUSTOMER", customer, "SENT", 600);
        String[] a = openCheckout(token, full);
        staffPays(token, customer, full, 1200);
        mockMvc.perform(get("/public/pay/" + a[0])).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/public/pay/" + a[0] + "/mock-complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\": \"" + a[1] + "\"}"));
        mockMvc.perform(get("/invoices/" + full).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amountPaid").value(1200.0));
        mockMvc.perform(get("/payments/invoice/" + full).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        // Part paid in between: the test gateway records only what is still owed.
        String part = invoice(token, "CUSTOMER", customer, "SENT", 600);
        String[] b = openCheckout(token, part);
        staffPays(token, customer, part, 500);
        mockMvc.perform(post("/public/pay/" + b[0] + "/mock-complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\": \"" + b[1] + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/invoices/" + part).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amountPaid").value(1200.0));
    }
}
