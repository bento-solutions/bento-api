package com.bento.crm.whatsapp.service;

import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.whatsapp.config.WaOutboxProperties;
import com.bento.crm.whatsapp.event.WaChangeEvent;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The circuit breaker on a linked personal number's outreach (first messages, relances, campaign
 * sends). Replies keep flowing; outreach stops as soon as WhatsApp shows it is unhappy with the
 * number, and only starts again after the problem has passed and a fresh warm-up.
 *
 * <p>Signals, strongest first:
 * <ul>
 *   <li>WhatsApp logs the number out, bans it, or another device takes the session over;</li>
 *   <li>WhatsApp's reachout timelock: a restriction on starting new chats, with an end time;</li>
 *   <li>WhatsApp's new-chat quota escalating to SECOND_WARNING or CAPPED (FIRST_WARNING halves
 *       the daily cap in {@link WaPacingPolicy} instead);</li>
 *   <li>a message WhatsApp refused (a FAILED receipt, e.g. error 463);</li>
 *   <li>most recent outreach never delivered: recipients blocking the number, or a silent restriction.</li>
 * </ul>
 * An admin can also pause and resume outreach from Settings → WhatsApp.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaOutreachGuard {

    private static final Duration DELIVERY_WINDOW = Duration.ofHours(48);

    private final WaAccountRepository accountRepository;
    private final WaMessageRepository messageRepository;
    private final WaPacingPolicy pacingPolicy;
    private final WaOutboxProperties properties;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /** What Settings → WhatsApp shows about the number's sending safety. */
    public record SafetyView(
            /* ACTIVE, WARMING_UP, QUIET, PAUSED or RESTRICTED */
            String outreach,
            Instant outreachPausedUntil, String outreachPauseReason,
            Instant reachoutLockedUntil, String reachoutEnforcement,
            Instant warmupStartedAt, Instant quietUntil,
            int newChatsToday, int newChatsCapToday, int outreachToday, int outreachCapToday,
            Integer newChatQuota, Integer newChatQuotaUsed, String newChatCapStatus, Instant newChatCycleEndsAt) {
    }

    // --- signals ---------------------------------------------------------------------------------

    /**
     * Stores WhatsApp's restriction and quota from a bot {@code session.status} event that was just
     * applied, and reacts to what changed. Starts the warm-up the first time the session opens.
     */
    @Transactional
    public void onSessionStatus(UUID accountId, String state, JsonNode data) {
        WaAccount before = accountRepository.findById(accountId).orElse(null);
        if (before == null || before.getProvider() != WaAccount.Provider.BAILEYS) {
            return;
        }
        Instant now = Instant.now();
        boolean locked = data.path("reachoutLocked").asBoolean(false);
        Instant lockedUntil = locked ? instant(data, "reachoutUntil") : null;
        if (locked && lockedUntil == null) {
            lockedUntil = now.plus(properties.getPauseAfterRefusal());
        }
        String capStatus = text(data, "newChatCapStatus");
        Instant cycleEnds = instant(data, "newChatCycleEndsAt");
        jdbc.update("""
                UPDATE wa_account SET reachout_locked_until = ?, reachout_enforcement = ?,
                    new_chat_quota = ?, new_chat_quota_used = ?, new_chat_cap_status = ?, new_chat_cycle_ends_at = ?,
                    warmup_started_at = CASE WHEN ? = 'open' AND warmup_started_at IS NULL THEN now() ELSE warmup_started_at END
                WHERE id = ?
                """, ts(lockedUntil), locked ? text(data, "reachoutType") : null,
                integer(data, "newChatQuota"), integer(data, "newChatUsed"), capStatus, ts(cycleEnds), state, accountId);

        switch (state) {
            case "logged_out", "replaced", "forbidden" -> {
                if (!state.equals(before.getSessionState())) {
                    // A new link starts the warm-up from zero; WaSessionService already alerts admins.
                    jdbc.update("UPDATE wa_account SET warmup_started_at = NULL WHERE id = ?", accountId);
                    pause(before, now.plus(properties.getPauseAfterLogout()), switch (state) {
                        case "logged_out" -> "WhatsApp logged the number out";
                        case "replaced" -> "Another device or server took over the WhatsApp session";
                        default -> "WhatsApp refused the connection; the number may be banned";
                    }, null, false);
                }
            }
            default -> {
            }
        }
        if (locked && !lockedUntil.equals(before.getReachoutLockedUntil())) {
            pause(before, lockedUntil, "WhatsApp restricted the number from starting new chats"
                    + (text(data, "reachoutType") != null ? " (" + text(data, "reachoutType") + ")" : ""), lockedUntil, true);
        }
        if (capStatus != null && !capStatus.equals(before.getNewChatCapStatus())) {
            switch (capStatus) {
                case "FIRST_WARNING" -> notifyAdmins(before.getOrganizationId(), "WhatsApp warning",
                        "WhatsApp warned that this number opens too many new chats. Bento has halved today's limit.");
                case "SECOND_WARNING" -> pause(before, now.plus(properties.getPauseAfterRefusal()),
                        "WhatsApp warned a second time about too many new chats", now, true);
                case "CAPPED" -> {
                    Instant until = cycleEnds != null && cycleEnds.isAfter(now) ? cycleEnds : now.plus(properties.getPauseAfterRefusal());
                    pause(before, until, "WhatsApp capped the number's new chats for this cycle", until, true);
                }
                default -> {
                }
            }
        }
    }

    /** The bot refused a send because of WhatsApp's restriction or quota (its own limits only defer it). */
    @Transactional
    public void onSendRefused(WaAccount account, String code, Instant retryAt) {
        Instant now = Instant.now();
        Instant until = retryAt != null && retryAt.isAfter(now) ? retryAt : now.plus(properties.getPauseAfterRefusal());
        switch (code) {
            case "REACHOUT_LOCKED" -> pause(account, until, "WhatsApp restricted the number from starting new chats", until, true);
            case "NEW_CHAT_CAP_REACHED" -> pause(account, until, "WhatsApp's new-chat quota for the number is used up", null, true);
            default -> log.warn("[wa-guard] bot limit {} deferred a send for org {}; CRM pacing should stay below it",
                    code, account.getOrganizationId());
        }
    }

    /** WhatsApp refused a message after accepting it (a FAILED receipt, e.g. error 463). */
    @Transactional
    public void onFailedReceipt(UUID orgId, String wamid, String errorCode) {
        if (wamid == null || errorCode == null) {
            return;
        }
        WaMessage message = messageRepository.findByOrgAndWamid(orgId, wamid).orElse(null);
        if (message == null || message.getDirection() != WaMessage.Direction.OUT) {
            return;
        }
        if (!"463".equals(errorCode) && message.getLane() != WaMessage.Lane.OUTREACH) {
            return;
        }
        accountRepository.findByOrganizationId(orgId).filter(a -> a.getProvider() == WaAccount.Provider.BAILEYS)
                .ifPresent(account -> {
                    Instant now = Instant.now();
                    pause(account, now.plus(properties.getPauseAfterRefusal()),
                            "WhatsApp refused a message (error " + errorCode + ")", now, true);
                });
    }

    /**
     * Pauses outreach when most of the last two days' outreach (old enough to have arrived) was
     * never delivered: people blocking the number, or WhatsApp silently holding its messages.
     */
    @Scheduled(initialDelayString = "${whatsapp.outbox.safety.delivery-check-initial-delay-ms:300000}",
            fixedDelayString = "${whatsapp.outbox.safety.delivery-check-interval-ms:1800000}")
    public void checkDeliveryHealth() {
        Instant now = Instant.now();
        for (WaAccount account : accountRepository.findAll()) {
            if (account.getProvider() != WaAccount.Provider.BAILEYS || !account.isSessionOpen()
                    || (account.getOutreachPausedUntil() != null && account.getOutreachPausedUntil().isAfter(now))) {
                continue;
            }
            Map<String, Object> stats = jdbc.queryForMap("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE delivered_at IS NULL AND read_at IS NULL) AS undelivered
                    FROM wa_message
                    WHERE organization_id = ? AND direction = 'OUT' AND lane = 'OUTREACH'
                      AND sent_at >= ? AND sent_at < ?
                    """, account.getOrganizationId(), ts(now.minus(DELIVERY_WINDOW)), ts(now.minus(properties.getDeliveryGrace())));
            long total = ((Number) stats.get("total")).longValue();
            long undelivered = ((Number) stats.get("undelivered")).longValue();
            if (total >= properties.getDeliveryMinSample() && undelivered >= total * properties.getDeliveryMaxUndelivered()) {
                pause(account, now.plus(properties.getPauseAfterRefusal()),
                        undelivered + " of the last " + total + " first messages were never delivered", now, true);
            }
        }
    }

    // --- admin controls --------------------------------------------------------------------------

    @Transactional
    public void manualPause(UUID orgId, Duration duration, String reason) {
        WaAccount account = accountRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new IllegalStateException("No WhatsApp account"));
        String why = reason == null || reason.isBlank() ? "Paused by an admin" : reason.strip();
        pause(account, Instant.now().plus(duration), why.length() > 300 ? why.substring(0, 300) : why, null, false);
    }

    /** Lifts Bento's own pause. WhatsApp's restriction and the warm-up still apply. */
    @Transactional
    public void resume(UUID orgId) {
        jdbc.update("UPDATE wa_account SET outreach_paused_until = NULL, outreach_pause_reason = NULL, updated_at = now() "
                + "WHERE organization_id = ?", orgId);
        events.publishEvent(new WaChangeEvent(orgId, WaChangeEvent.Type.SESSION_UPDATED, null, null));
    }

    public SafetyView view(WaAccount account, Instant now) {
        Instant dayStart = properties.businessHours().startOfDay(now);
        int newChatsCap = pacingPolicy.newChatsCap(account, now);
        Instant quietUntil = account.getWarmupStartedAt() == null ? null
                : account.getWarmupStartedAt().plus(properties.getQuietPeriod());
        String outreach;
        if (account.getReachoutLockedUntil() != null && account.getReachoutLockedUntil().isAfter(now)) {
            outreach = "RESTRICTED";
        } else if (account.getOutreachPausedUntil() != null && account.getOutreachPausedUntil().isAfter(now)) {
            outreach = "PAUSED";
        } else if (quietUntil != null && quietUntil.isAfter(now)) {
            outreach = "QUIET";
        } else if (account.getWarmupStartedAt() != null
                && properties.warmupNewChats(Duration.between(quietUntil, now).toDays()) >= 0) {
            outreach = "WARMING_UP";
        } else {
            outreach = "ACTIVE";
        }
        return new SafetyView(outreach, account.getOutreachPausedUntil(), account.getOutreachPauseReason(),
                account.getReachoutLockedUntil(), account.getReachoutEnforcement(), account.getWarmupStartedAt(), quietUntil,
                (int) messageRepository.countNewChatsSentSince(account.getOrganizationId(), dayStart), newChatsCap,
                (int) messageRepository.countOutreachSentSince(account.getOrganizationId(), dayStart),
                pacingPolicy.outreachPerDay(newChatsCap),
                account.getNewChatQuota(), account.getNewChatQuotaUsed(), account.getNewChatCapStatus(),
                account.getNewChatCycleEndsAt());
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * Pauses outreach until {@code until}, extending (never shortening) a pause already running.
     *
     * @param restartWarmupAt when set, the warm-up starts over from then: a quiet period, then
     *                        the lowest daily cap
     */
    private void pause(WaAccount account, Instant until, String reason, Instant restartWarmupAt, boolean notify) {
        Instant current = accountRepository.findById(account.getId()).map(WaAccount::getOutreachPausedUntil).orElse(null);
        boolean longer = current == null || current.isBefore(until);
        if (longer) {
            jdbc.update("UPDATE wa_account SET outreach_paused_until = ?, outreach_pause_reason = ?, updated_at = now() WHERE id = ?",
                    ts(until), reason, account.getId());
        }
        if (restartWarmupAt != null) {
            jdbc.update("UPDATE wa_account SET warmup_started_at = GREATEST(COALESCE(warmup_started_at, ?), ?) WHERE id = ?",
                    ts(restartWarmupAt), ts(restartWarmupAt), account.getId());
        }
        if (!longer) {
            return;
        }
        log.warn("[wa-guard] outreach paused for org {} until {}: {}", account.getOrganizationId(), until, reason);
        events.publishEvent(new WaChangeEvent(account.getOrganizationId(), WaChangeEvent.Type.SESSION_UPDATED, null, null));
        if (notify) {
            notifyAdmins(account.getOrganizationId(), "WhatsApp outreach paused", reason
                    + ". First messages, relances and campaigns wait until " + until
                    + "; replies to contacts who wrote still go out.");
        }
    }

    private void notifyAdmins(UUID orgId, String title, String message) {
        List<UUID> admins = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role = 'ADMIN' AND is_active = true", UUID.class, orgId);
        for (UUID admin : admins) {
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(admin);
            n.setType(Notification.NotificationType.WHATSAPP);
            n.setTitle(title);
            n.setMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            n.setIsRead(false);
            notificationService.createForOrganization(orgId, n);
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String text(JsonNode data, String field) {
        return data.hasNonNull(field) ? data.get(field).asText() : null;
    }

    private static Integer integer(JsonNode data, String field) {
        return data.hasNonNull(field) && data.get(field).canConvertToInt() ? data.get(field).asInt() : null;
    }

    private static Instant instant(JsonNode data, String field) {
        String value = text(data, field);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
