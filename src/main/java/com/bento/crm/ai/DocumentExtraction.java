package com.bento.crm.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a model read from a document. Every field may be null; callers validate before using it.
 *
 * @param documentType vendor_invoice, credit_note, receipt, bank_statement, quote or other
 * @param confidence   the model's own 0..1 estimate that the fields are right
 */
public record DocumentExtraction(
        String documentType,
        String supplierName,
        String supplierIce,
        String supplierIf,
        String supplierEmail,
        String supplierPhone,
        String supplierAddress,
        String buyerName,
        String buyerIce,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        List<Line> lines,
        Double confidence,
        String notes) {

    public record Line(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal vatRate, BigDecimal total) {}
}
