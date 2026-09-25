package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.config.WaOutboxProperties;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import com.bento.crm.whatsapp.util.BusinessHours;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * When a paced account may send a given message.
 *
 * <p>Two lanes. REPLY answers a contact who wrote within 24 hours — normal conversational
 * behaviour — so it only keeps a short gap between sends. OUTREACH opens or revives a
 * conversation, which is what bulk senders do and what gets personal numbers banned: it keeps a
 * longer gap, an hourly cap, a daily cap on brand-new chats, and business hours.
 *
 * <p>Must be called while holding the account's row lock (see the outbox claim), so the counts
 * cannot change underneath it.
 */
@Component
@RequiredArgsConstructor
public class WaPacingPolicy {

    /** How long to wait before re-checking a cap that is currently full. */
    static final Duration CAP_RECHECK = Duration.ofMinutes(2);

    private final WaMessageRepository messageRepository;
    private final WaOutboxProperties properties;

    /** @return the earliest time {@code message} may be sent; at or before {@code now} means now */
    public Instant eligibleAt(UUID orgId, WaMessage message, Instant now) {
        boolean outreach = message.getLane() == WaMessage.Lane.OUTREACH;

        if (outreach) {
            BusinessHours hours = properties.businessHours();
            Instant reopen = hours.nextWindow(now);
            if (reopen != null) {
                return reopen;
            }
            if (message.isNewChat() && messageRepository.countNewChatsSentSince(orgId, hours.startOfDay(now))
                    >= properties.getNewChatsPerDay()) {
                Instant tomorrow = hours.startOfDay(now).plus(Duration.ofDays(1));
                Instant open = hours.nextWindow(tomorrow);
                return open != null ? open : tomorrow;
            }
            if (messageRepository.countOutreachSentSince(orgId, now.minus(Duration.ofHours(1)))
                    >= properties.getOutreachPerHour()) {
                return now.plus(CAP_RECHECK);
            }
        }

        Instant last = messageRepository.lastCrmSendAt(orgId);
        if (last == null) {
            return now;
        }
        Instant afterGap = last.plus(outreach ? properties.getOutreachMinGap() : properties.getReplyMinGap());
        return afterGap.isAfter(now) ? afterGap : now;
    }
}
