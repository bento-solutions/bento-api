package com.bento.crm.invoice.dto;

import com.bento.crm.invoice.model.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What customers owe (or what is owed to suppliers), by how late it is.
 *
 * @param paid        settled so far on issued invoices
 * @param pending     still owed, not yet due
 * @param late        still owed, past due
 * @param buckets     outstanding by days past due: CURRENT, D1_30, D31_60, D61_90, D90_PLUS
 * @param partners    the same per customer or supplier, largest balance first
 */
public record AgingReport(
        Invoice.InvoiceType type,
        LocalDate asOf,
        BigDecimal invoiced,
        BigDecimal paid,
        BigDecimal pending,
        BigDecimal late,
        List<Bucket> buckets,
        List<PartnerRow> partners) {

    public record Bucket(String key, BigDecimal amount, int count) {
    }

    public record PartnerRow(
            UUID partnerId,
            String name,
            BigDecimal current,
            BigDecimal d1to30,
            BigDecimal d31to60,
            BigDecimal d61to90,
            BigDecimal d90plus,
            BigDecimal total,
            int openInvoices,
            int oldestDaysLate,
            LocalDate nextScheduledPayment) {
    }
}
