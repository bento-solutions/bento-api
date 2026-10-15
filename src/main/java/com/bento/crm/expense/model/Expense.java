package com.bento.crm.expense.model;

import com.bento.crm.common.model.BaseTenantEntity;
import com.bento.crm.payment.model.Payment;
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

/** Money spent outside supplier bills: fuel, rent, a restaurant receipt. Amounts are TTC. */
@Entity
@Table(name = "expense")
@Getter
@Setter
public class Expense extends BaseTenantEntity {

    /** Suggested categories; any other text is accepted too. */
    public static final java.util.List<String> CATEGORIES = java.util.List.of(
            "TRAVEL", "FUEL", "MEALS", "OFFICE", "RENT", "UTILITIES", "TELECOM", "SOFTWARE",
            "MARKETING", "FEES", "SALARIES", "TAXES", "OTHER");

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false, length = 60)
    private String category;

    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(precision = 15, scale = 2)
    private BigDecimal vatAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.Method paymentMethod;

    @Column(columnDefinition = "uuid")
    private UUID vendorPartnerId;

    private String vendorName;

    @Column(length = 100)
    private String reference;
}
