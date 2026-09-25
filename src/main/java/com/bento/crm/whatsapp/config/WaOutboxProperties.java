package com.bento.crm.whatsapp.config;

import com.bento.crm.whatsapp.util.BusinessHours;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbox tuning. The pacing values are the defaults for a paced (personal-number) account; they
 * are deliberately conservative, because WhatsApp bans personal numbers that send like a bulk
 * sender and there is no appeal that restores the number's history. The reasoning behind each
 * value is in docs/whatsapp-anti-spam-policy.md.
 */
@Component
@Getter
public class WaOutboxProperties {

    @Value("${whatsapp.outbox.max-attempts:5}")
    private int maxAttempts;

    /** A SENDING message older than this is assumed orphaned by a crashed instance. */
    @Value("${whatsapp.outbox.stale-sending-after:PT2M}")
    private Duration staleSendingAfter;

    @Value("${whatsapp.outbox.claim-batch:20}")
    private int claimBatch;

    /** Messages one drain may send for an unpaced account before yielding to other accounts. */
    @Value("${whatsapp.outbox.max-per-drain:50}")
    private int maxPerDrain;

    @Value("${whatsapp.outbox.pacing.reply-min-gap:PT8S}")
    private Duration replyMinGap;

    @Value("${whatsapp.outbox.pacing.outreach-min-gap:PT90S}")
    private Duration outreachMinGap;

    /**
     * Each gap is stretched by a random share of itself up to this factor (1.5: an 8s minimum
     * becomes 8–20s), so sends never tick at the regular interval that marks a machine.
     */
    @Value("${whatsapp.outbox.pacing.gap-jitter:1.5}")
    private double gapJitter;

    @Value("${whatsapp.outbox.pacing.outreach-per-hour:8}")
    private int outreachPerHour;

    /** First messages, relances and campaign sends together, per business day. */
    @Value("${whatsapp.outbox.pacing.outreach-per-day:25}")
    private int outreachPerDay;

    @Value("${whatsapp.outbox.pacing.new-chats-per-day:10}")
    private int newChatsPerDay;

    /** After linking (or a restriction), no outreach at all for this long: replies only. */
    @Value("${whatsapp.outbox.pacing.quiet-period:PT48H}")
    private Duration quietPeriod;

    /**
     * New chats per day after the quiet period, as {@code <days>x<limit>} steps: {@code 5x3,7x6,14x10}
     * allows 3 a day for 5 days, then 6 for a week, then 10 for two weeks, then the account's cap.
     */
    @Value("${whatsapp.outbox.pacing.warmup:5x3,7x6,14x10}")
    private String warmup;

    /** Never use more than this share of WhatsApp's own new-chat quota for the number. */
    @Value("${whatsapp.outbox.pacing.quota-share:0.5}")
    private double quotaShare;

    /** Held outreach is released at a random moment within this long after its window opens. */
    @Value("${whatsapp.outbox.pacing.window-spread:PT30M}")
    private Duration windowSpread;

    // --- Circuit breaker ------------------------------------------------------------------------

    /** Outreach pause after WhatsApp logs the number out, bans it or another device takes over. */
    @Value("${whatsapp.outbox.safety.pause-after-logout:PT72H}")
    private Duration pauseAfterLogout;

    /** Outreach pause after WhatsApp refuses a message or warns about too many new chats. */
    @Value("${whatsapp.outbox.safety.pause-after-refusal:PT24H}")
    private Duration pauseAfterRefusal;

    /** Outreach sent at least this long ago should have been delivered by now. */
    @Value("${whatsapp.outbox.safety.delivery-grace:PT3H}")
    private Duration deliveryGrace;

    @Value("${whatsapp.outbox.safety.delivery-min-sample:6}")
    private int deliveryMinSample;

    /** Share of recent outreach left undelivered that pauses outreach (recipients blocking, or a restriction). */
    @Value("${whatsapp.outbox.safety.delivery-max-undelivered:0.5}")
    private double deliveryMaxUndelivered;

    @Value("${whatsapp.followup.business-hours.enabled:true}")
    private boolean businessHoursEnabled;

    @Value("${whatsapp.followup.business-hours.zone:Africa/Casablanca}")
    private String businessZone;

    @Value("${whatsapp.followup.business-hours.start:9}")
    private int businessStartHour;

    @Value("${whatsapp.followup.business-hours.end:18}")
    private int businessEndHour;

    /**
     * The warm-up's new-chat limit on {@code day} (0 = the first day after the quiet period), or
     * -1 once the warm-up is over and the account's own cap applies.
     */
    public int warmupNewChats(long day) {
        long start = 0;
        for (int[] step : warmupSteps()) {
            if (day < start + step[0]) {
                return step[1];
            }
            start += step[0];
        }
        return -1;
    }

    private List<int[]> warmupSteps() {
        List<int[]> steps = new ArrayList<>();
        for (String part : warmup.split(",")) {
            String[] daysAndLimit = part.strip().split("x");
            if (daysAndLimit.length == 2) {
                steps.add(new int[]{Integer.parseInt(daysAndLimit[0].strip()), Integer.parseInt(daysAndLimit[1].strip())});
            }
        }
        return steps;
    }

    public BusinessHours businessHours() {
        return new BusinessHours(businessHoursEnabled, ZoneId.of(businessZone), businessStartHour, businessEndHour);
    }
}
