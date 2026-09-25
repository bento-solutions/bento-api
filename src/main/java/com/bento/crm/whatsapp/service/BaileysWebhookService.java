package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.ingest.StatusUpdate;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a batch of events from the Baileys bot. Each event is handled on its own (its own
 * transactions) and reported back as processed or failed, so the bot retries only what failed
 * and one bad event cannot hold the rest of its spool hostage.
 *
 * <p>Delivery is at-least-once; every path is idempotent (messages dedupe on wamid, receipts only
 * move forward, session changes are ordered by seq).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BaileysWebhookService {

    private final WaAccountRepository accountRepository;
    private final WaLeadResolver leadResolver;
    private final WaIngestService ingestService;
    private final WaSessionService sessionService;

    public record Result(List<Long> processed, List<Map<String, Object>> failed) {
    }

    public Result process(JsonNode payload) {
        List<Long> processed = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (JsonNode event : payload.path("events")) {
            long id = event.path("id").asLong();
            try {
                handle(event);
                processed.add(id);
            } catch (Exception e) {
                log.error("[baileys-hook] event {} ({}) failed", id, event.path("type").asText(), e);
                failed.add(Map.of("id", id, "error", String.valueOf(e.getMessage())));
            }
        }
        return new Result(processed, failed);
    }

    private void handle(JsonNode event) {
        String type = event.path("type").asText("");
        JsonNode data = event.path("data");
        Optional<WaAccount> account = account(event.path("sessionId").asText(null));
        if (account.isEmpty()) {
            // A session whose account is gone: acknowledge so the bot stops retrying it.
            log.warn("[baileys-hook] {} for unknown session {}, dropping", type, event.path("sessionId").asText());
            return;
        }
        switch (type) {
            case "message.upsert" -> ingest(account.get(), toInbound(data));
            case "message.status" -> ingestService.applyStatus(account.get().getOrganizationId(), toStatus(data));
            case "session.status" -> sessionService.applyEvent(account.get().getId(), data);
            default -> log.debug("[baileys-hook] ignoring event type {}", type);
        }
    }

    private void ingest(WaAccount account, InboundMessage message) {
        try {
            leadResolver.resolve(account, message);
        } catch (DataIntegrityViolationException e) {
            // Another delivery created the lead for this number a moment earlier; ingest finds it.
            log.debug("[baileys-hook] lead for {} already created concurrently", message.wamid());
        }
        ingestService.ingest(account.getOrganizationId(), message);
    }

    private Optional<WaAccount> account(String sessionId) {
        try {
            return accountRepository.findById(UUID.fromString(sessionId))
                    .filter(a -> a.getProvider() == WaAccount.Provider.BAILEYS);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    static InboundMessage toInbound(JsonNode d) {
        InboundMessage.Origin origin = switch (d.path("origin").asText("live")) {
            case "history" -> InboundMessage.Origin.HISTORY;
            case "offline" -> InboundMessage.Origin.OFFLINE;
            default -> InboundMessage.Origin.LIVE;
        };
        return new InboundMessage(
                text(d, "wamid"),
                "OUT".equals(d.path("direction").asText()) ? WaMessage.Direction.OUT : WaMessage.Direction.IN,
                origin,
                text(d, "phoneE164"),
                text(d, "jid"),
                text(d, "lid"),
                text(d, "pushName"),
                text(d, "messageType"),
                text(d, "body"),
                text(d, "mediaType"),
                text(d, "mimeType"),
                text(d, "fileName"),
                text(d, "quotedWamid"),
                d.hasNonNull("occurredAt") ? Instant.parse(d.get("occurredAt").asText()) : Instant.now());
    }

    static StatusUpdate toStatus(JsonNode d) {
        WaMessage.Status status;
        try {
            status = WaMessage.Status.valueOf(d.path("status").asText(""));
        } catch (IllegalArgumentException e) {
            status = null;
        }
        return new StatusUpdate(text(d, "wamid"), status, text(d, "errorCode"), text(d, "errorTitle"),
                d.hasNonNull("at") ? Instant.parse(d.get("at").asText()) : Instant.now());
    }

    private static String text(JsonNode d, String field) {
        return d.hasNonNull(field) ? d.get(field).asText() : null;
    }
}
