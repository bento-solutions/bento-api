package com.bento.crm.invoice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Turning a purchase order into the supplier's bill. Every field is optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillFromPurchaseOrderRequest {

    /** The number printed on the supplier's invoice; a FRS-… number is generated when empty. */
    @Size(max = 100)
    private String supplierInvoiceNumber;

    /** Defaults to today. */
    private LocalDate invoiceDate;

    /** Defaults to the invoice date plus {@code paymentTermsDays}. */
    private LocalDate dueDate;

    @Min(0)
    @Max(365)
    private Integer paymentTermsDays;
}
