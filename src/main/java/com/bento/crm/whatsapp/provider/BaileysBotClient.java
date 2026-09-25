package com.bento.crm.whatsapp.provider;

import com.bento.crm.whatsapp.config.BaileysProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for the Baileys bot (whatsapp-bot on the internal Docker network). Short connect
 * timeout so a stopped bot fails fast; a longer read timeout because a send may first look the
 * number up on WhatsApp.
 */
@Component
public class BaileysBotClient {

    private final BaileysProperties properties;
    private final ObjectMapper objectMapper;
    private volatile RestClient client;

    public BaileysBotClient(BaileysProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** A bot session as the bot reports it. */
    public record SessionStatus(String id, String state, boolean linked, String phoneNumber, String pairingCode,
                                String pairingExpiresAt, Integer pairingAttempt, Integer maxPairingAttempts,
                                String error) {
    }

    public record SendResponse(String wamid, String jid, String timestamp) {
    }

    /** A non-2xx answer from the bot, with its error code and whether a retry could succeed. */
    public static class BotException extends RuntimeException {
        private final int status;
        private final String code;
        private final boolean retryable;

        public BotException(int status, String code, String message, boolean retryable) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryable = retryable;
        }

        public int status() { return status; }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    public SessionStatus start(UUID sessionId, String phoneNumber) {
        Map<String, Object> body = new HashMap<>();
        if (phoneNumber != null) {
            body.put("phoneNumber", phoneNumber.replaceAll("\\D", ""));
        }
        return call(() -> client().post().uri("/sessions/{id}/start", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(SessionStatus.class));
    }

    public SessionStatus stop(UUID sessionId) {
        return call(() -> client().post().uri("/sessions/{id}/stop", sessionId).retrieve().body(SessionStatus.class));
    }

    public SessionStatus logout(UUID sessionId) {
        return call(() -> client().post().uri("/sessions/{id}/logout", sessionId).retrieve().body(SessionStatus.class));
    }

    public SessionStatus status(UUID sessionId) {
        return call(() -> client().get().uri("/sessions/{id}", sessionId).retrieve().body(SessionStatus.class));
    }

    public List<SessionStatus> list() {
        return call(() -> List.of(client().get().uri("/sessions").retrieve().body(SessionStatus[].class)));
    }

    public SendResponse sendText(UUID sessionId, String messageId, String toE164, String text) {
        Map<String, Object> body = Map.of("messageId", messageId, "to", toE164, "text", text);
        return call(() -> client().post().uri("/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(SendResponse.class));
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        if (!properties.isConfigured()) {
            throw new BotException(0, "BOT_NOT_CONFIGURED", "The WhatsApp bot is not configured on this server", false);
        }
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String code = "HTTP_" + e.getStatusCode().value();
            String message = e.getResponseBodyAsString();
            boolean retryable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
            try {
                JsonNode json = objectMapper.readTree(e.getResponseBodyAsString());
                code = json.path("code").asText(code);
                message = json.path("message").asText(message);
                if (json.has("retryable")) {
                    retryable = json.path("retryable").asBoolean();
                }
            } catch (Exception ignored) {
                // Not JSON: keep the HTTP status as the code.
            }
            throw new BotException(e.getStatusCode().value(), code, message, retryable);
        } catch (BotException e) {
            throw e;
        } catch (Exception e) {
            // Unreachable bot or timeout: the outcome of a send is unknown, and the bot
            // deduplicates on messageId, so a retry is always safe.
            throw new BotException(0, "TRANSPORT", e.getMessage(), true);
        }
    }

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(30));
            c = RestClient.builder()
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(factory)
                    .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                    .build();
            client = c;
        }
        return c;
    }
}
