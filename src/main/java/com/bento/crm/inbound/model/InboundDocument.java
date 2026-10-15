package com.bento.crm.inbound.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A document waiting to be read and booked, or already handled. */
@Entity
@Table(name = "inbound_document")
@Getter
@Setter
public class InboundDocument extends BaseTenantEntity {

    @Column(name = "mailbox_id", columnDefinition = "uuid")
    private UUID mailboxId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "file_id", nullable = false, columnDefinition = "uuid")
    private UUID fileId;

    @Column(length = 320)
    private String sender;

    @Column(length = 500)
    private String subject;

    /** The email's sender domain was authenticated by the receiving server. */
    @Column(name = "sender_verified", nullable = false)
    private boolean senderVerified;

    @Column(name = "message_id", length = 500)
    private String messageId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** The fields read from the document, as shown and edited in the review screen. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> extraction;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "review_reason", length = 255)
    private String reviewReason;

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Column(name = "invoice_id", columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(length = 500)
    private String error;

    @Column(name = "processed_at")
    private Instant processedAt;

    public enum Channel { EMAIL, WHATSAPP, UPLOAD }

    public enum Kind { VENDOR_INVOICE, BANK_STATEMENT, OTHER }

    public enum Status {
        /** Waiting for the reader. */
        QUEUED,
        /** Read and booked automatically, or booked after review. */
        BOOKED,
        /** Read, but a person must check it before it is booked. */
        NEEDS_REVIEW,
        /** Filed for later use (a bank statement waiting for reconciliation, a client document). */
        FILED,
        /** Set aside by a person, or not a document we book. */
        IGNORED,
        /** The same file arrived before. */
        DUPLICATE,
        /** Reading failed after several attempts. */
        FAILED
    }
}
