package com.bento.crm.ledger.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A balanced journal entry. Never edited or deleted; corrected by a reversing entry. */
@Entity
@Table(name = "ledger_entry")
@Getter
@Setter
public class LedgerEntry extends BaseTenantEntity {

    @Column(name = "journal_id", nullable = false, columnDefinition = "uuid")
    private UUID journalId;

    @Column(nullable = false, length = 30)
    private String number;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id", columnDefinition = "uuid")
    private UUID sourceId;

    @Column(name = "source_label", length = 160)
    private String sourceLabel;

    @Column(name = "reversal_of_id", columnDefinition = "uuid")
    private UUID reversalOfId;

    @Column(name = "reversed_by_id", columnDefinition = "uuid")
    private UUID reversedById;

    @Column(name = "total_debit", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalCredit;
}
