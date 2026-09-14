package com.bento.crm.purchaseorder.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "purchase_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrder extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID dealId;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID vendorPartnerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String orderNumber;

    private LocalDate orderDate;

    private LocalDate deliveryDate;

    private String sentVia;

    private BigDecimal subtotal;

    private BigDecimal tax;

    private BigDecimal total;

    @Column(columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> lines = new ArrayList<>();

    public enum Status {
        DRAFT, SENT, CONFIRMED, DELIVERED, INVOICED
    }
}
