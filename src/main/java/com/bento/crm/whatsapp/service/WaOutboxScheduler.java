package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.config.WaOutboxProperties;
import com.bento.crm.whatsapp.provider.WhatsAppProvider;
import com.bento.crm.whatsapp.provider.WhatsAppProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the outbox: a short poll picks up whatever is due, and {@link #kickAfterCommit} starts
 * a drain as soon as a new message commits so an unpaced send goes out immediately instead of on
 * the next tick.
 *
 * <p>Each account drains on its own virtual thread, so one slow provider call never delays other
 * tenants, and at most one drain per account runs in this instance at a time (across instances
 * the account row lock in {@link WaOutboxWorker#claimNext} does the same job).
 */
@Component
@Slf4j
public class WaOutboxScheduler {

    private final WaOutboxWorker worker;
    private final WhatsAppProviderRegistry providerRegistry;
    private final WaOutboxProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> draining = ConcurrentHashMap.newKeySet();

    public WaOutboxScheduler(WaOutboxWorker worker, WhatsAppProviderRegistry providerRegistry,
                             WaOutboxProperties properties) {
        this.worker = worker;
        this.providerRegistry = providerRegistry;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${whatsapp.outbox.initial-delay-ms:10000}",
            fixedDelayString = "${whatsapp.outbox.poll-interval-ms:2000}")
    public void poll() {
        for (UUID orgId : worker.organizationsWithDueMessages()) {
            kick(orgId);
        }
    }

    @Scheduled(initialDelayString = "${whatsapp.outbox.initial-delay-ms:10000}",
            fixedDelayString = "${whatsapp.outbox.reaper-interval-ms:60000}")
    public void reap() {
        int reaped = worker.reapStale();
        if (reaped > 0) {
            log.warn("[wa-outbox] resolved {} send(s) that never reported back", reaped);
        }
    }

    /** Starts a drain for the account once the current transaction commits (immediately if none). */
    public void kickAfterCommit(UUID orgId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            kick(orgId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                kick(orgId);
            }
        });
    }

    public void kick(UUID orgId) {
        if (!draining.add(orgId)) {
            return;
        }
        executor.execute(() -> {
            try {
                drain(orgId);
            } catch (Exception e) {
                log.error("[wa-outbox] drain failed for org {}", orgId, e);
            } finally {
                draining.remove(orgId);
            }
        });
    }

    /**
     * Sends due messages for one account. A paced account sends one message per drain: its
     * minimum gap is at least a few seconds, so the next one is picked up by a later poll.
     */
    void drain(UUID orgId) {
        for (int i = 0; i < properties.getMaxPerDrain(); i++) {
            Optional<WaOutboxWorker.Claim> claimed = worker.claimNext(orgId);
            if (claimed.isEmpty()) {
                return;
            }
            WaOutboxWorker.Claim claim = claimed.get();
            WhatsAppProvider.SendResult result;
            try {
                result = providerRegistry.forAccount(claim.account())
                        .sendText(claim.account(), claim.toPhoneE164(), claim.body(), claim.wamid());
            } catch (Exception e) {
                log.warn("[wa-outbox] provider threw sending {}", claim.messageId(), e);
                result = WhatsAppProvider.SendResult.retryableFailure("EXCEPTION", e.getMessage());
            }
            worker.complete(claim, result);
            if (claim.paced()) {
                return;
            }
        }
    }
}
