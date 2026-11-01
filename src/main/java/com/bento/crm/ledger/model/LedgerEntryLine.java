package com.bento.crm.ledger.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One debit or credit of an entry. */
@Entity
@Table(name = "ledger_entry_line")
@Getter
@Setter
public class LedgerEntryLine extends BaseTenantEntity {

    @Column(name = "entry_id", nullable = false, columnDefinition = "uuid")
    private UUID entryId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    private UUID accountId;

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Column(name = "cost_center_id", columnDefinition = "uuid")
    private UUID costCenterId;

    @Column(length = 255)
    private String label;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;
}
