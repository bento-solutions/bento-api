package com.bento.crm.partner.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.partner.dto.PartnerLedgerResponse;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerLedgerService {

    private final PartnerService partnerService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final com.bento.crm.invoice.service.AgingService agingService;

    @Transactional(readOnly = true)
    public PartnerLedgerResponse getLedger(UUID partnerId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Partner partner = partnerService.getPartner(partnerId);

        List<Invoice> invoices = invoiceRepository.findByOrganizationIdAndPartnerId(orgId, partnerId);
        List<Payment> payments = paymentRepository.findByOrganizationIdAndPartnerId(orgId, partnerId);

        Map<UUID, BigDecimal> paidByInvoice = new HashMap<>();
        for (Object[] row : paymentRepository.sumPaidByInvoiceForPartner(orgId, partnerId)) {
            paidByInvoice.put((UUID) row[0], (BigDecimal) row[1]);
        }

        boolean payable = partner.getType() == Partner.PartnerType.VENDOR;
        LocalDate today = LocalDate.now();

        List<PartnerLedgerResponse.OpenInvoice> open = new ArrayList<>();
        List<PartnerLedgerResponse.ClosedInvoice> closed = new ArrayList<>();
        List<PartnerLedgerResponse.Transaction> transactions = new ArrayList<>();

        BigDecimal totalInvoiced = BigDecimal.ZERO;
        BigDecimal totalOpen = BigDecimal.ZERO;
        BigDecimal totalOverdue = BigDecimal.ZERO;
        BigDecimal totalCredited = BigDecimal.ZERO;
        Map<UUID, BigDecimal> creditedByInvoice = agingService.creditedByInvoice(orgId);

        for (Invoice invoice : invoices) {
            BigDecimal total = nz(invoice.getTotal());
            LocalDate issued = issueDate(invoice);
            if (invoice.getKind() == Invoice.Kind.CREDIT_NOTE) {
                // A credit note lowers what is owed, like a payment.
                if (invoice.getStatus() != Invoice.Status.DRAFT) {
                    totalCredited = totalCredited.add(total);
                    transactions.add(PartnerLedgerResponse.Transaction.builder()
                            .date(issued)
                            .type(PartnerLedgerResponse.TransactionType.CREDIT_NOTE)
                            .documentNumber(documentNumber(invoice))
                            .amount(signedAmount(total, true, payable))
                            .build());
                }
                continue;
            }
            BigDecimal paid = nz(paidByInvoice.get(invoice.getId()));
            BigDecimal remaining = total.subtract(paid).subtract(nz(creditedByInvoice.get(invoice.getId())));

            totalInvoiced = totalInvoiced.add(total);

            transactions.add(PartnerLedgerResponse.Transaction.builder()
                    .date(issued)
                    .type(PartnerLedgerResponse.TransactionType.INVOICE)
                    .documentNumber(documentNumber(invoice))
                    .amount(signedAmount(total, false, payable))
                    .build());

            if (remaining.compareTo(BigDecimal.ZERO) > 0 && invoice.getStatus() != Invoice.Status.CREDITED) {
                boolean overdue = invoice.getDueDate() != null && invoice.getDueDate().isBefore(today);
                totalOpen = totalOpen.add(remaining);
                if (overdue) {
                    totalOverdue = totalOverdue.add(remaining);
                }
                open.add(PartnerLedgerResponse.OpenInvoice.builder()
                        .id(invoice.getId())
                        .invoiceNumber(documentNumber(invoice))
                        .invoiceDate(issued)
                        .dueDate(invoice.getDueDate())
                        .totalAmount(total)
                        .paidAmount(paid)
                        .remainingAmount(remaining)
                        .vatAmount(nz(invoice.getTax()))
                        .status(invoice.getStatus() != null ? invoice.getStatus().name() : null)
                        .overdue(overdue)
                        .build());
            } else {
                closed.add(PartnerLedgerResponse.ClosedInvoice.builder()
                        .id(invoice.getId())
                        .invoiceNumber(documentNumber(invoice))
                        .paymentDate(settlementDate(invoice, payments))
                        .invoiceDate(issued)
                        .dueDate(invoice.getDueDate())
                        .totalAmount(total)
                        .vatAmount(nz(invoice.getTax()))
                        .build());
            }
        }

        BigDecimal totalPaid = BigDecimal.ZERO;
        for (Payment payment : payments) {
            BigDecimal amount = nz(payment.getAmount());
            totalPaid = totalPaid.add(amount);
            transactions.add(PartnerLedgerResponse.Transaction.builder()
                    .date(payment.getPaymentDate())
                    .type(PartnerLedgerResponse.TransactionType.PAYMENT)
                    .documentNumber(payment.getReference())
                    .amount(signedAmount(amount, true, payable))
                    .build());
        }

        transactions.sort(Comparator
                .comparing(PartnerLedgerResponse.Transaction::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // Within a day an invoice is raised before it is settled, so ordering by type
                // keeps a same-day invoice-and-payment pair readable as a running balance.
                .thenComparing(PartnerLedgerResponse.Transaction::getType));

        BigDecimal balance = transactions.stream()
                .map(PartnerLedgerResponse.Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<com.bento.crm.invoice.dto.AgingReport.Advance> advances = agingService.advances(orgId,
                payable ? Invoice.InvoiceType.VENDOR : Invoice.InvoiceType.CUSTOMER, partnerId);
        BigDecimal totalAdvances = advances.stream().map(com.bento.crm.invoice.dto.AgingReport.Advance::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PartnerLedgerResponse.builder()
                .advances(advances)
                .partnerId(partnerId)
                .partnerType(partner.getType())
                .openInvoices(open)
                .closedInvoices(closed)
                .transactions(transactions)
                .totals(PartnerLedgerResponse.Totals.builder()
                        .totalInvoiced(totalInvoiced)
                        .totalPaid(totalPaid)
                        .totalOpen(totalOpen)
                        .totalOverdue(totalOverdue)
                        .balance(balance)
                        .totalCredited(totalCredited)
                        .totalAdvances(totalAdvances)
                        .netBalance(totalOpen.subtract(totalAdvances))
                        .build())
                .build();
    }

    /**
     * The one place the ledger's sign convention lives.
     *
     * <p>For a customer the relationship is a receivable: invoicing increases what they owe
     * (positive) and a payment reduces it (negative). For a vendor it is a payable, so both
     * flip. Every caller goes through here, which is what keeps the supplier view a mirror of
     * the customer view rather than a second implementation free to drift.
     */
    private BigDecimal signedAmount(BigDecimal amount, boolean isPayment, boolean payable) {
        boolean negative = isPayment != payable;
        return negative ? amount.negate() : amount;
    }

    /** Falls back to the insert date for invoices predating the {@code invoiceDate} column. */
    private LocalDate issueDate(Invoice invoice) {
        if (invoice.getInvoiceDate() != null) return invoice.getInvoiceDate();
        return invoice.getCreatedAt() != null
                ? invoice.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : null;
    }

    /** The invoice number if one was assigned, otherwise the id so the row is never blank. */
    private String documentNumber(Invoice invoice) {
        return invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()
                ? invoice.getInvoiceNumber()
                : String.valueOf(invoice.getId());
    }

    /**
     * The date a closed invoice was settled: the last payment allocated to it, falling back to
     * {@code paidAt} for invoices marked paid directly from the finance list without a payment
     * record behind them.
     */
    private LocalDate settlementDate(Invoice invoice, List<Payment> payments) {
        LocalDate last = payments.stream()
                .filter(p -> invoice.getId().equals(p.getInvoiceId()))
                .map(Payment::getPaymentDate)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (last != null) return last;
        return invoice.getPaidAt() != null
                ? invoice.getPaidAt().atZone(ZoneOffset.UTC).toLocalDate()
                : null;
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
