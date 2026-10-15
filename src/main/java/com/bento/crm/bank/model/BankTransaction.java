package com.bento.crm.bank.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One line of a bank statement. A positive amount came in, a negative one went out. */
@Entity
@Table(name = "bank_transaction")
@Getter
@Setter
public class BankTransaction extends BaseTenantEntity {

    @Column(name = "bank_account_id", nullable = false, columnDefinition = "uuid")
    private UUID bankAccountId;

    @Column(name = "import_id", columnDefinition = "uuid")
    private UUID importId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 120)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 40)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_source", length = 10)
    private CategorySource categorySource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.UNMATCHED;

    @Column(name = "invoice_id", columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(name = "payment_id", columnDefinition = "uuid")
    private UUID paymentId;

    @Column(name = "expense_id", columnDefinition = "uuid")
    private UUID expenseId;

    @Column(name = "payment_created", nullable = false)
    private boolean paymentCreated;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    public enum Status { UNMATCHED, MATCHED, IGNORED }

    public enum CategorySource { RULE, KEYWORD, AI, MANUAL, MATCH }
}
