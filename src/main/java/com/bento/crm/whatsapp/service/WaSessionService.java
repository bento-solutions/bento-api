package com.bento.crm.whatsapp.service;

import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.whatsapp.config.BaileysProperties;
import com.bento.crm.whatsapp.event.WaChangeEvent;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.provider.BaileysBotClient;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.util.PhoneNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The organization's linked-device session on the Baileys bot: preparing the account, linking by
 * pairing code, starting, stopping and unlinking, and mirroring the bot's reported state.
 *
 * <p>The bot is the source of truth for the session; it reports every change as a
 * {@code session.status} webhook event with a per-session sequence number, applied here in
 * order (an older event arriving late is ignored). A periodic reconcile restarts sessions the
 * bot lost track of, e.g. after its container was recreated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaSessionService {

    /** States in which the session is meant to be running on the bot. */
    private static final Set<String> RUNNING = Set.of("connecting", "pairing", "open", "reconnecting");
    /** States that need an admin: the number was unlinked from the phone, or taken over elsewhere. */
    private static final Set<String> ALERTING = Set.of("logged_out", "replaced", "pairing_failed");

    private final WaAccountRepository accountRepository;
    private final BaileysBotClient bot;
    private final BaileysProperties properties;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public record SessionView(UUID accountId, String provider, String state, String requestedPhone,
                              String linkedPhone, Instant linkedAt, Instant lastSeenAt, String pairingCode,
                              Instant pairingExpiresAt, Integer pairingAttempt, String error,
                              boolean botConfigured) {
    }

    /**
     * Makes the organization's account a Baileys account for {@code phone} without touching the
     * bot. Converts an existing MOCK/META row in place, so campaigns and conversations stay.
     */
    @Transactional
    public WaAccount prepare(UUID orgId, String phone, WaAccount.AutoCreateLeads autoCreateLeads) {
        String e164 = PhoneNumbers.toE164(phone)
                .orElseThrow(() -> new IllegalArgumentException("Not a usable phone number"));
        WaAccount account = accountRepository.findByOrganizationId(orgId).orElseGet(WaAccount::new);
        if (account.getProvider() == WaAccount.Provider.BAILEYS && account.isSessionOpen()
                && account.getLinkedPhone() != null && !account.getLinkedPhone().equals(e164)) {
            throw new IllegalStateException("Unlink " + account.getLinkedPhone() + " before linking another number");
        }
        account.setOrganizationId(orgId);
        account.setProvider(WaAccount.Provider.BAILEYS);
        account.setPhoneNumberId("baileys-" + orgId);
        account.setDisplayPhoneNumber(e164);
        account.setRequestedPhone(e164);
        if (account.getStatus() == null || !account.isSessionOpen()) {
            account.setStatus(WaAccount.Status.DISCONNECTED);
        }
        if (account.getSessionState() == null) {
            account.setSessionState("stopped");
        }
        if (autoCreateLeads != null) {
            account.setAutoCreateLeads(autoCreateLeads);
        }
        return accountRepository.save(account);
    }

    /** Asks the bot for a pairing code for the prepared number. */
    public SessionView link(UUID orgId) {
        WaAccount account = requireBaileys(orgId);
        if (account.getRequestedPhone() == null) {
            throw new IllegalStateException("Choose the number to link first");
        }
        applyDirect(account.getId(), bot.start(account.getId(), account.getRequestedPhone()));
        return view(orgId);
    }

    /** Reconnects an already linked session (no pairing). */
    public SessionView start(UUID orgId) {
        WaAccount account = requireBaileys(orgId);
        applyDirect(account.getId(), bot.start(account.getId(), null));
        return view(orgId);
    }

    public SessionView stop(UUID orgId) {
        WaAccount account = requireBaileys(orgId);
        applyDirect(account.getId(), bot.stop(account.getId()));
        return view(orgId);
    }

    /** Unlinks the device from the phone and deletes its credentials on the bot. */
    public SessionView unlink(UUID orgId) {
        WaAccount account = requireBaileys(orgId);
        applyDirect(account.getId(), bot.logout(account.getId()));
        jdbc.update("UPDATE wa_account SET linked_phone = NULL, status = 'DISCONNECTED' WHERE id = ?", account.getId());
        return view(orgId);
    }

    /**
     * Read with a plain query rather than through the entity: the session columns are written by
     * direct UPDATEs, and within one request (open-session-in-view) an entity loaded earlier
     * would still show the old state.
     */
    public SessionView view(UUID orgId) {
        List<SessionView> rows = jdbc.query("""
                SELECT id, provider, session_state, requested_phone, linked_phone, linked_at, last_seen_at,
                       pairing_code, pairing_expires_at, pairing_attempt, session_error
                FROM wa_account WHERE organization_id = ?
                """, (rs, i) -> new SessionView(
                rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("session_state"),
                rs.getString("requested_phone"), rs.getString("linked_phone"), instant(rs, "linked_at"),
                instant(rs, "last_seen_at"), rs.getString("pairing_code"), instant(rs, "pairing_expires_at"),
                (Integer) rs.getObject("pairing_attempt"), rs.getString("session_error"), properties.isConfigured()),
                orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("No WhatsApp account");
        }
        return rows.get(0);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Applies a {@code session.status} event from the bot if it is newer than the last one
     * applied. The sequence check is part of the UPDATE, so concurrent deliveries cannot apply an
     * older state over a newer one.
     *
     * @return whether the event was applied
     */
    @Transactional
    public boolean applyEvent(UUID accountId, JsonNode data) {
        long seq = data.path("seq").asLong(0);
        String state = data.path("state").asText(null);
        if (seq <= 0 || state == null) {
            return false;
        }
        int updated = jdbc.update("""
                UPDATE wa_account SET
                    session_seq = ?, session_state = ?, pairing_code = ?, pairing_expires_at = ?,
                    pairing_attempt = ?, session_error = ?, last_seen_at = now(),
                    linked_phone = COALESCE(?, linked_phone),
                    linked_at = CASE WHEN ? = 'open' AND linked_at IS NULL THEN now() ELSE linked_at END,
                    status = CASE WHEN ? = 'open' THEN 'CONNECTED' ELSE 'DISCONNECTED' END,
                    updated_at = now()
                WHERE id = ? AND provider = 'BAILEYS' AND session_seq < ?
                """,
                seq, state, text(data, "pairingCode"), timestamp(data, "pairingExpiresAt"),
                data.hasNonNull("pairingAttempt") ? data.get("pairingAttempt").asInt() : null,
                truncate(text(data, "error")), "open".equals(state) ? text(data, "phoneNumber") : null,
                state, state, accountId, seq);
        if (updated == 0) {
            return false;
        }
        WaAccount account = accountRepository.findById(accountId).orElseThrow();
        events.publishEvent(new WaChangeEvent(account.getOrganizationId(), WaChangeEvent.Type.SESSION_UPDATED, null, null));
        if (ALERTING.contains(state)) {
            notifyAdmins(account, state);
        }
        return true;
    }

    /** Applies the bot's synchronous answer to a start/stop/logout call (it carries no seq). */
    private void applyDirect(UUID accountId, BaileysBotClient.SessionStatus status) {
        if (status == null) {
            return;
        }
        jdbc.update("""
                UPDATE wa_account SET session_state = ?, pairing_code = ?, pairing_expires_at = ?,
                    pairing_attempt = ?, session_error = ?, last_seen_at = now(), updated_at = now()
                WHERE id = ?
                """, status.state(), status.pairingCode(),
                status.pairingExpiresAt() == null ? null : java.sql.Timestamp.from(Instant.parse(status.pairingExpiresAt())),
                status.pairingAttempt(), truncate(status.error()), accountId);
        accountRepository.findById(accountId).ifPresent(a -> events.publishEvent(
                new WaChangeEvent(a.getOrganizationId(), WaChangeEvent.Type.SESSION_UPDATED, null, null)));
    }

    /**
     * Restarts sessions the bot should be running but is not (its container was recreated, or it
     * dropped a session after an error). Linked sessions resume without pairing; the bot itself
     * also resumes every linked session it finds on its volume at boot.
     */
    @Scheduled(initialDelayString = "${whatsapp.baileys.reconcile-initial-delay-ms:20000}",
            fixedDelayString = "${whatsapp.baileys.reconcile-interval-ms:60000}")
    public void reconcile() {
        if (!properties.isConfigured()) {
            return;
        }
        List<WaAccount> accounts = accountRepository.findAll().stream()
                .filter(a -> a.getProvider() == WaAccount.Provider.BAILEYS)
                .filter(a -> a.getSessionState() != null && RUNNING.contains(a.getSessionState()))
                .filter(a -> !"pairing".equals(a.getSessionState()))
                .toList();
        if (accounts.isEmpty()) {
            return;
        }
        Map<String, BaileysBotClient.SessionStatus> onBot;
        try {
            onBot = bot.list().stream().collect(Collectors.toMap(BaileysBotClient.SessionStatus::id, Function.identity()));
        } catch (BaileysBotClient.BotException e) {
            log.warn("[wa-session] reconcile skipped, bot unreachable: {}", e.getMessage());
            return;
        }
        for (WaAccount account : accounts) {
            BaileysBotClient.SessionStatus status = onBot.get(account.getId().toString());
            if (status != null && RUNNING.contains(status.state())) {
                continue;
            }
            try {
                log.info("[wa-session] restarting session {} (bot reports {})", account.getId(),
                        status == null ? "nothing" : status.state());
                applyDirect(account.getId(), bot.start(account.getId(), null));
            } catch (BaileysBotClient.BotException e) {
                log.warn("[wa-session] could not restart session {}: {}", account.getId(), e.getMessage());
            }
        }
    }

    private void notifyAdmins(WaAccount account, String state) {
        String message = switch (state) {
            case "logged_out" -> "The WhatsApp number was unlinked from the phone. Link it again in Settings → WhatsApp.";
            case "replaced" -> "Another device or server took over the WhatsApp session. Check that only one bot runs it.";
            default -> "Linking the WhatsApp number did not complete. Start again in Settings → WhatsApp.";
        };
        List<UUID> admins = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role = 'ADMIN' AND is_active = true",
                UUID.class, account.getOrganizationId());
        for (UUID admin : admins) {
            Notification n = new Notification();
            n.setOrganizationId(account.getOrganizationId());
            n.setRecipientUserId(admin);
            n.setType(Notification.NotificationType.WHATSAPP);
            n.setTitle("WhatsApp disconnected");
            n.setMessage(message);
            n.setIsRead(false);
            notificationService.createForOrganization(account.getOrganizationId(), n);
        }
    }

    private WaAccount requireBaileys(UUID orgId) {
        WaAccount account = accountRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("No WhatsApp account"));
        if (account.getProvider() != WaAccount.Provider.BAILEYS) {
            throw new IllegalStateException("This organization's WhatsApp account is not a linked phone");
        }
        return account;
    }

    private static String text(JsonNode data, String field) {
        return data.hasNonNull(field) ? data.get(field).asText() : null;
    }

    private static java.sql.Timestamp timestamp(JsonNode data, String field) {
        String value = text(data, field);
        return value == null ? null : java.sql.Timestamp.from(Instant.parse(value));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
