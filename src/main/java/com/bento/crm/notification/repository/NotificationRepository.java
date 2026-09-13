package com.bento.crm.notification.repository;

import com.bento.crm.notification.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE n.organizationId = :organizationId AND n.recipientUserId = :recipientUserId ORDER BY n.createdAt DESC")
    Page<Notification> findByRecipient(@Param("organizationId") UUID organizationId, @Param("recipientUserId") UUID recipientUserId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.organizationId = :organizationId AND n.id = :id")
    Optional<Notification> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Modifying
    @Query(value = "INSERT INTO notification (id, organization_id, recipient_user_id, type, title, message, related_entity_type, related_entity_id, is_read, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :organizationId, :recipientUserId, :type, :title, :message, :relatedEntityType, :relatedEntityId, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            nativeQuery = true)
    void insertCrossTenantNotification(@Param("organizationId") UUID organizationId,
                                        @Param("recipientUserId") UUID recipientUserId,
                                        @Param("type") String type,
                                        @Param("title") String title,
                                        @Param("message") String message,
                                        @Param("relatedEntityType") String relatedEntityType,
                                        @Param("relatedEntityId") UUID relatedEntityId);
}

