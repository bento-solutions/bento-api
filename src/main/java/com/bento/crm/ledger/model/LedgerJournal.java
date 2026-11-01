package com.bento.crm.ledger.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A journal (VT, AC, BQ, CA, OD, AN), seeded per organization. */
@Entity
@Table(name = "ledger_journal")
@Getter
@Setter
public class LedgerJournal extends BaseTenantEntity {

    @Column(nullable = false, length = 4)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    public enum Kind { SALES, PURCHASES, BANK, CASH, MISC, OPENING }
}
