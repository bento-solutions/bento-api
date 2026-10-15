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

    @Transactional(readOnly = true)
    public AgingReport report(Invoice.InvoiceType type) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        LocalDate today = billingCalendar.today(orgId);

        Map<UUID, BigDecimal> paidByInvoice = new HashMap<>();
        for (Object[] row : paymentRepository.sumPaidByInvoice(orgId)) {
            paidByInvoice.put((UUID) row[0], (BigDecimal) row[1]);
        }

        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal[] bucketAmounts = zeros(BUCKETS.size());
        int[] bucketCounts = new int[BUCKETS.size()];
        Map<UUID, Acc> byPartner = new LinkedHashMap<>();

        for (Invoice invoice : invoiceRepository.findIssuedByType(orgId, type)) {
            BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;
            BigDecimal settled = paidByInvoice.getOrDefault(invoice.getId(), BigDecimal.ZERO);
            if (invoice.getStatus() == Invoice.Status.PAID) {
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

        return new AgingReport(type, today, scale(invoiced), scale(paid), scale(pending), scale(late), buckets, rows);
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
