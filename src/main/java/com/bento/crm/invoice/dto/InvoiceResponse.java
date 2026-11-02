package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.Invoice;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceResponse {

    private UUID id;
    private UUID organizationId;
    private Invoice.InvoiceType type;
    private UUID partnerId;
    private UUID dealId;
    private UUID purchaseOrderId;
    private Invoice.Status status;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private Instant sentAt;
    private Instant paidAt;
    private String customerAccount;
    private String customerName;
    private String deliveryAddress;
    private String vatNumber;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private List<Map<String, Object>> lines;
    private UUID recurringInvoiceId;
    private Integer reminderCount;
    private Instant lastReminderAt;
    private LocalDate scheduledPaymentDate;
    /** Sum of recorded payments against this invoice; set by the controller, not the entity. */
    private BigDecimal amountPaid;
    /** Sum of the credit notes issued against this invoice; set by the controller. */
    private BigDecimal amountCredited;
    /** total - amountPaid - amountCredited (never below 0); set by the controller. */
    private BigDecimal balanceDue;
    private Invoice.Kind kind;
    private UUID sourceProposalId;
    private UUID creditedInvoiceId;
    private String creditReason;
    private List<Map<String, Object>> advanceDeductions;
    /** VAT per code, net of the advance deductions; mentions filled by the controller. */
    private List<VatBreakdownRow> vatBreakdown;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VatBreakdownRow {
        private String vatCode;
        /** Percentage, like the VAT setup's rate (20). */
        private BigDecimal rate;
        private BigDecimal base;
        private BigDecimal vat;
        private String mention;
    }
    private UUID createdBy;
    private UUID updatedBy;
    // These were previously converted with LocalDateTime.from(Instant), which throws
    // DateTimeException at runtime because an Instant carries no local date or time
    // fields. Keeping them as Instant matches the entity and the other DTOs.
    private Instant createdAt;
    private Instant updatedAt;

    public static InvoiceResponse fromEntity(Invoice invoice) {
        return InvoiceResponse.builder()
                .id(invoice.getId())
                .organizationId(invoice.getOrganizationId())
                .type(invoice.getType())
                .partnerId(invoice.getPartnerId())
                .dealId(invoice.getDealId())
                .purchaseOrderId(invoice.getPurchaseOrderId())
                .status(invoice.getStatus())
                .invoiceNumber(invoice.getInvoiceNumber())
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .sentAt(invoice.getSentAt())
                .paidAt(invoice.getPaidAt())
                .customerAccount(invoice.getCustomerAccount())
                .customerName(invoice.getCustomerName())
                .deliveryAddress(invoice.getDeliveryAddress())
                .vatNumber(invoice.getVatNumber())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .total(invoice.getTotal())
                .lines(invoice.getLines())
                .recurringInvoiceId(invoice.getRecurringInvoiceId())
                .reminderCount(invoice.getReminderCount())
                .lastReminderAt(invoice.getLastReminderAt())
                .scheduledPaymentDate(invoice.getScheduledPaymentDate())
                .kind(invoice.getKind() == null ? Invoice.Kind.INVOICE : invoice.getKind())
                .sourceProposalId(invoice.getSourceProposalId())
                .creditedInvoiceId(invoice.getCreditedInvoiceId())
                .creditReason(invoice.getCreditReason())
                .advanceDeductions(invoice.getAdvanceDeductions() == null || invoice.getAdvanceDeductions().isEmpty()
                        ? null : invoice.getAdvanceDeductions())
                .vatBreakdown(com.bento.crm.invoice.service.InvoiceVat.breakdown(invoice, java.util.Map.of()))
                .createdBy(invoice.getCreatedBy())
                .updatedBy(invoice.getUpdatedBy())
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .build();
    }
}
