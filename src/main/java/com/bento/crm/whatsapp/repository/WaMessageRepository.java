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

    /** Newest page of a thread (cancelled drafts and withdrawn sends are not shown). */
    @Query("""
            SELECT m FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.conversationId = :conversationId
              AND m.status <> com.bento.crm.whatsapp.model.WaMessage.Status.CANCELLED
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<WaMessage> findThreadPage(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId,
                                   org.springframework.data.domain.Pageable page);

    /** The page before the keyset cursor ({@code occurredAt}, {@code id}). */
    @Query("""
            SELECT m FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.conversationId = :conversationId
              AND m.status <> com.bento.crm.whatsapp.model.WaMessage.Status.CANCELLED
              AND (m.occurredAt < :beforeAt OR (m.occurredAt = :beforeAt AND m.id < :beforeId))
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<WaMessage> findThreadPageBefore(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId,
                                         @Param("beforeAt") Instant beforeAt, @Param("beforeId") UUID beforeId,
                                         org.springframework.data.domain.Pageable page);

    // --- outbox ------------------------------------------------------------------------------

    @Query("SELECT m FROM WaMessage m WHERE m.organizationId = :orgId AND m.clientRef = :clientRef")
    Optional<WaMessage> findByOrgAndClientRef(@Param("orgId") UUID orgId, @Param("clientRef") String clientRef);

    @Query("SELECT m FROM WaMessage m WHERE m.organizationId = :orgId AND m.id = :id")
    Optional<WaMessage> findByOrgAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    /** Messages (sent, queued or drafted) an API token created since {@code since}. */
    @Query("SELECT COUNT(m) FROM WaMessage m WHERE m.apiTokenId = :tokenId AND m.createdAt >= :since")
    long countByApiTokenSince(@Param("tokenId") UUID tokenId, @Param("since") Instant since);

    /** Organizations with at least one message ready to send. */
    @Query(value = """
            SELECT DISTINCT organization_id FROM wa_message
            WHERE status = 'QUEUED' AND (not_before IS NULL OR not_before <= now())
            """, nativeQuery = true)
    List<UUID> findOrgsWithDueMessages();

    /**
     * The next sendable messages of one account, locked. SKIP LOCKED lets a concurrent claimer
     * (another instance, a kick racing the poll) move past rows already being claimed instead of
     * waiting on them.
     */
    @Query(value = """
            SELECT * FROM wa_message
            WHERE organization_id = :orgId AND status = 'QUEUED'
              AND (not_before IS NULL OR not_before <= now())
            ORDER BY priority DESC, created_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WaMessage> lockDueQueued(@Param("orgId") UUID orgId, @Param("limit") int limit);

    @Query("""
            SELECT COUNT(m) > 0 FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.status = com.bento.crm.whatsapp.model.WaMessage.Status.SENDING
            """)
    boolean existsSending(@Param("orgId") UUID orgId);

    /** When the CRM last put a message on the wire for this account (phone-typed ones excluded). */
    @Query("""
            SELECT max(m.sentAt) FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.direction = com.bento.crm.whatsapp.model.WaMessage.Direction.OUT
              AND m.source <> com.bento.crm.whatsapp.model.WaMessage.Source.PHONE
            """)
    Instant lastCrmSendAt(@Param("orgId") UUID orgId);

    @Query("""
            SELECT COUNT(m) FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.lane = com.bento.crm.whatsapp.model.WaMessage.Lane.OUTREACH
              AND m.sentAt >= :since
            """)
    long countOutreachSentSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("""
            SELECT COUNT(m) FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.newChat = true AND m.sentAt >= :since
            """)
    long countNewChatsSentSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("""
            SELECT COUNT(m) > 0 FROM WaMessage m
            WHERE m.organizationId = :orgId AND m.conversationId = :conversationId
              AND m.direction = com.bento.crm.whatsapp.model.WaMessage.Direction.OUT
              AND m.status NOT IN (com.bento.crm.whatsapp.model.WaMessage.Status.DRAFT,
                                   com.bento.crm.whatsapp.model.WaMessage.Status.CANCELLED,
                                   com.bento.crm.whatsapp.model.WaMessage.Status.FAILED)
            """)
    boolean hasOutbound(@Param("orgId") UUID orgId, @Param("conversationId") UUID conversationId);

    /**
     * Completes a successful send. Also applies when the reaper had already requeued or failed
     * the message (the call was merely slow); never walks back a receipt that arrived first.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET
                m.status = CASE WHEN m.status IN (com.bento.crm.whatsapp.model.WaMessage.Status.SENDING,
                                                  com.bento.crm.whatsapp.model.WaMessage.Status.QUEUED,
                                                  com.bento.crm.whatsapp.model.WaMessage.Status.FAILED)
                                THEN com.bento.crm.whatsapp.model.WaMessage.Status.SENT ELSE m.status END,
                m.wamid = coalesce(m.wamid, :wamid),
                m.sentAt = coalesce(m.sentAt, :now),
                m.occurredAt = :now,
                m.claimedAt = null,
                m.errorCode = null,
                m.errorTitle = null,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.id = :id AND m.status <> com.bento.crm.whatsapp.model.WaMessage.Status.CANCELLED
            """)
    int completeSent(@Param("id") UUID id, @Param("wamid") String wamid, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET
                m.status = com.bento.crm.whatsapp.model.WaMessage.Status.QUEUED,
                m.notBefore = :retryAt, m.claimedAt = null,
                m.errorCode = :errorCode, m.errorTitle = :errorTitle,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.id = :id AND m.status = com.bento.crm.whatsapp.model.WaMessage.Status.SENDING
            """)
    int requeue(@Param("id") UUID id, @Param("retryAt") Instant retryAt,
                @Param("errorCode") String errorCode, @Param("errorTitle") String errorTitle);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET
                m.status = com.bento.crm.whatsapp.model.WaMessage.Status.FAILED,
                m.claimedAt = null, m.errorCode = :errorCode, m.errorTitle = :errorTitle,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.id = :id AND m.status = com.bento.crm.whatsapp.model.WaMessage.Status.SENDING
            """)
    int fail(@Param("id") UUID id, @Param("errorCode") String errorCode, @Param("errorTitle") String errorTitle);

    /** Withdraws a message that has not been handed to the provider yet. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET m.status = com.bento.crm.whatsapp.model.WaMessage.Status.CANCELLED,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.organizationId = :orgId AND m.id = :id
              AND m.status IN (com.bento.crm.whatsapp.model.WaMessage.Status.DRAFT,
                               com.bento.crm.whatsapp.model.WaMessage.Status.QUEUED)
            """)
    int cancelIfUnsent(@Param("orgId") UUID orgId, @Param("id") UUID id);

    /** Promotes a draft into the queue, optionally with the approver's edited text. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE WaMessage m SET m.status = com.bento.crm.whatsapp.model.WaMessage.Status.QUEUED,
                m.body = coalesce(:body, m.body), m.approvedByUserId = :approverId,
                m.notBefore = :now, m.occurredAt = :now, m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.organizationId = :orgId AND m.id = :id
              AND m.status = com.bento.crm.whatsapp.model.WaMessage.Status.DRAFT
            """)
    int approveDraft(@Param("orgId") UUID orgId, @Param("id") UUID id, @Param("body") String body,
                     @Param("approverId") UUID approverId, @Param("now") Instant now);

    /** Sends whose provider call never reported back (the instance died mid-call). */
    @Query("""
            SELECT m FROM WaMessage m
            WHERE m.status = com.bento.crm.whatsapp.model.WaMessage.Status.SENDING AND m.claimedAt < :cutoff
            """)
    List<WaMessage> findStaleSending(@Param("cutoff") Instant cutoff);
}
