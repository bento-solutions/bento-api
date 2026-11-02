package com.bento.crm.invoice.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.invoice.dto.AgingReport;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receivables and payables aging report: what is still owed on issued invoices, split by how
 * many days past due it is (not yet due, 1–30, 31–60, 61–90, over 90), overall and per partner.
 */
@Service
@RequiredArgsConstructor
public class AgingService {

    static final List<String> BUCKETS = List.of("CURRENT", "D1_30", "D31_60", "D61_90", "D90_PLUS");

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PartnerRepository partnerRepository;
    private final BillingCalendar billingCalendar;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** What has been paid against each invoice of the organization, from recorded payments. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> paidByInvoice(UUID orgId) {
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (Object[] row : paymentRepository.sumPaidByInvoice(orgId)) {
            paid.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return paid;
    }

    /** What has been credited on each invoice of the organization, from its live credit notes. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> creditedByInvoice(UUID orgId) {
        Map<UUID, BigDecimal> credited = new HashMap<>();
        jdbc.query("SELECT credited_invoice_id, SUM(total) FROM invoice WHERE organization_id = ? AND kind = 'CREDIT_NOTE'"
                + " AND credited_invoice_id IS NOT NULL AND deleted_at IS NULL GROUP BY credited_invoice_id", rs -> {
            credited.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
        }, orgId);
        return credited;
    }

    @Transactional(readOnly = true)
    public AgingReport report(Invoice.InvoiceType type) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        LocalDate today = billingCalendar.today(orgId);

        Map<UUID, BigDecimal> paidByInvoice = paidByInvoice(orgId);
        // Credit notes lower what their invoice still owes (credit notes themselves are not listed).
        Map<UUID, BigDecimal> creditedByInvoice = creditedByInvoice(orgId);

        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal[] bucketAmounts = zeros(BUCKETS.size());
        int[] bucketCounts = new int[BUCKETS.size()];
        Map<UUID, Acc> byPartner = new LinkedHashMap<>();

        for (Invoice invoice : invoiceRepository.findIssuedByType(orgId, type)) {
            BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;
            BigDecimal settled = paidByInvoice.getOrDefault(invoice.getId(), BigDecimal.ZERO)
                    .add(creditedByInvoice.getOrDefault(invoice.getId(), BigDecimal.ZERO));
            if (invoice.getStatus() == Invoice.Status.PAID || invoice.getStatus() == Invoice.Status.CREDITED) {
                // A PAID status settles the invoice even when the payment was not recorded.
                settled = total;
            }
            settled = settled.min(total);
            invoiced = invoiced.add(total);
            paid = paid.add(settled);
            BigDecimal remaining = total.subtract(settled);
            if (remaining.signum() <= 0) {
                continue;
            }
            int daysLate = invoice.getDueDate() == null ? 0
                    : (int) Math.max(0, ChronoUnit.DAYS.between(invoice.getDueDate(), today));
            int bucket = bucketOf(daysLate);
            bucketAmounts[bucket] = bucketAmounts[bucket].add(remaining);
            bucketCounts[bucket]++;

            Acc acc = byPartner.computeIfAbsent(invoice.getPartnerId(), id -> new Acc());
            acc.amounts[bucket] = acc.amounts[bucket].add(remaining);
            acc.open++;
            acc.oldest = Math.max(acc.oldest, daysLate);
            if (acc.fallbackName == null) {
                acc.fallbackName = invoice.getCustomerName();
            }
            LocalDate scheduled = invoice.getScheduledPaymentDate();
            if (scheduled != null && (acc.nextScheduled == null || scheduled.isBefore(acc.nextScheduled))) {
                acc.nextScheduled = scheduled;
            }
        }

        List<AgingReport.Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < BUCKETS.size(); i++) {
            buckets.add(new AgingReport.Bucket(BUCKETS.get(i), scale(bucketAmounts[i]), bucketCounts[i]));
        }
        BigDecimal pending = bucketAmounts[0];
        BigDecimal late = BigDecimal.ZERO;
        for (int i = 1; i < bucketAmounts.length; i++) {
            late = late.add(bucketAmounts[i]);
        }

        Map<UUID, String> names = new HashMap<>();
        if (!byPartner.isEmpty()) {
            for (Partner p : partnerRepository.findAllById(byPartner.keySet())) {
                if (orgId.equals(p.getOrganizationId())) {
                    names.put(p.getId(), p.getName());
                }
            }
        }
        List<AgingReport.PartnerRow> rows = new ArrayList<>();
        byPartner.forEach((partnerId, acc) -> {
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal a : acc.amounts) {
                total = total.add(a);
            }
            rows.add(new AgingReport.PartnerRow(partnerId,
                    names.getOrDefault(partnerId, acc.fallbackName != null ? acc.fallbackName : "—"),
                    scale(acc.amounts[0]), scale(acc.amounts[1]), scale(acc.amounts[2]),
                    scale(acc.amounts[3]), scale(acc.amounts[4]), scale(total),
                    acc.open, acc.oldest, acc.nextScheduled));
        });
        rows.sort(Comparator.comparing(AgingReport.PartnerRow::total).reversed());

        List<AgingReport.Advance> advances = advances(orgId, type, null);
        BigDecimal advancesTotal = advances.stream().map(AgingReport.Advance::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AgingReport(type, today, scale(invoiced), scale(paid), scale(pending), scale(late), buckets, rows,
                advances, scale(advancesTotal), scale(pending.add(late).subtract(advancesTotal)));
    }

    /**
     * Open advances of one side (and partner, when given): unapplied advance payments, and paid
     * advance invoices with an amount (incl. VAT) not yet deducted on a final invoice.
     */
    @Transactional(readOnly = true)
    public List<AgingReport.Advance> advances(UUID orgId, Invoice.InvoiceType type, UUID partnerId) {
        List<AgingReport.Advance> out = new ArrayList<>();
        boolean vendor = type == Invoice.InvoiceType.VENDOR;
        jdbc.query("""
                SELECT p.id, p.partner_id, COALESCE(NULLIF(pa.company_name, ''), pa.name), p.reference, p.payment_date, p.amount
                FROM payment p JOIN partner pa ON pa.id = p.partner_id
                WHERE p.organization_id = ? AND p.deleted_at IS NULL AND p.advance AND p.invoice_id IS NULL
                  AND (CAST(? AS uuid) IS NULL OR p.partner_id = CAST(? AS uuid))
                  AND COALESCE((SELECT b.amount < 0 FROM bank_transaction b WHERE b.payment_id = p.id AND b.deleted_at IS NULL LIMIT 1),
                               pa.type = 'VENDOR') = ?
                ORDER BY p.payment_date
                """, rs -> {
            out.add(new AgingReport.Advance("PAYMENT", rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class), scale(rs.getBigDecimal(6))));
        }, orgId, partnerId, partnerId, vendor);
        jdbc.query("""
                SELECT i.id, i.partner_id, COALESCE(NULLIF(pa.company_name, ''), pa.name, i.customer_name), i.invoice_number,
                       i.invoice_date,
                       i.total - COALESCE((SELECT SUM((d->>'base')::numeric + (d->>'vat')::numeric)
                                           FROM invoice f, jsonb_array_elements(f.advance_deductions) d
                                           WHERE f.organization_id = i.organization_id AND f.deleted_at IS NULL
                                             AND f.kind = 'INVOICE' AND d->>'advanceInvoiceId' = i.id::text), 0) AS open
                FROM invoice i LEFT JOIN partner pa ON pa.id = i.partner_id
                WHERE i.organization_id = ? AND i.deleted_at IS NULL AND i.kind = 'ADVANCE' AND i.status = 'PAID'
                  AND i.type = ? AND (CAST(? AS uuid) IS NULL OR i.partner_id = CAST(? AS uuid))
                ORDER BY i.invoice_date
                """, rs -> {
            BigDecimal open = rs.getBigDecimal(6);
            if (open != null && open.signum() > 0) {
                out.add(new AgingReport.Advance("ADVANCE_INVOICE", rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class), scale(open)));
            }
        }, orgId, type.name(), partnerId, partnerId);
        return out;
    }

    static int bucketOf(int daysLate) {
        if (daysLate <= 0) return 0;
        if (daysLate <= 30) return 1;
        if (daysLate <= 60) return 2;
        if (daysLate <= 90) return 3;
        return 4;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Acc {
        final BigDecimal[] amounts = zeros(BUCKETS.size());
        int open;
        int oldest;
        String fallbackName;
        LocalDate nextScheduled;
    }
}
