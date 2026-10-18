package com.bento.crm.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The one door to Claude, through the official Anthropic SDK. With no API key every AI feature
 * stays off: documents wait for a person, and the assistant says it is not set up.
 */
@Component
public class ClaudeClient {

    /** Server-side fallback on a safety decline: the API re-runs the request on a suitable model. */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AiProperties props;
    private volatile AnthropicClient client;

    public ClaudeClient(AiProperties props) {
        this.props = props;
    }

    public boolean configured() {
        return props.isConfigured();
    }

    /** Sends one request; adds the refusal fallback. Throws {@link AiException} on any failure. */
    public Message create(MessageCreateParams.Builder params) {
        if (!configured()) {
            throw new AiException("AI is not set up on this server", null);
        }
        params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        try {
            Message m = client().messages().create(params.build());
            if (m.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                throw new AiRefusedException();
            }
            return m;
        } catch (AnthropicException e) {
            throw new AiException("The AI service could not be reached: " + e.getMessage(), e);
        }
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder()
                            .apiKey(props.getAnthropic().getApiKey())
                            .baseUrl(props.getAnthropic().getBaseUrl())
                            .timeout(Duration.ofSeconds(props.getAnthropic().getTimeoutSeconds()))
                            .maxRetries(2)
                            .build();
                }
                c = client;
            }
        }
        return c;
    }

    /** The model declined (after the server-side fallback): retrying the same input will not help. */
    public static class AiRefusedException extends AiException {
        public AiRefusedException() {
            super("The model declined this request", null);
        }
    }

    /** The AI could not answer: not configured, unreachable, rate limited or declined. */
    public static class AiException extends RuntimeException {
        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
