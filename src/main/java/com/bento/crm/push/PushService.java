package com.bento.crm.push;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.notification.model.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Registers the phones of signed-in users and pushes every in-app notification to them once it
 * is committed. Only the title, the message and the related record's type and id are sent: the
 * app opens the record and loads the rest with the user's own session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    static final int MAX_DEVICES_PER_USER = 10;

    private final PushSender sender;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public record Registration(String platform, String token) {}

    /** Published by NotificationService for every notification it stores. */
    public record NotificationCreated(UUID organizationId, UUID userId, String title, String message,
                                      String entityType, UUID entityId) {
        public static NotificationCreated of(Notification n) {
            return new NotificationCreated(n.getOrganizationId(), n.getRecipientUserId(), n.getTitle(), n.getMessage(),
                    n.getRelatedEntityType(), n.getRelatedEntityId());
        }
    }

    public boolean enabled() {
        return sender.configured();
    }

    @Transactional
    public void register(Registration r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        UUID userId = CurrentUser.idOrNull();
        if (userId == null) throw new IllegalStateException("Sign in to register this device");
        if (r.token() == null || r.token().isBlank() || r.token().length() > 512) {
            throw new IllegalArgumentException("Device token missing or too long");
        }
        DeviceToken.Platform platform;
        try {
            platform = DeviceToken.Platform.valueOf(r.platform() == null ? "" : r.platform().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Platform is ANDROID, IOS or WEB");
        }
        // A phone that changes hands (or accounts) follows its latest user.
        jdbc.update("""
                INSERT INTO device_token (organization_id, user_id, platform, token, last_seen_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (token) DO UPDATE SET organization_id = EXCLUDED.organization_id, user_id = EXCLUDED.user_id,
                    platform = EXCLUDED.platform, last_seen_at = now(), deleted_at = NULL, updated_at = now()
                """, orgId, userId, platform.name(), r.token().trim());
        jdbc.update("""
                UPDATE device_token SET deleted_at = now() WHERE user_id = ? AND deleted_at IS NULL AND id NOT IN (
                  SELECT id FROM device_token WHERE user_id = ? AND deleted_at IS NULL ORDER BY last_seen_at DESC LIMIT ?)
                """, userId, userId, MAX_DEVICES_PER_USER);
    }

    /** Sign-out on the phone. Only the caller's own registration can be removed. */
    @Transactional
    public void unregister(String token) {
        UUID userId = CurrentUser.idOrNull();
        jdbc.update("UPDATE device_token SET deleted_at = now() WHERE token = ? AND user_id = ? AND deleted_at IS NULL",
                token == null ? "" : token.trim(), userId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationCreated n) {
        if (!sender.configured() || n.userId() == null) return;
        List<String> tokens = jdbc.queryForList("SELECT token FROM device_token WHERE organization_id = ? AND user_id = ?"
                + " AND deleted_at IS NULL", String.class, n.organizationId(), n.userId());
        if (tokens.isEmpty()) return;
        Map<String, String> data = new HashMap<>();
        if (n.entityType() != null) data.put("entityType", n.entityType());
        if (n.entityId() != null) data.put("entityId", n.entityId().toString());
        String title = n.title() == null ? "Bento" : n.title();
        String body = n.message() == null ? "" : n.message().length() > 240 ? n.message().substring(0, 237) + "..." : n.message();
        executor.submit(() -> {
            for (String t : tokens) {
                if (sender.send(t, title, body, data) == PushSender.Result.INVALID_TOKEN) {
                    jdbc.update("UPDATE device_token SET deleted_at = now() WHERE token = ?", t);
                }
            }
        });
    }
}
