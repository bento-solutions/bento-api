package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.config.WaOutboxProperties;
import com.bento.crm.whatsapp.event.WaChangeEvent;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.provider.WhatsAppProvider;
import com.bento.crm.whatsapp.provider.WhatsAppProviderRegistry;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional halves of an outbox send: claim, then complete. The provider call happens
 * between them, outside any transaction (see {@link WaOutboxScheduler}), so no lock or pool
 * connection is held across the network.
 *
 * <p>A separate bean from the scheduler for the same reason as {@link WaFollowupWorker}: calls
 * on {@code this} bypass the transactional proxy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaOutboxWorker {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final WaMessageRepository messageRepository;
    private final WaAccountRepository accountRepository;
    private final WaConversationRepository conversationRepository;
    private final WhatsAppProviderRegistry providerRegistry;
    private final WaPacingPolicy pacingPolicy;
    private final WaConversationService conversationService;
    private final WaOutboxProperties properties;
    private final ApplicationEventPublisher events;

    /** Everything the send needs, captured while the message was locked. */
    public record Claim(UUID messageId, UUID organizationId, UUID conversationId, WaAccount account,
                        String toPhoneE164, String body, String wamid, int attempts, boolean paced) {
    }

    public List<UUID> organizationsWithDueMessages() {
        return messageRepository.findOrgsWithDueMessages();
    }

    /**
     * Claims the next message this account may send now, if any, and marks it SENDING.
     *
     * <p>The account row lock serializes claimers of one account: a paced account never has two
     * sends in flight, and its pacing counts cannot change while they are being evaluated.
     * Messages that must wait (pacing, caps, business hours) get their {@code notBefore} pushed
     * out so later polls skip them without re-evaluating.
     */
    @Transactional
    public Optional<Claim> claimNext(UUID orgId) {
        WaAccount account = accountRepository.findByOrganizationIdForUpdate(orgId).orElse(null);
        if (account == null) {
            return Optional.empty();
        }
        WhatsAppProvider provider = providerRegistry.forAccount(account);
        if (provider.paced() && messageRepository.existsSending(orgId)) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        for (WaMessage candidate : messageRepository.lockDueQueued(orgId, properties.getClaimBatch())) {
            Instant eligibleAt = provider.paced() ? pacingPolicy.eligibleAt(account, candidate, now) : now;
            if (eligibleAt.isAfter(now)) {
                candidate.setNotBefore(eligibleAt);
                continue;
            }
            WaConversation conversation = conversationRepository.findById(candidate.getConversationId()).orElse(null);
            if (conversation == null) {
                candidate.setStatus(WaMessage.Status.FAILED);
                candidate.setErrorCode("NO_CONVERSATION");
                continue;
            }

            candidate.setStatus(WaMessage.Status.SENDING);
            candidate.setClaimedAt(now);
            candidate.setAttempts(candidate.getAttempts() + 1);
            if (provider.assignsMessageIds() && candidate.getWamid() == null) {
                candidate.setWamid(newMessageId());
            }
            return Optional.of(new Claim(candidate.getId(), orgId, candidate.getConversationId(), account,
                    conversation.getPhoneE164(), candidate.getBody(), candidate.getWamid(),
                    candidate.getAttempts(), provider.paced()));
        }
        return Optional.empty();
    }

    /** Records the provider's answer for a claimed message. */
    @Transactional
    public void complete(Claim claim, WhatsAppProvider.SendResult result) {
        Instant now = Instant.now();
        if (result.success()) {
            String wamid = result.wamid() != null ? result.wamid() : claim.wamid();
            messageRepository.completeSent(claim.messageId(), wamid, now);
            conversationService.recordOutbound(claim.conversationId(), now, claim.body());
        } else if (result.retryable() && claim.attempts() < properties.getMaxAttempts()) {
            messageRepository.requeue(claim.messageId(), now.plus(backoff(claim.attempts())),
                    result.errorCode(), truncate(result.errorTitle()));
            log.warn("[wa-outbox] send of {} failed (attempt {}), retrying: {}",
                    claim.messageId(), claim.attempts(), result.errorCode());
        } else {
            messageRepository.fail(claim.messageId(), result.errorCode(), truncate(result.errorTitle()));
            log.warn("[wa-outbox] send of {} failed permanently: {} {}",
                    claim.messageId(), result.errorCode(), result.errorTitle());
        }
        events.publishEvent(WaChangeEvent.messageUpdated(claim.organizationId(), claim.conversationId(), claim.messageId()));
    }

    /**
     * Resolves sends whose instance died mid-call. With a CRM-assigned wamid the provider
     * deduplicates, so the send is simply retried under the same id; otherwise the outcome is
     * unknown and resending could deliver twice, so it is failed for a person to check.
     */
    @Transactional
    public int reapStale() {
        Instant now = Instant.now();
        List<WaMessage> stale = messageRepository.findStaleSending(now.minus(properties.getStaleSendingAfter()));
        for (WaMessage message : stale) {
            boolean idempotent = accountRepository.findByOrganizationId(message.getOrganizationId())
                    .map(a -> providerRegistry.forAccount(a).assignsMessageIds())
                    .orElse(false);
            if (idempotent && message.getWamid() != null && message.getAttempts() < properties.getMaxAttempts()) {
                messageRepository.requeue(message.getId(), now, "STALE_SENDING", "Send did not report back; retrying");
            } else {
                messageRepository.fail(message.getId(), "UNKNOWN_OUTCOME",
                        "The send did not report back; check the phone before resending");
            }
            events.publishEvent(WaChangeEvent.messageUpdated(message.getOrganizationId(),
                    message.getConversationId(), message.getId()));
        }
        return stale.size();
    }

    /** Same shape as Baileys' own ids ({@code 3EB0} + 18 uppercase hex), so they look native. */
    static String newMessageId() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return "3EB0" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static Duration backoff(int attempts) {
        Duration d = Duration.ofSeconds(5L << Math.min(attempts - 1, 10));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
