package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.Invoice;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
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
public class CreateInvoiceRequest {

    @NotNull
    private Invoice.InvoiceType type;

    @NotNull
    private UUID partnerId;

    private UUID dealId;

    private UUID purchaseOrderId;

    @NotNull
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

    /** INVOICE (default) or ADVANCE; credit notes are issued with POST /invoices/{id}/credit-note. */
    private Invoice.Kind kind;

    private UUID sourceProposalId;

    /** A final invoice's deductions: {@code [{advanceInvoiceId, base?, vat?}]}; amounts default to what is left. */
    private List<Map<String, Object>> advanceDeductions;

    /** True: deduct every open advance of the same deal or proposal and partner, in full. */
    private Boolean deductAdvances;
}
