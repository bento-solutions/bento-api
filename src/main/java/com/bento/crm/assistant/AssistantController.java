package com.bento.crm.assistant;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
@Tag(name = "AI assistant")
public class AssistantController {

    private final AssistantService assistant;
    private final com.bento.crm.ai.ClaudeClient claude;

    public record ChatRequest(List<AssistantService.ChatMessage> messages) {}

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> status() {
        return Map.of("enabled", claude.configured());
    }

    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public AssistantService.ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("Ask a question");
        }
        return assistant.chat(request.messages());
    }
}
