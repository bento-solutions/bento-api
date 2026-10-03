package com.bento.crm.identity;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserDirectoryTest extends IntegrationTestBase {

    @Test
    void salesperson_cannotListUsers_butCanReadTheDirectoryToNameLeadOwners() throws Exception {
        String adminToken = signUpAndLogin();
        UUID orgId = orgIdOf(adminToken);
        String salespersonToken = tokenForNewUser(orgId, UserRole.SALESPERSON);

        mockMvc.perform(get("/users").header("Authorization", "Bearer " + salespersonToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/users/directory").header("Authorization", "Bearer " + salespersonToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].display_name").exists())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andExpect(jsonPath("$[0].phone").doesNotExist());
    }

    @Test
    void directory_isScopedToTheCallersOrganization() throws Exception {
        String adminToken = signUpAndLogin();
        signUpAndLogin();

        mockMvc.perform(get("/users/directory").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
