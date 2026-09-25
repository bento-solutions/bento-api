package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Owns conversation lifecycle and the 24-hour customer service window.
 *
 * <p>Every activity write goes through {@link WaConversationRepository}'s single-statement
 * updates, so concurrent inbound messages, receipts and sends for one conversation never lose
 * each other's changes.
 */
@Service
@RequiredArgsConstructor
public class WaConversationService {

    /** WhatsApp's customer service window: free-form text is only allowed inside it. */
    public static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    /** Longest last-message preview kept on the conversation for the inbox list. */
    public static final int PREVIEW_LENGTH = 280;

    private final WaConversationRepository conversationRepository;

    /**
     * Finds or creates the conversation for a phone number, in the caller's transaction.
     *
     * <p>The inbound webhook and a send can create the same conversation at the same moment. The
     * insert uses ON CONFLICT DO NOTHING, so the loser neither fails nor poisons its transaction;
     * it simply reads the winner's row. Running in the caller's transaction (rather than
     * REQUIRES_NEW) also means an ingest never holds two pool connections at once.
     */
    @Transactional
    public WaConversation getOrCreate(UUID organizationId, String phoneE164, UUID partnerId) {
        WaConversation existing = conversationRepository.findByOrgAndPhone(organizationId, phoneE164).orElse(null);
        if (existing == null) {
            conversationRepository.insertIfAbsent(organizationId, phoneE164, partnerId);
            return conversationRepository.findByOrgAndPhone(organizationId, phoneE164)
                    .orElseThrow(() -> new IllegalStateException("Conversation vanished right after insert"));
        }
        if (existing.getPartnerId() == null && partnerId != null
                && conversationRepository.linkPartnerIfUnset(existing.getId(), partnerId) == 1) {
            existing.setPartnerId(partnerId);
        }
        return existing;
    }

    @Transactional
    public void recordIdentity(UUID conversationId, String jid, String lid, String displayName) {
        if (jid != null || lid != null || displayName != null) {
            conversationRepository.recordIdentity(conversationId, jid, lid, displayName);
        }
    }

    /**
     * Records an outbound message on the inbox list.
     *
     * <p>Only an inbound message extends the window — outbound traffic never does, which is why a
     * campaign send alone leaves the tenant restricted to templates.
     */
    @Transactional
    public void recordOutbound(UUID conversationId, Instant at, String body) {
        conversationRepository.recordOutbound(conversationId, at, preview(body));
    }

    /**
     * Records an inbound message and reopens the 24-hour window.
     *
     * @param countAsUnread false for history-sync messages, which the owner has already seen
     */
    @Transactional
    public void recordInbound(UUID conversationId, Instant at, String body, boolean countAsUnread) {
        conversationRepository.recordInbound(conversationId, at, at.plus(SERVICE_WINDOW), preview(body),
                countAsUnread ? 1 : 0);
    }

    @Transactional
    public void markRead(UUID conversationId, Instant at) {
        conversationRepository.markRead(conversationId, at);
    }

    @Transactional
    public void optOut(UUID conversationId, Instant at) {
        conversationRepository.optOut(conversationId, at);
    }

    /** @return whether the caller should send a notification (see the repository's throttle). */
    @Transactional
    public boolean claimNotification(UUID conversationId, Instant now, Duration throttle) {
        return conversationRepository.claimNotification(conversationId, now, now.minus(throttle)) == 1;
    }

    public static String preview(String body) {
        if (body == null) {
            return null;
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH - 1) + "…";
    }
}
