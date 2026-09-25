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
 * conversation, which is what bulk senders do and what gets personal numbers restricted or
 * banned. It waits out any pause (the circuit breaker in {@link WaOutreachGuard}) and WhatsApp's
 * own restriction, the warm-up's quiet period, business hours, then a daily cap on new chats
 * (lowered during the warm-up and kept to a share of WhatsApp's quota), a daily and an hourly cap
 * on outreach, and a longer gap.
 *
 * <p>No gap is ever the same twice: each is stretched by a random share that is derived from the
 * message and the previous send, so repeated evaluations of one message agree with each other.
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
        double spread = unit(message.getId(), 0);

        if (outreach) {
            Instant hold = outreachHold(account, now);
            if (hold != null && hold.isAfter(now)) {
                return spreadOut(hold, spread);
            }
            BusinessHours hours = properties.businessHours();
            Instant reopen = hours.nextWindow(now);
            if (reopen != null) {
                return spreadOut(reopen, spread);
            }
            Instant dayStart = hours.startOfDay(now);
            int newChatCap = newChatsCap(account, now);
            if (message.isNewChat() && (quotaUsedUp(account, now)
                    || messageRepository.countNewChatsSentSince(orgId, dayStart) >= newChatCap)) {
                return spreadOut(nextDay(hours, now, account), spread);
            }
            if (messageRepository.countOutreachSentSince(orgId, dayStart) >= outreachPerDay(newChatCap)) {
                return spreadOut(nextDay(hours, now, account), spread);
            }
            int perHour = orDefault(account.getOutreachPerHour(), properties.getOutreachPerHour());
            if (messageRepository.countOutreachSentSince(orgId, now.minus(Duration.ofHours(1))) >= perHour) {
                return now.plus(stretch(CAP_RECHECK, spread));
            }
        }

        Instant last = messageRepository.lastCrmSendAt(orgId);
        if (last == null) {
            return now;
        }
        Duration minGap = outreach
                ? account.getOutreachMinGapSeconds() != null
                        ? Duration.ofSeconds(account.getOutreachMinGapSeconds()) : properties.getOutreachMinGap()
                : account.getReplyMinGapSeconds() != null
                        ? Duration.ofSeconds(account.getReplyMinGapSeconds()) : properties.getReplyMinGap();
        Instant afterGap = last.plus(stretch(minGap, unit(message.getId(), last.getEpochSecond())));
        return afterGap.isAfter(now) ? afterGap : now;
    }

    /**
     * The latest of: an outreach pause, WhatsApp's restriction on new chats, and the end of the
     * warm-up's quiet period. Null when nothing holds outreach back.
     */
    public Instant outreachHold(WaAccount account, Instant now) {
        Instant hold = later(account.getOutreachPausedUntil(), account.getReachoutLockedUntil());
        if (account.getWarmupStartedAt() != null) {
            hold = later(hold, account.getWarmupStartedAt().plus(properties.getQuietPeriod()));
        }
        return hold != null && hold.isAfter(now) ? hold : null;
    }

    /**
     * New chats allowed today: the account's cap, lowered by the warm-up, never above the
     * configured share of WhatsApp's own quota, and halved once WhatsApp has warned.
     */
    public int newChatsCap(WaAccount account, Instant now) {
        // 0 is a real setting here (no new chats at all), unlike the hourly cap.
        int cap = account.getNewChatsPerDay() != null ? account.getNewChatsPerDay() : properties.getNewChatsPerDay();
        if (account.getWarmupStartedAt() != null) {
            Instant rampStart = account.getWarmupStartedAt().plus(properties.getQuietPeriod());
            long day = now.isBefore(rampStart) ? 0 : Duration.between(rampStart, now).toDays();
            int warmup = properties.warmupNewChats(day);
            if (warmup >= 0) {
                cap = Math.min(cap, warmup);
            }
        }
        if (account.getNewChatQuota() != null) {
            cap = Math.min(cap, (int) Math.floor(account.getNewChatQuota() * properties.getQuotaShare()));
        }
        if ("FIRST_WARNING".equals(account.getNewChatCapStatus())) {
            cap = cap / 2;
        }
        return Math.max(cap, 0);
    }

    /** Relances and campaign sends ride along with new chats: about two per new chat, capped. */
    int outreachPerDay(int newChatsCap) {
        return Math.min(properties.getOutreachPerDay(), newChatsCap * 2 + 2);
    }

    /** Whether the configured share of WhatsApp's quota is spent for the cycle that is running. */
    private boolean quotaUsedUp(WaAccount account, Instant now) {
        if (account.getNewChatQuota() == null || account.getNewChatQuotaUsed() == null) {
            return false;
        }
        if (account.getNewChatCycleEndsAt() != null && !account.getNewChatCycleEndsAt().isAfter(now)) {
            return false;
        }
        return account.getNewChatQuotaUsed() >= Math.floor(account.getNewChatQuota() * properties.getQuotaShare());
    }

    /** Tomorrow's window, or the end of WhatsApp's quota cycle if that comes later. */
    private Instant nextDay(BusinessHours hours, Instant now, WaAccount account) {
        Instant tomorrow = hours.startOfDay(now).plus(Duration.ofDays(1));
        Instant open = hours.nextWindow(tomorrow);
        Instant next = open != null ? open : tomorrow;
        return quotaUsedUp(account, now) ? later(next, account.getNewChatCycleEndsAt()) : next;
    }

    private Duration stretch(Duration minimum, double u) {
        return minimum.plusMillis(Math.round(minimum.toMillis() * properties.getGapJitter() * u));
    }

    private Instant spreadOut(Instant at, double u) {
        return at.plusMillis(Math.round(properties.getWindowSpread().toMillis() * u));
    }

    /** A stable value in [0, 1) for this message (and salt); 0 for a message not saved yet. */
    static double unit(UUID id, long salt) {
        if (id == null) {
            return 0.0;
        }
        long h = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17) ^ (salt * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}
