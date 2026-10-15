package com.bento.crm.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a customer holding a payment link may see: enough to recognise the invoice and pay it.
 * No partner ids, no other invoices, no internal notes.
 */
public record PublicInvoiceView(
        String organizationName,
        UUID logoFileId,
        String accentColor,
        String customerName,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String currency,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal remaining,
        String status,
        boolean testMode,
        List<Line> lines) {

    public record Line(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {}
}
