package com.bento.crm.whatsapp.config;

import com.bento.crm.whatsapp.util.BusinessHours;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Outbox tuning. The pacing values are the defaults for a paced (personal-number) account; they
 * are deliberately conservative, because WhatsApp bans personal numbers that send like a bulk
 * sender and there is no appeal that restores the number's history.
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

    @Value("${whatsapp.outbox.pacing.reply-min-gap:PT3S}")
    private Duration replyMinGap;

    @Value("${whatsapp.outbox.pacing.outreach-min-gap:PT10S}")
    private Duration outreachMinGap;

    @Value("${whatsapp.outbox.pacing.outreach-per-hour:30}")
    private int outreachPerHour;

    @Value("${whatsapp.outbox.pacing.new-chats-per-day:15}")
    private int newChatsPerDay;

    @Value("${whatsapp.followup.business-hours.enabled:true}")
    private boolean businessHoursEnabled;

    @Value("${whatsapp.followup.business-hours.zone:Africa/Casablanca}")
    private String businessZone;

    @Value("${whatsapp.followup.business-hours.start:9}")
    private int businessStartHour;

    @Value("${whatsapp.followup.business-hours.end:18}")
    private int businessEndHour;

    public BusinessHours businessHours() {
        return new BusinessHours(businessHoursEnabled, ZoneId.of(businessZone), businessStartHour, businessEndHour);
    }
}
