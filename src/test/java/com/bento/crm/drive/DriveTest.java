package com.bento.crm.drive;

import com.bento.crm.common.model.UserRole;
import com.bento.crm.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriveTest extends IntegrationTestBase {

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    @Test
    void foldersTagsSearchAndExpiringShareLinks() throws Exception {
        String token = signUpAndLogin();
        String auth = "Bearer " + token;

        String folderId = json(mockMvc.perform(post("/drive/folders").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Contrats\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/drive/folders").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"contrats\"}"))
                .andExpect(status().isBadRequest());

        MockMultipartFile pdf = new MockMultipartFile("file", "Contrat bail 2026.pdf", "application/pdf", "%PDF-1.4 bail".getBytes());
        String fileId = json(mockMvc.perform(multipart("/drive/files").file(pdf).header("Authorization", auth)
                        .param("folderId", folderId).param("tags", "Bail", "juridique"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags[0]").value("bail"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(multipart("/drive/files").file(new MockMultipartFile("file", "x.html", "text/html", "<script>".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // The folder lists the file; the root lists the folder, not the file.
        mockMvc.perform(get("/drive?folderId=" + folderId).header("Authorization", auth))
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.path[0].name").value("Contrats"));
        mockMvc.perform(get("/drive").header("Authorization", auth))
                .andExpect(jsonPath("$.folders[0].name").value("Contrats"))
                .andExpect(jsonPath("$.files.length()").value(0));
        mockMvc.perform(get("/drive?q=BAIL").header("Authorization", auth))
                .andExpect(jsonPath("$.files[0].id").value(fileId));
        mockMvc.perform(get("/drive?tag=juridique").header("Authorization", auth))
                .andExpect(jsonPath("$.totalFiles").value(1));
        mockMvc.perform(get("/drive/tags").header("Authorization", auth))
                .andExpect(jsonPath("$[0]").value("bail"));

        mockMvc.perform(put("/drive/files/" + fileId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"moveToRoot\": true, \"tags\": [\"archive\"]}"))
                .andExpect(jsonPath("$.folderId").doesNotExist())
                .andExpect(jsonPath("$.tags[0]").value("archive"));
        mockMvc.perform(delete("/drive/folders/" + folderId).header("Authorization", auth))
                .andExpect(status().isNoContent());

        // A share link works without login until it is revoked.
        JsonNode share = json(mockMvc.perform(post("/drive/files/" + fileId + "/shares").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\": 3}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String linkToken = share.get("url").asText().replaceAll(".*/f/", "");
        mockMvc.perform(get("/public/files/" + linkToken))
                .andExpect(jsonPath("$.fileName").value("Contrat bail 2026.pdf"));
        mockMvc.perform(get("/public/files/" + linkToken + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(content().bytes("%PDF-1.4 bail".getBytes()));
        mockMvc.perform(delete("/drive/shares/" + share.get("id").asText()).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/public/files/" + linkToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/public/files/" + "x".repeat(32))).andExpect(status().isNotFound());
    }

    @Test
    void financialFoldersAreHiddenFromUsersWithoutTheAuthority() throws Exception {
        String admin = signUpAndLogin();
        UUID orgId = orgIdOf(admin);
        String sales = tokenForNewUser(orgId, UserRole.SALESPERSON);

        // Upload into the vendor-invoice folder the system creates.
        String folders = mockMvc.perform(get("/drive").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(folders).doesNotContain("Factures fournisseurs");
        UUID vendorFolder = driveService.systemFolder(orgId, "VENDOR_INVOICES", java.time.YearMonth.of(2026, 10)).getId();

        String fileId = json(mockMvc.perform(multipart("/drive/files")
                        .file(new MockMultipartFile("file", "facture.pdf", "application/pdf", "%PDF facture".getBytes()))
                        .header("Authorization", "Bearer " + admin).param("folderId", vendorFolder.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/drive?q=facture").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.totalFiles").value(1));
        mockMvc.perform(get("/drive?q=facture").header("Authorization", "Bearer " + sales))
                .andExpect(jsonPath("$.totalFiles").value(0));
        mockMvc.perform(get("/drive/files/" + fileId + "/content").header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/drive?folderId=" + vendorFolder).header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
        // The older /files download checks the same rule.
        mockMvc.perform(get("/files/" + fileId).header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/files/" + fileId).header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/" + fileId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        // System folders take no subfolders, so nothing can be filed outside their rules.
        mockMvc.perform(post("/drive/folders").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Divers\", \"parentId\": \"" + vendorFolder + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/drive").header("Authorization", "Bearer " + sales))
                .andExpect(jsonPath("$.folders.length()").value(0));
        mockMvc.perform(get("/drive").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.folders[0].name").value("Factures fournisseurs"))
                .andExpect(jsonPath("$.folders[0].locked").value(true));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.bento.crm.drive.service.DriveService driveService;
}
