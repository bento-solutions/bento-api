package com.bento.crm.purchaseorder.dto;

import com.bento.crm.purchaseorder.model.PurchaseOrder;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePurchaseOrderRequest {

    @NotNull
    private UUID dealId;

    @NotNull
    private UUID vendorPartnerId;

    @NotNull
    private PurchaseOrder.Status status;

    private String orderNumber;

    private LocalDate orderDate;

    private LocalDate deliveryDate;

    private String sentVia;

    private BigDecimal subtotal;

    private BigDecimal tax;

    private BigDecimal total;

    private String notes;

    private List<Map<String, Object>> lines;
}
