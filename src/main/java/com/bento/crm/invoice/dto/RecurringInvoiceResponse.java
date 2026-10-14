package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.RecurringInvoice;
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
public class RecurringInvoiceResponse {

    private UUID id;
    private UUID partnerId;
    private String name;
    private RecurringInvoice.Frequency frequency;
    private Integer intervalCount;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate nextRunDate;
    private RecurringInvoice.Status status;
    private Integer paymentTermsDays;
    private Boolean autoSend;
    private String customerName;
    private String deliveryAddress;
    private String vatNumber;
    private List<Map<String, Object>> lines;
    /** What each generated invoice will total, computed from the lines. */
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private Integer generatedCount;
    /** True once a period has been billed or skipped: frequency and start date can no longer change. */
    private Boolean scheduleLocked;
    private Instant lastRunAt;
    private UUID lastInvoiceId;
    private Instant createdAt;
    private Instant updatedAt;
}
