package com.bento.crm.notification.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.notification.dto.CreateNotificationRequest;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AppUserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               AppUserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    private static UUID getCurrentUserId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    public Page<Notification> listForCurrentUser(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return notificationRepository.findByRecipient(orgId, getCurrentUserId(), pageable);
    }

    /**
     * Creates a notification for a recipient in the caller's own organization.
     *
     * <p>The recipient is resolved against the tenant rather than trusted from the body, so a
     * notification can never be addressed into another organization's inbox.
     */
    @Transactional
    public Notification create(CreateNotificationRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        userRepository.findByOrganizationIdAndId(orgId, request.getRecipientUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient user not found"));
        return createForOrganization(orgId, request.toEntity());
    }

    /**
     * Creates a notification for an explicitly supplied tenant.
     *
     * <p>Needed by callers that run outside a authenticated request — the inbound
     * WhatsApp webhook and the relance scheduler — where the {@code TenantContext}
     * ThreadLocal is never populated and {@link #create} would throw.
     */
    @Transactional
    public Notification createForOrganization(UUID organizationId, Notification notification) {
        notification.setOrganizationId(organizationId);
        if (notification.getIsRead() == null) {
            notification.setIsRead(false);
        }
        return notificationRepository.save(notification);
    }

    /**
     * Inserts an in-app notification directly for a user in another organization
     * (e.g. inviting an existing user to join a new organization).
     *
     * <p>Uses a native SQL query with Propagation.REQUIRES_NEW so that:
     * 1. {@link com.bento.crm.common.model.TenantEntityListener} does not throw {@code CrossTenantWriteException}
     *    due to mismatch with the caller's {@code TenantContext}.
     * 2. Any failure in notification delivery cannot mark the parent transaction as rollback-only.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void createCrossTenantNotification(UUID organizationId,
                                              UUID recipientUserId,
                                              Notification.NotificationType type,
                                              String title,
                                              String message,
                                              String relatedEntityType,
                                              UUID relatedEntityId) {
        notificationRepository.insertCrossTenantNotification(
                organizationId,
                recipientUserId,
                type != null ? type.name() : Notification.NotificationType.SYSTEM.name(),
                title,
                message,
                relatedEntityType,
                relatedEntityId
        );
    }


    @Transactional
    public Notification markRead(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Notification notification = notificationRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    @Transactional
    public void markAllReadForCurrentUser() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        UUID userId = getCurrentUserId();
        Page<Notification> page = notificationRepository.findByRecipient(orgId, userId, Pageable.unpaged());
        page.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(page);
    }
}
