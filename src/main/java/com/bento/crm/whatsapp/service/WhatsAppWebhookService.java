package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.ingest.StatusUpdate;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses Meta's inbound webhook (replies, delivery receipts) into the provider-neutral records
 * and hands each one to {@link WaIngestService}.
 *
 * <p>Tenancy is the subtle part. The callback arrives unauthenticated and carries no
 * organization id — only {@code metadata.phone_number_id}. The tenant is resolved from
 * {@link WaAccount} and passed explicitly, because the usual {@code TenantContext} ThreadLocal
 * is never populated on this request.
 *
 * <p>Every item is ingested through the {@link WaIngestService} proxy, so each one runs in its
 * own transaction (calling a {@code @Transactional} method on {@code this} would bypass it).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppWebhookService {

    private final WaAccountRepository accountRepository;
    private final WaIngestService ingestService;

    /**
     * Processes one webhook body. Meta batches several entries and several changes per delivery,
     * so this flattens before dispatching.
     */
    public void handle(JsonNode payload) {
        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);

                Optional<WaAccount> account = phoneNumberId == null
                        ? Optional.empty()
                        : accountRepository.findByPhoneNumberId(phoneNumberId);

                if (account.isEmpty()) {
                    log.warn("[wa-hook] no account for phone_number_id={}, ignoring", phoneNumberId);
                    continue;
                }

                UUID orgId = account.get().getOrganizationId();
                value.path("messages").forEach(m -> safely(() -> ingestService.ingest(orgId, toInbound(m))));
                value.path("statuses").forEach(s -> safely(() -> ingestService.applyStatus(orgId, toStatus(s))));
            }
        }
    }

    /**
     * One malformed item must not abort the rest of the batch — Meta would retry the whole
     * delivery and the good items would be reprocessed.
     */
    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[wa-hook] failed to process webhook item", e);
        }
    }

    InboundMessage toInbound(JsonNode message) {
        String digits = message.path("from").asText("").replaceAll("\\D", "");
        String type = message.path("type").asText("text");
        return new InboundMessage(
                message.path("id").asText(null),
                WaMessage.Direction.IN,
                InboundMessage.Origin.LIVE,
                digits.isEmpty() ? null : "+" + digits,
                null,
                null,
                null,
                type,
                extractBody(message, type),
                null,
                null,
                null,
                message.path("context").path("id").asText(null),
                parseTimestamp(message.path("timestamp").asText(null)));
    }

    StatusUpdate toStatus(JsonNode status) {
        WaMessage.Status mapped = switch (status.path("status").asText("")) {
            case "sent" -> WaMessage.Status.SENT;
            case "delivered" -> WaMessage.Status.DELIVERED;
            case "read" -> WaMessage.Status.READ;
            case "failed" -> WaMessage.Status.FAILED;
            default -> null;
        };
        JsonNode error = status.path("errors").path(0);
        return new StatusUpdate(
                status.path("id").asText(null),
                mapped,
                error.path("code").asText(null),
                error.path("title").asText(null),
                parseTimestamp(status.path("timestamp").asText(null)));
    }

    /** Extracts displayable text across the message types a campaign realistically sees. */
    private String extractBody(JsonNode message, String type) {
        return switch (type) {
            case "text" -> message.path("text").path("body").asText(null);
            // Quick-reply and call-to-action button taps arrive as their own types. Handling them
            // matters because "did they respond?" is far more reliable to detect from a button
            // tap than from parsing free text.
            case "button" -> message.path("button").path("text").asText(null);
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                yield interactive.path("button_reply").path("title").asText(
                        interactive.path("list_reply").path("title").asText(null));
            }
            case "image", "video", "document", "audio" ->
                    message.path(type).path("caption").asText("[" + type + "]");
            case "location" -> "[location]";
            case "sticker" -> "[sticker]";
            // Default case is not optional: without it an unhandled type stores null and the CRM
            // timeline renders an empty bubble.
            default -> "[" + type + "]";
        };
    }

    private Instant parseTimestamp(String epochSeconds) {
        if (epochSeconds == null || epochSeconds.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (NumberFormatException e) {
            return Instant.now();
        }
    }
}
