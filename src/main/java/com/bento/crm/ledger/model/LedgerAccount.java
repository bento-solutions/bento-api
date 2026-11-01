package com.bento.crm.ledger.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An account of the chart of accounts (PCGE). Its code never changes once created. */
@Entity
@Table(name = "ledger_account")
@Getter
@Setter
public class LedgerAccount extends BaseTenantEntity {

    @Column(nullable = false, length = 10, updatable = false)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "account_class", nullable = false)
    private short accountClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    public enum Kind { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

    /**
     * The kind an account code implies: class 1 is equity except 14xx..18xx (long-term debt),
     * 2, 3 and 5 are assets, 4 liabilities, 6 and 8 expenses, 7 income.
     */
    public static Kind kindOf(String code) {
        char c = code.charAt(0);
        return switch (c) {
            case '1' -> code.charAt(1) >= '4' && code.charAt(1) <= '8' ? Kind.LIABILITY : Kind.EQUITY;
            case '2', '3', '5' -> Kind.ASSET;
            case '4' -> Kind.LIABILITY;
            case '7' -> Kind.INCOME;
            default -> Kind.EXPENSE;
        };
    }
}
