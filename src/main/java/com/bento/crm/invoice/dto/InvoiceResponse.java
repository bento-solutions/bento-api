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
                .createdBy(invoice.getCreatedBy())
                .updatedBy(invoice.getUpdatedBy())
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .build();
    }
}
