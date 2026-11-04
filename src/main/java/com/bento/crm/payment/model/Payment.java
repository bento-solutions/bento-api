package com.bento.crm.payment.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single settlement movement against a partner.
 *
 * <p>{@link #amount} is always positive. Whether the movement reads as a debit or a credit
 * depends on which side of the relationship the partner sits on (receivable for customers,
 * payable for vendors), so the sign is applied when the ledger is composed rather than baked
 * into stored data.
 */
@Entity
@Table(name = "payment")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    /** Null for an on-account payment not yet allocated to a specific invoice. */
    @Column(columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Method method;

    private String reference;

    @Column(columnDefinition = "text")
    private String notes;

    /** The bank account the money went through (bank matching fills it); null with the cash register. */
    @Column(columnDefinition = "uuid")
    private UUID bankAccountId;

    /** Paid in cash, through the cash register (5161) rather than a bank account. */
    @Column(nullable = false)
    @Builder.Default
    private boolean cashRegister = false;

    /**
     * An advance: money received (4421) or paid (3411) before the invoice. Applied to an invoice
     * later, an OD entry moves it to the partner's account.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean advance = false;

    /** When an advance was applied to its invoice (the date of the OD entry). */
    private LocalDate advanceAppliedOn;

    public enum Method {
        BANK_TRANSFER, CHECK, CASH, CARD, DIRECT_DEBIT, OTHER,
        /** Paid by an employee out of pocket: only on the lines of an expense report, never on a payment. */
        EMPLOYEE
    }
}
