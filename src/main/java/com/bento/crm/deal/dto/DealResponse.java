package com.bento.crm.deal.dto;

import com.bento.crm.deal.model.Deal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealResponse {

    private UUID id;
    private UUID organizationId;
    private UUID partnerId;
    private UUID proposalId;
    private String title;
    private Deal.DealStage stage;
    private BigDecimal amount;
    private BigDecimal discount;
    private String comments;
    private String orderNumber;
    private LocalDate orderDate;
    private LocalDate requestedDeliveryDate;
    private LocalDate estimatedDeliveryDate;
    private LocalDate expectedDeliveryDateVendor;
    private LocalDate deliveryDate;
    private String customerAccount;
    private String billingAddress;
    private String deliveryAddress;
    private String contactPerson;
    private String contactEmail;
    private String contactPhone;
    private UUID salesPersonUserId;
    private String salesRegion;
    private String currency;
    private String paymentTerms;
    private BigDecimal orderTotalAmount;
    private String vendorAccount;
    private String purchaseOrderRef;
    private String warehouseAddress;
    private String transportationService;
    private List<OrderLineDto> orderLines;
    /** Advance invoices issued on the deal: their total (incl. VAT), and whether they are all paid. */
    private boolean advancePaid;
    private java.math.BigDecimal advanceTotal;
    /** Missions made for the deal (phase 3 part C). */
    @Builder.Default
    private List<UUID> missionIds = new java.util.ArrayList<>();
    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DealResponse fromEntity(Deal deal) {
        return DealResponse.builder()
                .id(deal.getId())
                .organizationId(deal.getOrganizationId())
                .partnerId(deal.getPartnerId())
                .proposalId(deal.getProposalId())
                .title(deal.getTitle())
                .stage(deal.getStage())
                .amount(deal.getAmount())
                .discount(deal.getDiscount())
                .comments(deal.getComments())
                .orderNumber(deal.getOrderNumber())
                .orderDate(deal.getOrderDate())
                .requestedDeliveryDate(deal.getRequestedDeliveryDate())
                .estimatedDeliveryDate(deal.getEstimatedDeliveryDate())
                .expectedDeliveryDateVendor(deal.getExpectedDeliveryDateVendor())
                .deliveryDate(deal.getDeliveryDate())
                .customerAccount(deal.getCustomerAccount())
                .billingAddress(deal.getBillingAddress())
                .deliveryAddress(deal.getDeliveryAddress())
                .contactPerson(deal.getContactPerson())
                .contactEmail(deal.getContactEmail())
                .contactPhone(deal.getContactPhone())
                .salesPersonUserId(deal.getSalesPersonUserId())
                .salesRegion(deal.getSalesRegion())
                .currency(deal.getCurrency())
                .paymentTerms(deal.getPaymentTerms())
                .orderTotalAmount(deal.getOrderTotalAmount())
                .vendorAccount(deal.getVendorAccount())
                .purchaseOrderRef(deal.getPurchaseOrderRef())
                .warehouseAddress(deal.getWarehouseAddress())
                .transportationService(deal.getTransportationService())
                .orderLines(deal.getOrderLines().stream().map(OrderLineDto::fromEntity).toList())
                .createdBy(deal.getCreatedBy())
                .updatedBy(deal.getUpdatedBy())
                .createdAt(deal.getCreatedAt() != null ?
                    LocalDateTime.ofInstant(deal.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(deal.getUpdatedAt() != null ?
                    LocalDateTime.ofInstant(deal.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
