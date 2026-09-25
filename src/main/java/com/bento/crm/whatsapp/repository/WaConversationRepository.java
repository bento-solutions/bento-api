package com.bento.crm.whatsapp.repository;

import com.bento.crm.whatsapp.model.WaConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversation lookups plus the atomic updates that maintain the activity columns.
 *
 * <p>Each update is one statement, so concurrent ingests of the same conversation serialize on
 * the row lock instead of racing a read-modify-write. Postgres evaluates every SET expression
 * against the row as it was before the statement, so the {@code lastMessageAt} comparisons in
 * the CASE branches all see the previous value. None of them bumps {@code version}: these
 * columns are never written through the entity (see {@code WaConversation}).
 */
@Repository
public interface WaConversationRepository extends JpaRepository<WaConversation, UUID> {

    @Query("SELECT c FROM WaConversation c WHERE c.organizationId = :orgId AND c.phoneE164 = :phone")
    Optional<WaConversation> findByOrgAndPhone(@Param("orgId") UUID orgId, @Param("phone") String phone);

    @Query("SELECT c FROM WaConversation c WHERE c.organizationId = :orgId AND c.id = :id")
    Optional<WaConversation> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT c FROM WaConversation c WHERE c.organizationId = :orgId AND c.partnerId = :partnerId")
    Optional<WaConversation> findByOrgAndPartner(@Param("orgId") UUID orgId, @Param("partnerId") UUID partnerId);

    /**
     * Creates the conversation unless one already exists for the number. ON CONFLICT means a
     * concurrent creator never raises an error (which would poison the caller's transaction):
     * the loser waits for the winner's commit and inserts nothing.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO wa_conversation (id, organization_id, phone_e164, partner_id, created_at, updated_at)
            VALUES (gen_random_uuid(), :orgId, :phone, :partnerId, now(), now())
            ON CONFLICT (organization_id, phone_e164) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orgId") UUID orgId, @Param("phone") String phone, @Param("partnerId") UUID partnerId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WaConversation c SET c.partnerId = :partnerId, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.partnerId IS NULL")
    int linkPartnerIfUnset(@Param("id") UUID id, @Param("partnerId") UUID partnerId);

    /** Links (or re-links, or with null unlinks) the conversation to a partner. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE WaConversation c SET c.partnerId = :partnerId, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.organizationId = :orgId AND c.id = :id")
    int setPartner(@Param("orgId") UUID orgId, @Param("id") UUID id, @Param("partnerId") UUID partnerId);

    /** Records what WhatsApp calls the contact; null arguments leave the stored value alone. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaConversation c SET
                c.waJid = coalesce(:jid, c.waJid),
                c.waLid = coalesce(:lid, c.waLid),
                c.displayName = coalesce(:displayName, c.displayName)
            WHERE c.id = :id
            """)
    int recordIdentity(@Param("id") UUID id, @Param("jid") String jid, @Param("lid") String lid,
                       @Param("displayName") String displayName);

    /**
     * A message from the contact: reopens the 24-hour window (never shortening it, since an
     * offline or history message can be older than one already seen) and moves the inbox preview
     * forward only if this message is the newest.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaConversation c SET
                c.lastInboundAt = greatest(coalesce(c.lastInboundAt, :at), :at),
                c.windowExpiresAt = greatest(coalesce(c.windowExpiresAt, :windowEnd), :windowEnd),
                c.unreadCount = c.unreadCount + :unreadIncrement,
                c.lastMessagePreview = CASE WHEN c.lastMessageAt IS NULL OR c.lastMessageAt <= :at
                                            THEN :preview ELSE c.lastMessagePreview END,
                c.lastMessageDirection = CASE WHEN c.lastMessageAt IS NULL OR c.lastMessageAt <= :at
                                              THEN com.bento.crm.whatsapp.model.WaMessage.Direction.IN
                                              ELSE c.lastMessageDirection END,
                c.lastMessageAt = greatest(coalesce(c.lastMessageAt, :at), :at),
                c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id
            """)
    int recordInbound(@Param("id") UUID id, @Param("at") Instant at, @Param("windowEnd") Instant windowEnd,
                      @Param("preview") String preview, @Param("unreadIncrement") int unreadIncrement);

    /** A message to the contact, from any source. Never touches the service window. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaConversation c SET
                c.lastOutboundAt = greatest(coalesce(c.lastOutboundAt, :at), :at),
                c.lastMessagePreview = CASE WHEN c.lastMessageAt IS NULL OR c.lastMessageAt <= :at
                                            THEN :preview ELSE c.lastMessagePreview END,
                c.lastMessageDirection = CASE WHEN c.lastMessageAt IS NULL OR c.lastMessageAt <= :at
                                              THEN com.bento.crm.whatsapp.model.WaMessage.Direction.OUT
                                              ELSE c.lastMessageDirection END,
                c.lastMessageAt = greatest(coalesce(c.lastMessageAt, :at), :at),
                c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id
            """)
    int recordOutbound(@Param("id") UUID id, @Param("at") Instant at, @Param("preview") String preview);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaConversation c SET
                c.unreadCount = 0,
                c.lastReadAt = greatest(coalesce(c.lastReadAt, :at), :at),
                c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id
            """)
    int markRead(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE WaConversation c SET c.optedOutAt = coalesce(c.optedOutAt, :at), c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id")
    int optOut(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Claims the right to notify about new activity: granted when the conversation just went
     * from read to unread, or the last notification is older than {@code cutoff}. Being a single
     * conditional UPDATE, two concurrent messages cannot both win it.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WaConversation c SET c.lastNotifiedAt = :now
            WHERE c.id = :id
              AND (c.unreadCount <= 1 OR c.lastNotifiedAt IS NULL OR c.lastNotifiedAt < :cutoff)
            """)
    int claimNotification(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
