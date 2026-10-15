package com.bento.crm.notification.service;

import com.bento.crm.notification.model.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** In-app notifications for the people who run the back office (admins and managers). */
@Component
@RequiredArgsConstructor
public class TeamNotifier {

    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;

    public void notifyManagers(UUID orgId, String title, String message, String entityType, UUID entityId) {
        List<UUID> recipients = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role IN ('ADMIN', 'MANAGER') AND is_active = true",
                UUID.class, orgId);
        for (UUID userId : recipients) {
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(userId);
            n.setType(Notification.NotificationType.SYSTEM);
            n.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
            n.setMessage(message);
            n.setRelatedEntityType(entityType);
            n.setRelatedEntityId(entityId);
            n.setIsRead(false);
            notificationService.createForOrganization(orgId, n);
        }
    }
}
