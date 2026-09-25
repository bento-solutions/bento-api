package com.bento.crm.whatsapp.service;

import com.bento.crm.whatsapp.config.WaOutboxProperties;
import com.bento.crm.whatsapp.model.WaAccount;
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

    /**
     * @param account the sending account; its pacing overrides win over the configured defaults
     * @return the earliest time {@code message} may be sent; at or before {@code now} means now
     */
    public Instant eligibleAt(WaAccount account, WaMessage message, Instant now) {
        UUID orgId = account.getOrganizationId();
        boolean outreach = message.getLane() == WaMessage.Lane.OUTREACH;
        int newChatsPerDay = orDefault(account.getNewChatsPerDay(), properties.getNewChatsPerDay());
        int outreachPerHour = orDefault(account.getOutreachPerHour(), properties.getOutreachPerHour());
        Duration replyGap = account.getReplyMinGapSeconds() != null
                ? Duration.ofSeconds(account.getReplyMinGapSeconds()) : properties.getReplyMinGap();
        Duration outreachGap = account.getOutreachMinGapSeconds() != null
                ? Duration.ofSeconds(account.getOutreachMinGapSeconds()) : properties.getOutreachMinGap();

        if (outreach) {
            BusinessHours hours = properties.businessHours();
            Instant reopen = hours.nextWindow(now);
            if (reopen != null) {
                return reopen;
            }
            if (message.isNewChat() && messageRepository.countNewChatsSentSince(orgId, hours.startOfDay(now))
                    >= newChatsPerDay) {
                Instant tomorrow = hours.startOfDay(now).plus(Duration.ofDays(1));
                Instant open = hours.nextWindow(tomorrow);
                return open != null ? open : tomorrow;
            }
            if (messageRepository.countOutreachSentSince(orgId, now.minus(Duration.ofHours(1)))
                    >= outreachPerHour) {
                return now.plus(CAP_RECHECK);
            }
        }

        Instant last = messageRepository.lastCrmSendAt(orgId);
        if (last == null) {
            return now;
        }
        Instant afterGap = last.plus(outreach ? outreachGap : replyGap);
        return afterGap.isAfter(now) ? afterGap : now;
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}
