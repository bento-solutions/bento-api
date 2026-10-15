package com.bento.crm.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.ai}: the LLM used to read documents and to answer in the assistant. Claude is the
 * default; with no API key the features stay off and documents wait for a person to review them.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private Anthropic anthropic = new Anthropic();

    /** Model that reads invoices and statements (vision). */
    private String documentModel = "claude-opus-5-5";

    /** Model behind the assistant. */
    private String assistantModel = "claude-opus-5-5";

    /** Below this confidence a document goes to review instead of being booked. */
    private double autoBookConfidence = 0.85;

    @Data
    public static class Anthropic {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private int timeoutSeconds = 120;
    }

    public boolean isConfigured() {
        return anthropic.getApiKey() != null && !anthropic.getApiKey().isBlank();
    }
}
