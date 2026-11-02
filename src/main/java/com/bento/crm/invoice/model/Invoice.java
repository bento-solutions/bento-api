package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceType type;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(columnDefinition = "uuid")
    private UUID dealId;

    @Column(columnDefinition = "uuid")
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Human-facing document number, e.g. {@code FA-2026-0042}. */
    private String invoiceNumber;

    /**
     * The date the document was issued, which is what the ledger sorts and reports on.
     * Distinct from {@code createdAt}, so a back-dated invoice imported from an ERP lands in
     * the right place.
     */
    private LocalDate invoiceDate;

    private LocalDate dueDate;

    private Instant sentAt;

    private Instant paidAt;

    private String customerAccount;

    private String customerName;

    @Column(columnDefinition = "text")
    private String deliveryAddress;

    private String vatNumber;

    private BigDecimal subtotal;

    private BigDecimal tax;

    private BigDecimal total;

    // NOT NULL in the schema: an invoice with no itemised lines (manual total) must still save.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> lines = new ArrayList<>();

    /** The template this invoice was generated from, when it came from one. */
    @Column(columnDefinition = "uuid")
    private UUID recurringInvoiceId;

    /** Automatic reminders sent so far. */
    @Column(nullable = false)
    @Builder.Default
    private Integer reminderCount = 0;

    private Instant lastReminderAt;

    /** Supplier bills: the day the organization plans to pay it. */
    private LocalDate scheduledPaymentDate;

    /** INVOICE, ADVANCE (facture d'acompte) or CREDIT_NOTE (avoir). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Kind kind = Kind.INVOICE;

    /** The proposal (quote) an advance or final invoice comes from. */
    @Column(columnDefinition = "uuid")
    private UUID sourceProposalId;

    /** A credit note: the invoice it credits. */
    @Column(columnDefinition = "uuid")
    private UUID creditedInvoiceId;

    /** A credit note: why it was issued. */
    @Column(columnDefinition = "text")
    private String creditReason;

    /**
     * A final invoice: the advances it deducts, as {@code {advanceInvoiceId, base, vat, vatCode,
     * number, date}}. Subtotal and tax are net of them.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> advanceDeductions = new ArrayList<>();

    public enum InvoiceType {
        CUSTOMER, VENDOR
    }

    public enum Status {
        DRAFT, SENT, PARTIALLY_PAID, PAID, OVERDUE, CREDITED
    }

    public enum Kind {
        INVOICE, ADVANCE, CREDIT_NOTE
    }
}
