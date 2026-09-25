package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.event.WaChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-organization registry of inbox SSE subscribers.
 *
 * <p>Subscribing touches no database: the stream is opened once per browser tab and held for
 * half an hour, so it must not pin a pool connection. Events are {@link WaChangeEvent} hints,
 * forwarded after the publishing transaction commits (a subscriber that refetches immediately
 * must see the change), and written from a separate virtual-thread executor so a slow client can
 * never stall the transaction that produced the event.
 */
@Service
@Slf4j
public class WaStreamService {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final Map<UUID, List<SseEmitter>> emittersByOrg = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    public SseEmitter subscribe(UUID organizationId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        List<SseEmitter> list = emittersByOrg.computeIfAbsent(organizationId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> remove(organizationId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("{}"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(WaChangeEvent event) {
        List<SseEmitter> list = emittersByOrg.get(event.organizationId());
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", event.type().name());
        if (event.conversationId() != null) {
            data.put("conversationId", event.conversationId());
        }
        if (event.messageId() != null) {
            data.put("messageId", event.messageId());
        }
        for (SseEmitter emitter : list) {
            sender.execute(() -> send(event.organizationId(), emitter, "change", data));
        }
    }

    /** Keeps idle streams alive through Traefik and browsers, and prunes dead ones. */
    @Scheduled(fixedDelayString = "${whatsapp.stream.heartbeat-ms:25000}")
    public void heartbeat() {
        emittersByOrg.forEach((orgId, list) -> list.forEach(emitter ->
                sender.execute(() -> {
                    try {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } catch (Exception e) {
                        remove(orgId, emitter);
                    }
                })));
    }

    int subscriberCount(UUID organizationId) {
        List<SseEmitter> list = emittersByOrg.get(organizationId);
        return list == null ? 0 : list.size();
    }

    private void send(UUID orgId, SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.debug("[wa-stream] dropping subscriber of org {}: {}", orgId, e.getMessage());
            remove(orgId, emitter);
        }
    }

    private void remove(UUID orgId, SseEmitter emitter) {
        emittersByOrg.computeIfPresent(orgId, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
