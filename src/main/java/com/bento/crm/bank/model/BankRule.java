package com.bento.crm.bank.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** "Lines containing X are category Y": checked before the built-in keywords and the AI. */
@Entity
@Table(name = "bank_rule")
@Getter
@Setter
public class BankRule extends BaseTenantEntity {

    @Column(name = "contains_text", nullable = false, length = 120)
    private String containsText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Direction direction = Direction.ANY;

    @Column(nullable = false, length = 40)
    private String category;

    public enum Direction { IN, OUT, ANY }
}
