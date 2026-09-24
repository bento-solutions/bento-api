package com.bento.crm.file;

import com.bento.crm.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileControllerTest extends IntegrationTestBase {

    @Test
    void uploadLogoAndPublicView_succeedsWithoutAuth() throws Exception {
        String email = "logo-test-" + System.nanoTime() + "@example.com";
        String password = "Password123!";

        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Logo Test Org",
                                  "admin_email": "%s",
                                  "admin_name": "Logo Admin",
                                  "admin_password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(loginResponse, "$.access_token");

        byte[] fakePng = new byte[]{1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "company-logo.png",
                "image/png",
                fakePng
        );

        String uploadResponse = mockMvc.perform(multipart("/organizations/me/logo")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logo_url").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String logoUrl = JsonPath.read(uploadResponse, "$.logo_url");
        String fileId = logoUrl.substring(logoUrl.lastIndexOf('/') + 1);

        // Unauthenticated access to the public logo must succeed
        mockMvc.perform(get("/files/public/" + fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(content().bytes(fakePng));
    }
}
