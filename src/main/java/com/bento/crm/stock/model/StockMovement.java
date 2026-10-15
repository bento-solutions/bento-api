package com.bento.crm.stock.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One change to a product's quantity in a warehouse. The ledger the levels are derived from. */
@Entity
@Table(name = "stock_movement")
@Getter
@Setter
public class StockMovement extends BaseTenantEntity {

    public enum Type {
        /** Goods in: a delivered purchase order or a manual receipt. */
        RECEIPT,
        /** Goods out: an invoice, a won deal or a manual issue. */
        ISSUE,
        TRANSFER_IN,
        TRANSFER_OUT,
        /** An inventory count that found more or less than the books said. */
        ADJUSTMENT
    }

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID productId;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    /** Signed: positive into the warehouse, negative out. */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(precision = 15, scale = 2)
    private BigDecimal unitCost;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal balanceAfter;

    /** PURCHASE_ORDER, INVOICE, DEAL, TRANSFER or COUNT. */
    @Column(length = 30)
    private String referenceType;

    @Column(columnDefinition = "uuid")
    private UUID referenceId;

    /** What people recognise the reference by, e.g. BC-2026-0007. */
    @Column(length = 100)
    private String referenceLabel;

    @Column(columnDefinition = "text")
    private String note;
}
