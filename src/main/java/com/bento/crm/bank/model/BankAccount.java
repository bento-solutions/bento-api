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

/** One of the company's bank accounts. Statements are matched to it by account number. */
@Entity
@Table(name = "bank_account")
@Getter
@Setter
public class BankAccount extends BaseTenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Bank bank;

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @Column(nullable = false, length = 3)
    private String currency = "MAD";

    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "opening_date")
    private LocalDate openingDate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public enum Bank { ATTIJARIWAFA, BANK_OF_AFRICA, CIH, BANQUE_POPULAIRE, SOCIETE_GENERALE, BMCI, CREDIT_AGRICOLE, CREDIT_DU_MAROC, AL_BARID, OTHER }
}
