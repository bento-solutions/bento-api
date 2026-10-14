package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.RecurringInvoice;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecurringInvoiceRequest {

    @NotNull
    private UUID partnerId;

    @NotBlank
    private String name;

    @NotNull
    private RecurringInvoice.Frequency frequency;

    @Min(1)
    @Max(24)
    private Integer intervalCount;

    @NotNull
    private LocalDate startDate;

    private LocalDate endDate;

    @Min(0)
    @Max(365)
    private Integer paymentTermsDays;

    private Boolean autoSend;

    private String customerName;

    private String deliveryAddress;

    private String vatNumber;

    /** Same shape as invoice lines: {@code description}, {@code qty}, {@code unitPrice}, {@code vatRate}. */
    @NotEmpty
    private List<Map<String, Object>> lines;
}
