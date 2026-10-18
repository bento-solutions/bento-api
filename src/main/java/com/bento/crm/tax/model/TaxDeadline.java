package com.bento.crm.tax.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "tax_deadline")
@Getter
@Setter
public class TaxDeadline extends BaseTenantEntity {

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    private UUID profileId;

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "period_label", nullable = false, length = 20)
    private String periodLabel;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.TODO;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by", columnDefinition = "uuid")
    private UUID doneBy;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 500)
    private String note;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    public enum Kind { TVA, IS_INSTALLMENT, IS_RETURN, IR_SALARIES, CNSS, IR_ANNUAL_SALARIES }

    public enum Status { TODO, DONE, NOT_APPLICABLE }
}
