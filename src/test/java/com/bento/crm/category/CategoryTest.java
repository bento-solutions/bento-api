package com.bento.crm.category;

import com.bento.crm.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Categories are single-word coloured labels on tickets and tasks. Covers their CRUD and the
 * rule that a task on a ticket always carries the ticket's category.
 */
class CategoryTest extends IntegrationTestBase {

    private String auth(String token) {
        return "Bearer " + token;
    }

    private String currentUserId(String token) throws Exception {
        String response = mockMvc.perform(get("/auth/me").header("Authorization", auth(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createCategory(String token, String name, String color) throws Exception {
        String response = mockMvc.perform(post("/categories")
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"color\": \"%s\"}".formatted(name, color)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createTicket(String token, String categoryId) throws Exception {
        String category = categoryId == null ? "" : ", \"categoryId\": \"%s\"".formatted(categoryId);
        String response = mockMvc.perform(post("/tickets")
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Portal login broken\", \"status\": \"OPEN\", \"priority\": \"LOW\"%s}".formatted(category)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createTask(String token, String body) throws Exception {
        String response = mockMvc.perform(post("/tasks")
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String taskBody(String userId, String extra) {
        return "{\"title\": \"Reproduce\", \"status\": \"TODO\", \"assignedByUserId\": \"%s\"%s}".formatted(userId, extra);
    }

    @Test
    void categoryCrud_createListRenameRecolourDelete() throws Exception {
        String token = signUpAndLogin();
        String id = createCategory(token, "CRMbento", "blue");

        mockMvc.perform(get("/categories").header("Authorization", auth(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("CRMbento"))
                .andExpect(jsonPath("$[0].color").value("blue"))
                .andExpect(jsonPath("$[0].ticketCount").value(0));

        mockMvc.perform(patch("/categories/" + id)
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Bento\", \"color\": \"violet\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bento"))
                .andExpect(jsonPath("$.color").value("violet"));

        mockMvc.perform(delete("/categories/" + id).header("Authorization", auth(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/categories").header("Authorization", auth(token)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void category_mustBeASingleWordWithKnownColourAndUniqueName() throws Exception {
        String token = signUpAndLogin();
        createCategory(token, "Orthoflow", "emerald");

        // More than one word.
        mockMvc.perform(post("/categories").header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ortho flow\", \"color\": \"blue\"}"))
                .andExpect(status().isBadRequest());
        // Too long.
        mockMvc.perform(post("/categories").header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Supercalifragilisticexpialidocious\", \"color\": \"blue\"}"))
                .andExpect(status().isBadRequest());
        // Colour outside the palette.
        mockMvc.perform(post("/categories").header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Plain\", \"color\": \"#ff0000\"}"))
                .andExpect(status().isBadRequest());
        // Same name, different case.
        mockMvc.perform(post("/categories").header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"ORTHOFLOW\", \"color\": \"rose\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ticketTasks_inheritTheTicketsCategory() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);
        String bento = createCategory(token, "CRMbento", "blue");
        String ortho = createCategory(token, "Orthoflow", "emerald");
        String ticketId = createTicket(token, bento);

        // Raised through the ticket: takes the ticket's category.
        String subTask = objectMapper.readTree(mockMvc.perform(post("/tickets/" + ticketId + "/tasks")
                        .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Reproduce on staging\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(bento))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // Raised through /tasks with a different category: the ticket's wins.
        String linkedTask = createTask(token, taskBody(userId,
                ", \"categoryId\": \"%s\", \"relatedEntityType\": \"TICKET\", \"relatedEntityId\": \"%s\"".formatted(ortho, ticketId)));
        mockMvc.perform(get("/tasks/" + linkedTask).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").value(bento));

        // Re-categorising the ticket moves all of its tasks along.
        mockMvc.perform(patch("/tickets/" + ticketId).header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Portal login broken\", \"status\": \"OPEN\", \"categoryId\": \"%s\"}".formatted(ortho)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(ortho));
        mockMvc.perform(get("/tasks/" + subTask).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").value(ortho));
        mockMvc.perform(get("/tasks/" + linkedTask).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").value(ortho));

        // Clearing it clears them too.
        mockMvc.perform(patch("/tickets/" + ticketId).header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Portal login broken\", \"status\": \"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").doesNotExist());
        mockMvc.perform(get("/tasks/" + subTask).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").doesNotExist());
    }

    @Test
    void standaloneTask_keepsItsOwnCategory_andTakesTheTicketsOnceAttached() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);
        String bento = createCategory(token, "CRMbento", "blue");
        String ortho = createCategory(token, "Orthoflow", "emerald");
        String ticketId = createTicket(token, bento);

        String taskId = createTask(token, taskBody(userId, ", \"categoryId\": \"%s\"".formatted(ortho)));
        mockMvc.perform(get("/tasks/" + taskId).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").value(ortho));

        // Attaching it to a categorised ticket replaces its own.
        mockMvc.perform(patch("/tasks/" + taskId).header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(userId, ", \"categoryId\": \"%s\", \"relatedEntityType\": \"TICKET\", \"relatedEntityId\": \"%s\""
                                .formatted(ortho, ticketId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(bento));
    }

    @Test
    void deletingACategory_takesItOffTicketsAndTasks() throws Exception {
        String token = signUpAndLogin();
        String userId = currentUserId(token);
        String bento = createCategory(token, "CRMbento", "blue");
        String ticketId = createTicket(token, bento);
        String taskId = createTask(token, taskBody(userId, ", \"categoryId\": \"%s\"".formatted(bento)));

        mockMvc.perform(get("/categories").header("Authorization", auth(token)))
                .andExpect(jsonPath("$[0].ticketCount").value(1))
                .andExpect(jsonPath("$[0].taskCount").value(1));

        mockMvc.perform(delete("/categories/" + bento).header("Authorization", auth(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/tickets/" + ticketId).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").doesNotExist());
        mockMvc.perform(get("/tasks/" + taskId).header("Authorization", auth(token)))
                .andExpect(jsonPath("$.categoryId").doesNotExist());
    }

    @Test
    void unknownCategory_isRejected() throws Exception {
        String token = signUpAndLogin();
        mockMvc.perform(post("/tickets").header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"X\", \"status\": \"OPEN\", \"categoryId\": \"00000000-0000-0000-0000-000000000000\"}"))
                .andExpect(status().isNotFound());
    }
}
