package com.bento.crm.whatsapp.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every message in or out, forming the conversation timeline the CRM renders, and — while
 * QUEUED/SENDING — the outbound queue itself.
 *
 * <p>{@code wamid} is WhatsApp's message id and is unique per organization: it is the
 * idempotency key that makes redelivered webhooks harmless. Status transitions driven by
 * receipts are applied with conditional UPDATEs in {@link
 * com.bento.crm.whatsapp.repository.WaMessageRepository}, so a receipt racing the send's own
 * completion can neither be lost nor move a message backwards.
 */
@Entity
@Table(name = "wa_message")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaMessage extends BaseTenantEntity {

    @Column(name = "conversation_id", nullable = false, columnDefinition = "uuid")
    private UUID conversationId;

    @Column(name = "campaign_id", columnDefinition = "uuid")
    private UUID campaignId;

    @Column(name = "recipient_id", columnDefinition = "uuid")
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    private String wamid;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "template_name")
    private String templateName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_params", columnDefinition = "jsonb")
    private List<String> templateParams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_title", length = 500)
    private String errorTitle;

    /** 0 for the initial campaign send, 1 for the J+3 relance, and so on. */
    @Column(name = "sequence_step")
    private Integer sequenceStep;

    @Column(name = "sent_by_user_id", columnDefinition = "uuid")
    private UUID sentByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    /** When the message happened on WhatsApp; the thread is ordered by this. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Lane lane;

    /** Whether this send opens a chat the account has never messaged (counts toward the daily cap). */
    @Column(name = "new_chat", nullable = false)
    private boolean newChat;

    /** Outbox ordering within an account: human sends before agent sends before campaigns. */
    @Column(nullable = false)
    private int priority;

    /** Earliest time the outbox may send this (pacing, business hours, retry backoff). */
    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(nullable = false)
    private int attempts;

    /** The API token an agent used to create this message, for audit and per-token caps. */
    @Column(name = "api_token_id", columnDefinition = "uuid")
    private UUID apiTokenId;

    /** Who approved an agent's draft into the queue. */
    @Column(name = "approved_by_user_id", columnDefinition = "uuid")
    private UUID approvedByUserId;

    /** Caller-supplied idempotency key, unique per organization. */
    @Column(name = "client_ref", length = 100)
    private String clientRef;

    @Column(name = "media_type", length = 20)
    private String mediaType;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "quoted_wamid", length = 128)
    private String quotedWamid;

    @PrePersist
    void defaultTimelineFields() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (source == null) {
            source = direction == Direction.IN ? Source.CONTACT
                    : campaignId != null ? Source.CAMPAIGN : Source.HUMAN;
        }
    }

    public enum Direction {
        OUT, IN
    }

    public enum Status {
        /** An agent's proposed message, waiting for a human to approve, edit or discard it. */
        DRAFT,
        QUEUED,
        /** Claimed by the outbox worker; the provider call is in flight. */
        SENDING,
        SENT, DELIVERED, READ, FAILED, RECEIVED,
        /** Withdrawn before it was sent. */
        CANCELLED
    }

    public enum Source {
        CONTACT, HUMAN, AGENT, CAMPAIGN, PHONE
    }

    /**
     * Outbound pacing lane. REPLY answers a contact who wrote in the last 24 hours; OUTREACH
     * starts or revives a conversation and is held to stricter limits.
     */
    public enum Lane {
        REPLY, OUTREACH
    }
}
