package com.bento.crm.expense.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An expense report (note de frais): expenses an employee paid out of pocket. Its lines are
 * {@link Expense} rows pointing at it. Posted, it books each line to its category account and the
 * amount owed to the employee to 4438; reimbursed, it clears 4438 from the bank or the cash register.
 */
@Entity
@Table(name = "expense_report")
@Getter
@Setter
public class ExpenseReport extends BaseTenantEntity {

    public enum Status { DRAFT, POSTED, REIMBURSED, CANCELLED }

    public enum ReimbursementMethod { BANK_TRANSFER, CASH }

    /** NF-YYYY-NNNN, allocated the first time the report is posted and kept when it is unposted. */
    @Column(length = 30)
    private String number;

    @Column(columnDefinition = "uuid")
    private UUID employeeUserId;

    @Column(nullable = false, length = 160)
    private String employeeName;

    private String title;

    private LocalDate periodFrom;

    private LocalDate periodTo;

    @Column(columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.DRAFT;

    private Instant postedAt;

    private Instant reimbursedAt;

    /** The day the employee was paid back (the reimbursement entry's date). */
    private LocalDate reimbursementDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReimbursementMethod reimbursementMethod;

    @Column(columnDefinition = "uuid")
    private UUID reimbursementBankAccountId;

    /** The cost center of lines that have none. */
    @Column(columnDefinition = "uuid")
    private UUID costCenterId;
}
