package com.bento.crm.expense;

import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExpenseTest extends IntegrationTestBase {

    private String create(String token, String date, String category, int amount) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/expenses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate": "%s", "category": "%s", "description": "Test", "amount": %d,
                                 "vatAmount": 20, "paymentMethod": "CARD"}""".formatted(date, category, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void expensesAreRecordedFilteredAndTotalledByCategory() throws Exception {
        String token = signUpAndLogin();
        create(token, "2026-10-01", "fuel", 500);
        String rent = create(token, "2026-10-05", "RENT", 6000);
        create(token, "2026-09-20", "FUEL", 300);

        mockMvc.perform(get("/expenses?from=2026-10-01&to=2026-10-31").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].category").value("RENT"));
        mockMvc.perform(get("/expenses/summary?from=2026-10-01&to=2026-10-31").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.total").value(6500.0))
                .andExpect(jsonPath("$.byCategory[0].category").value("RENT"))
                .andExpect(jsonPath("$.byCategory[1].category").value("FUEL"));

        mockMvc.perform(put("/expenses/" + rent).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate": "2026-10-05", "category": "RENT", "amount": 10, "vatAmount": 20,
                                 "paymentMethod": "BANK_TRANSFER"}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/expenses/" + rent).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/expenses/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.total").value(800.0))
                .andExpect(jsonPath("$.count").value(2));
    }
}
