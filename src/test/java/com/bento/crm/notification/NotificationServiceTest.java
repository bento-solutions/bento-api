package com.bento.crm.notification;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.repository.NotificationRepository;
import com.bento.crm.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AppUserRepository userRepository;

    @InjectMocks
    private NotificationService notificationService;

    private UUID tenantOrgId;

    @BeforeEach
    void setUp() {
        tenantOrgId = UUID.randomUUID();
        TenantContext.setCurrentOrganizationId(tenantOrgId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createCrossTenantNotificationDelegatesToNativeInsertQuery() {
        UUID otherOrgId = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();

        notificationService.createCrossTenantNotification(
                otherOrgId,
                recipientUserId,
                Notification.NotificationType.INVITATION,
                "Invitation to join Acme",
                "You have been invited to join Acme",
                "INVITATION",
                invitationId
        );

        verify(notificationRepository).insertCrossTenantNotification(
                eq(otherOrgId),
                eq(recipientUserId),
                eq("INVITATION"),
                eq("Invitation to join Acme"),
                eq("You have been invited to join Acme"),
                eq("INVITATION"),
                eq(invitationId)
        );
    }
}
