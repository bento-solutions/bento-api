package com.bento.crm.notification.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "notification")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String message;

    private String relatedEntityType;

    @Column(columnDefinition = "uuid")
    private UUID relatedEntityId;

    private Boolean isRead;

    public enum NotificationType {
        DEAL, LEAD, TASK, TICKET, SYSTEM, MENTION,
        /** A contact replied to a WhatsApp campaign message. */
        WHATSAPP,
        /** An invitation to join an organization or team. */
        INVITATION
    }
}
