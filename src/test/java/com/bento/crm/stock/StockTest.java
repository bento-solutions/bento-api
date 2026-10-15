package com.bento.crm.stock;

import com.bento.crm.stock.model.StockSettings;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockTest extends IntegrationTestBase {

    private JsonNode send(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                          String body, int expected) throws Exception {
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String r = mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return r.isEmpty() ? null : objectMapper.readTree(r);
    }

    private String id(JsonNode n) {
        return n.get("id").asText();
    }

    private double level(String token, String product, String warehouse) throws Exception {
        JsonNode rows = send(token, get("/stock/levels"), null, 200);
        for (JsonNode r : rows) {
            if (r.get("productId").asText().equals(product) && r.get("warehouseId").asText().equals(warehouse)) {
                return r.get("quantity").asDouble();
            }
        }
        return Double.NaN;
    }

    @Test
    void goodsComeInFromDeliveredOrdersAndGoOutWithInvoices() throws Exception {
        String token = signUpAndLogin();
        String screen = id(send(token, post("/products"), """
                {"sku": "ECR-24", "name": "Écran 24 pouces", "unitPrice": 1200, "costPrice": 800,
                 "trackStock": true, "reorderLevel": 5}""", 201));
        send(token, post("/products"), """
                {"sku": "INST", "name": "Installation", "unitPrice": 300}""", 201);
        JsonNode warehouses = send(token, get("/stock/warehouses"), null, 200);
        assertThat(warehouses.size()).isEqualTo(1);
        String main = id(warehouses.get(0));
        send(token, get("/stock/settings"), null, 200);

        String vendor = id(send(token, post("/partners"), "{\"type\": \"VENDOR\", \"name\": \"Maroc Hardware\"}", 201));
        String customer = id(send(token, post("/partners"), "{\"type\": \"CUSTOMER\", \"name\": \"Riad Bleu\"}", 201));
        String deal = id(send(token, post("/deals"), "{\"partnerId\": \"" + customer + "\", \"title\": \"Écrans\", \"stage\": \"OPEN\"}", 201));

        // A delivered PO adds its tracked lines once, however often it is saved as delivered.
        String poBody = """
                {"dealId": "%s", "vendorPartnerId": "%s", "status": "DELIVERED",
                 "lines": [{"product": "Écran 24 pouces", "qty": 10, "cost": 790}, {"product": "Installation", "qty": 1, "cost": 100}]}
                """.formatted(deal, vendor);
        String po = id(send(token, post("/purchase-orders"), poBody, 201));
        send(token, patch("/purchase-orders/" + po), poBody, 200);
        assertThat(level(token, screen, main)).isEqualTo(10.0);

        // A draft invoice moves nothing; a sent one takes its goods out.
        String invoiceBody = """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "%s", "dueDate": "%s",
                 "lines": [{"item": "ECR-24", "description": "Écran", "qty": 6, "unitPrice": 1200}]}
                """;
        send(token, post("/invoices"), invoiceBody.formatted(customer, "DRAFT", LocalDate.now().plusDays(30)), 201);
        assertThat(level(token, screen, main)).isEqualTo(10.0);
        send(token, post("/invoices"), invoiceBody.formatted(customer, "SENT", LocalDate.now().plusDays(30)), 201);
        assertThat(level(token, screen, main)).isEqualTo(4.0);
        mockMvc.perform(get("/stock/levels?lowOnly=true").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].low").value(true));

        // Transfer, then count: the count turns the difference into an adjustment.
        String depot = id(send(token, post("/stock/warehouses"), "{\"name\": \"Dépôt Tanger\"}", 201));
        send(token, post("/stock/transfers"), """
                {"productId": "%s", "fromWarehouseId": "%s", "toWarehouseId": "%s", "quantity": 1}
                """.formatted(screen, main, depot), 201);
        assertThat(level(token, screen, main)).isEqualTo(3.0);
        assertThat(level(token, screen, depot)).isEqualTo(1.0);
        mockMvc.perform(post("/stock/counts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"" + main + "\", \"lines\": [{\"productId\": \"" + screen + "\", \"countedQuantity\": 2}]}"))
                .andExpect(jsonPath("$.adjusted").value(1))
                .andExpect(jsonPath("$.netChange").value(-1.0));

        mockMvc.perform(get("/stock/valuation").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalUnits").value(3.0))
                .andExpect(jsonPath("$.totalValue").value(2400.0))
                .andExpect(jsonPath("$.products").value(1));
        mockMvc.perform(get("/stock/movements?productId=" + screen).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[4].type").value("RECEIPT"))
                .andExpect(jsonPath("$.content[4].unitCost").value(790.0));

        // Untracked products cannot be moved by hand.
        mockMvc.perform(post("/stock/receipts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": \"" + id(send(token, post("/products"), "{\"sku\": \"X\", \"name\": \"Conseil\", \"unitPrice\": 1}", 201)) + "\", \"quantity\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withDealTriggerStockLeavesWhenTheDealIsConfirmed() throws Exception {
        String token = signUpAndLogin();
        send(token, put("/stock/settings"), "{\"issueTrigger\": \"DEAL_WON\"}", 200);
        String product = id(send(token, post("/products"), """
                {"sku": "MOTO-1", "name": "Scooter 125", "unitPrice": 15000, "costPrice": 11000, "trackStock": true}""", 201));
        send(token, post("/stock/receipts"), "{\"productId\": \"" + product + "\", \"quantity\": 4}", 201);
        String main = id(send(token, get("/stock/warehouses"), null, 200).get(0));
        String customer = id(send(token, post("/partners"), "{\"type\": \"CUSTOMER\", \"name\": \"Café Atlas\"}", 201));

        String dealBody = """
                {"partnerId": "%s", "title": "Scooters", "stage": "%s",
                 "orderLines": [{"product": "Scooter 125", "qty": 3, "unitPrice": 15000}]}
                """;
        String deal = id(send(token, post("/deals"), dealBody.formatted(customer, "OPEN"), 201));
        assertThat(level(token, product, main)).isEqualTo(4.0);
        send(token, patch("/deals/" + deal), dealBody.formatted(customer, "PO_SENT"), 200);
        send(token, patch("/deals/" + deal), dealBody.formatted(customer, "AWAITING_DELIVERY"), 200);
        assertThat(level(token, product, main)).isEqualTo(1.0);

        // The invoice for the same goods does not take them out a second time.
        send(token, post("/invoices"), """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"item": "Scooter 125", "qty": 3, "unitPrice": 15000}]}
                """.formatted(customer, LocalDate.now().plusDays(10)), 201);
        assertThat(level(token, product, main)).isEqualTo(1.0);
    }

    @Test
    void cancelledSalesGiveTheirStockBackAndManualMovesCannotGoNegative() throws Exception {
        String token = signUpAndLogin();
        String product = id(send(token, post("/products"), """
                {"sku": "LAMP-1", "name": "Lampe berbère", "unitPrice": 250, "trackStock": true}""", 201));
        String plain = id(send(token, post("/products"), """
                {"sku": "TAP-1", "name": "Tapis", "unitPrice": 900, "trackStock": true}""", 201));
        send(token, post("/stock/receipts"), "{\"productId\": \"" + product + "\", \"quantity\": 10}", 201);
        String main = id(send(token, get("/stock/warehouses"), null, 200).get(0));
        String customer = id(send(token, post("/partners"), "{\"type\": \"CUSTOMER\", \"name\": \"Riad Zitoun\"}", 201));

        // A line that only carries a description still finds its product.
        String invoiceBody = """
                {"type": "CUSTOMER", "partnerId": "%s", "status": "SENT", "dueDate": "%s",
                 "lines": [{"description": "lampe berbère", "qty": 4, "unitPrice": 250}]}
                """.formatted(customer, LocalDate.now().plusDays(30));
        String invoice = id(send(token, post("/invoices"), invoiceBody, 201));
        assertThat(level(token, product, main)).isEqualTo(6.0);

        // Saving it again after it was sent moves nothing.
        send(token, patch("/invoices/" + invoice), invoiceBody, 200);
        assertThat(level(token, product, main)).isEqualTo(6.0);
        // Changing the quantity afterwards moves only the difference, both ways.
        send(token, patch("/invoices/" + invoice), invoiceBody.replace("\"qty\": 4", "\"qty\": 7"), 200);
        assertThat(level(token, product, main)).isEqualTo(3.0);
        send(token, patch("/invoices/" + invoice), invoiceBody.replace("\"qty\": 4", "\"qty\": 5"), 200);
        assertThat(level(token, product, main)).isEqualTo(5.0);

        // Deleting the sent invoice puts the goods back; restoring it takes them out again.
        send(token, delete("/invoices/" + invoice), null, 204);
        assertThat(level(token, product, main)).isEqualTo(10.0);
        send(token, post("/invoices/" + invoice + "/restore"), null, 200);
        assertThat(level(token, product, main)).isEqualTo(5.0);

        // A manual issue or transfer above what is on hand is refused.
        send(token, post("/stock/issues"), "{\"productId\": \"" + product + "\", \"quantity\": 6}", 400);
        String depot = id(send(token, post("/stock/warehouses"), "{\"name\": \"Dépôt\"}", 201));
        send(token, post("/stock/transfers"), """
                {"productId": "%s", "fromWarehouseId": "%s", "toWarehouseId": "%s", "quantity": 6}
                """.formatted(product, main, depot), 400);
        assertThat(level(token, product, main)).isEqualTo(5.0);

        // A tracked product with no moves shows at zero, is not low without a reorder level,
        // and stock with no cost price is valued at its sale price.
        assertThat(level(token, plain, main)).isEqualTo(0.0);
        mockMvc.perform(get("/stock/levels?lowOnly=true").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/stock/valuation").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalUnits").value(5.0))
                .andExpect(jsonPath("$.totalValue").value(1250.0));
    }

    @Test
    void aLostDealGivesItsStockBack() throws Exception {
        String token = signUpAndLogin();
        send(token, put("/stock/settings"), "{\"issueTrigger\": \"DEAL_WON\"}", 200);
        String product = id(send(token, post("/products"), """
                {"sku": "VEL-1", "name": "Vélo", "unitPrice": 3000, "costPrice": 2000, "trackStock": true}""", 201));
        send(token, post("/stock/receipts"), "{\"productId\": \"" + product + "\", \"quantity\": 5}", 201);
        String main = id(send(token, get("/stock/warehouses"), null, 200).get(0));
        String customer = id(send(token, post("/partners"), "{\"type\": \"CUSTOMER\", \"name\": \"Club Atlas\"}", 201));
        String dealBody = """
                {"partnerId": "%s", "title": "Vélos", "stage": "%s",
                 "orderLines": [{"product": "Vélo", "qty": 2, "unitPrice": 3000}]}
                """;
        String deal = id(send(token, post("/deals"), dealBody.formatted(customer, "CLOSED_WON"), 201));
        assertThat(level(token, product, main)).isEqualTo(3.0);
        // Moving between won stages does not take the goods out again.
        send(token, patch("/deals/" + deal), dealBody.formatted(customer, "INVOICED"), 200);
        assertThat(level(token, product, main)).isEqualTo(3.0);
        // Editing the order lines of a won deal moves only the difference.
        send(token, patch("/deals/" + deal), dealBody.formatted(customer, "INVOICED").replace("\"qty\": 2", "\"qty\": 4"), 200);
        assertThat(level(token, product, main)).isEqualTo(1.0);
        send(token, patch("/deals/" + deal), dealBody.formatted(customer, "CLOSED_LOST"), 200);
        assertThat(level(token, product, main)).isEqualTo(5.0);
    }

    @Test
    void theDefaultTriggerFollowsTheIndustry() {
        assertThat(StockSettings.defaultFor("Retail")).isEqualTo(StockSettings.IssueTrigger.INVOICE_SENT);
        assertThat(StockSettings.defaultFor("Logistics")).isEqualTo(StockSettings.IssueTrigger.DEAL_WON);
        assertThat(StockSettings.defaultFor("Consulting")).isEqualTo(StockSettings.IssueTrigger.MANUAL);
        assertThat(StockSettings.defaultFor(null)).isEqualTo(StockSettings.IssueTrigger.INVOICE_SENT);
    }
}
