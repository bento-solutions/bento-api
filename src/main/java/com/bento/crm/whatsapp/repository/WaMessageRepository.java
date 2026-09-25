package com.bento.crm.whatsapp.repository;

import com.bento.crm.whatsapp.model.WaMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WaMessageRepository extends JpaRepository<WaMessage, UUID> {

    /** Idempotency lookup. wamid is unique per organization, not globally. */
    @Query("SELECT m FROM WaMessage m WHERE m.organizationId = :orgId AND m.wamid = :wamid")
    Optional<WaMessage> findByOrgAndWamid(@Param("orgId") UUID orgId, @Param("wamid") String wamid);

    @Query("SELECT COUNT(m) > 0 FROM WaMessage m WHERE m.organizationId = :orgId AND m.wamid = :wamid")
    boolean existsByOrgAndWamid(@Param("orgId") UUID orgId, @Param("wamid") String wamid);

    @Query("""
            SELECT m FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.conversationId = :conversationId
            ORDER BY m.occurredAt ASC, m.id ASC
            """)
    List<WaMessage> findConversationTimeline(@Param("orgId") UUID orgId,
                                             @Param("conversationId") UUID conversationId);

    /**
     * Moves an outbound message to {@code status} if it is currently in one of {@code from}.
     * Receipts are unordered (a 'delivered' can land after 'read') and can race the sender's own
     * completion, so the transition is a compare-and-set rather than an entity update.
     *
     * @return 1 if the message advanced, 0 if it is unknown or already further along
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET
                m.status = :status,
                m.errorCode = coalesce(:errorCode, m.errorCode),
                m.errorTitle = coalesce(:errorTitle, m.errorTitle),
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.organizationId = :orgId AND m.wamid = :wamid
              AND m.direction = com.bento.crm.whatsapp.model.WaMessage.Direction.OUT
              AND m.status IN :from
            """)
    int advanceStatus(@Param("orgId") UUID orgId, @Param("wamid") String wamid,
                      @Param("status") WaMessage.Status status, @Param("from") Collection<WaMessage.Status> from,
                      @Param("errorCode") String errorCode, @Param("errorTitle") String errorTitle);

    /**
     * Records receipt timestamps, keeping the first one seen for each. Applied even when the
     * status itself does not advance, so an out-of-order 'delivered' still fills deliveredAt.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET
                m.sentAt = coalesce(m.sentAt, :sentAt),
                m.deliveredAt = coalesce(m.deliveredAt, :deliveredAt),
                m.readAt = coalesce(m.readAt, :readAt)
            WHERE m.organizationId = :orgId AND m.wamid = :wamid
              AND m.direction = com.bento.crm.whatsapp.model.WaMessage.Direction.OUT
            """)
    int recordReceiptTimes(@Param("orgId") UUID orgId, @Param("wamid") String wamid,
                           @Param("sentAt") Instant sentAt, @Param("deliveredAt") Instant deliveredAt,
                           @Param("readAt") Instant readAt);
}
