package com.bento.crm.purchaseorder.dto;

import com.bento.crm.purchaseorder.model.PurchaseOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderResponse {

    private UUID id;
    private UUID organizationId;
    private UUID dealId;
    private UUID vendorPartnerId;
    private PurchaseOrder.Status status;
    private String orderNumber;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    private String sentVia;
    private java.math.BigDecimal subtotal;
    private java.math.BigDecimal tax;
    private java.math.BigDecimal total;
    private String notes;
    private java.util.List<java.util.Map<String, Object>> lines;
    private UUID createdBy;
    private UUID updatedBy;
    // Kept as Instant to match the entity: LocalDateTime.from(Instant) throws DateTimeException
    // at runtime because an Instant carries no local date or time fields.
    private Instant createdAt;
    private Instant updatedAt;

    public static PurchaseOrderResponse fromEntity(PurchaseOrder purchaseOrder) {
        return PurchaseOrderResponse.builder()
                .id(purchaseOrder.getId())
                .organizationId(purchaseOrder.getOrganizationId())
                .dealId(purchaseOrder.getDealId())
                .vendorPartnerId(purchaseOrder.getVendorPartnerId())
                .status(purchaseOrder.getStatus())
                .orderNumber(purchaseOrder.getOrderNumber())
                .orderDate(purchaseOrder.getOrderDate())
                .deliveryDate(purchaseOrder.getDeliveryDate())
                .sentVia(purchaseOrder.getSentVia())
                .subtotal(purchaseOrder.getSubtotal())
                .tax(purchaseOrder.getTax())
                .total(purchaseOrder.getTotal())
                .notes(purchaseOrder.getNotes())
                .lines(purchaseOrder.getLines())
                .createdBy(purchaseOrder.getCreatedBy())
                .updatedBy(purchaseOrder.getUpdatedBy())
                .createdAt(purchaseOrder.getCreatedAt())
                .updatedAt(purchaseOrder.getUpdatedAt())
                .build();
    }
}
