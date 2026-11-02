package com.bento.crm.payment.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.payment.dto.CreatePaymentRequest;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.bento.crm.ledger.service.LedgerAutoPostingService ledger;

    @Transactional
    public Payment createPayment(CreatePaymentRequest request) {
        return createPayment(request, true);
    }

    /**
     * {@code post = false} leaves the posting to the caller: bank matching links the payment to
     * its line first, so a line still to validate keeps the payment out of the ledger.
     */
    @Transactional
    public Payment createPayment(CreatePaymentRequest request, boolean post) {
        Payment payment = new Payment();
        payment.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(payment, request);
        Payment saved = paymentRepository.save(payment);
        syncInvoiceStatus(saved.getInvoiceId());
        if (post) ledger.payment(saved);
        return saved;
    }

    public Payment getPayment(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return paymentRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    public Page<Payment> listPayments(Pageable pageable) {
        return paymentRepository.findByOrganizationId(TenantContext.getCurrentOrganizationId(), pageable);
    }

    public List<Payment> listPaymentsByPartner(UUID partnerId) {
        return paymentRepository.findByOrganizationIdAndPartnerId(
                TenantContext.getCurrentOrganizationId(), partnerId);
    }

    public List<Payment> listPaymentsByInvoice(UUID invoiceId) {
        return paymentRepository.findByOrganizationIdAndInvoiceId(
                TenantContext.getCurrentOrganizationId(), invoiceId);
    }

    @Transactional
    public Payment updatePayment(UUID id, CreatePaymentRequest request) {
        Payment payment = getPayment(id);
        UUID previousInvoiceId = payment.getInvoiceId();
        applyRequest(payment, request);
        Payment saved = paymentRepository.save(payment);
        ledger.payment(saved);
        syncInvoiceStatus(saved.getInvoiceId());
        // Reallocating a payment leaves the invoice it used to settle short, so that one has to
        // be re-evaluated too.
        if (previousInvoiceId != null && !previousInvoiceId.equals(saved.getInvoiceId())) {
            syncInvoiceStatus(previousInvoiceId);
        }
        return saved;
    }

    @Transactional
    public void deletePayment(UUID id) {
        Payment payment = getPayment(id);
        UUID invoiceId = payment.getInvoiceId();
        payment.setDeletedAt(Instant.now());
        paymentRepository.save(payment);
        paymentRepository.flush();
        syncInvoiceStatus(invoiceId);
        ledger.payment(payment);
    }

    @Transactional
    public Payment restorePayment(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Payment payment = paymentRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        payment.setDeletedAt(null);
        Payment saved = paymentRepository.save(payment);
        paymentRepository.flush();
        syncInvoiceStatus(saved.getInvoiceId());
        ledger.payment(saved);
        return saved;
    }

    public Page<Payment> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return paymentRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    /**
     * Sets payment.bankAccountId / cashRegister from the request: an explicit bank account wins,
     * then an explicit cashRegister, then the method (CASH = cash register, else the main bank
     * account). An edit that says neither and keeps the method keeps the payment's account.
     */
    private void applyTreasury(Payment payment, CreatePaymentRequest request, boolean isNew, Payment.Method before) {
        UUID orgId = payment.getOrganizationId();
        if (request.getBankAccountId() != null) {
            Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_account WHERE organization_id = ?"
                    + " AND id = ? AND deleted_at IS NULL)", Boolean.class, orgId, request.getBankAccountId());
            if (!Boolean.TRUE.equals(known)) {
                throw new IllegalArgumentException("Unknown bank account");
            }
            payment.setBankAccountId(request.getBankAccountId());
            payment.setCashRegister(false);
            return;
        }
        if (request.getCashRegister() != null) {
            payment.setCashRegister(request.getCashRegister());
            if (request.getCashRegister()) {
                payment.setBankAccountId(null);
            } else if (payment.getBankAccountId() == null) {
                payment.setBankAccountId(mainBankAccount(orgId));
            }
            return;
        }
        if (isNew || before != payment.getMethod()) {
            boolean cash = payment.getMethod() == Payment.Method.CASH;
            payment.setCashRegister(cash);
            payment.setBankAccountId(cash ? null : payment.getBankAccountId() != null && !isNew
                    ? payment.getBankAccountId() : mainBankAccount(orgId));
        }
    }

    private UUID mainBankAccount(UUID orgId) {
        java.util.List<UUID> ids = jdbc.queryForList("SELECT id FROM bank_account WHERE organization_id = ?"
                + " AND deleted_at IS NULL ORDER BY is_active DESC, created_at LIMIT 1", UUID.class, orgId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void applyRequest(Payment payment, CreatePaymentRequest request) {
        boolean isNew = payment.getId() == null;
        Payment.Method before = payment.getMethod();
        applyFields(payment, request);
        applyTreasury(payment, request, isNew, before);
    }

    private void applyFields(Payment payment, CreatePaymentRequest request) {
        if (request.getAdvance() != null) {
            payment.setAdvance(request.getAdvance());
        }
        if (request.getInvoiceId() != null) {
            checkInvoice(payment.getOrganizationId(), request.getPartnerId(), request.getInvoiceId(), payment.isAdvance());
        }
        if (payment.isAdvance()) {
            if (request.getInvoiceId() == null) {
                payment.setAdvanceAppliedOn(null);
            } else if (!request.getInvoiceId().equals(payment.getInvoiceId()) || payment.getAdvanceAppliedOn() == null) {
                payment.setAdvanceAppliedOn(request.getPaymentDate());
            }
        } else {
            payment.setAdvanceAppliedOn(null);
        }
        payment.setPartnerId(request.getPartnerId());
        payment.setInvoiceId(request.getInvoiceId());
        payment.setPaymentDate(request.getPaymentDate());
        payment.setAmount(request.getAmount());
        payment.setMethod(request.getMethod());
        payment.setReference(request.getReference());
        payment.setNotes(request.getNotes());
    }

    /** A payment's invoice: the organization's, of the same partner, issued, not a credit note. */
    private void checkInvoice(UUID orgId, UUID partnerId, UUID invoiceId, boolean advance) {
        Invoice inv = invoiceRepository.findByOrganizationIdAndId(orgId, invoiceId).orElse(null);
        if (inv == null) {
            if (advance) throw new IllegalArgumentException("Invoice not found");
            return;
        }
        if (inv.getKind() == Invoice.Kind.CREDIT_NOTE) {
            throw new IllegalArgumentException("A payment cannot settle a credit note");
        }
        if (advance && partnerId != null && !partnerId.equals(inv.getPartnerId())) {
            throw new IllegalArgumentException("The invoice is for another partner");
        }
    }

    // Advances

    /** Unapplied advance payments, of a partner when given, oldest first. */
    public List<Payment> openAdvances(UUID partnerId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return paymentRepository.findByOrganizationId(orgId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(p -> p.isAdvance() && p.getInvoiceId() == null)
                .filter(p -> partnerId == null || partnerId.equals(p.getPartnerId()))
                .sorted(java.util.Comparator.comparing(Payment::getPaymentDate))
                .toList();
    }

    /**
     * Applies an advance payment to an invoice: the invoice's balance due drops, and an OD entry
     * moves the amount from 4421 to the customer's account (or from the supplier's account to 3411).
     */
    @Transactional
    public Payment applyAdvance(UUID id, UUID invoiceId) {
        Payment payment = getPayment(id);
        if (!payment.isAdvance()) {
            throw new IllegalArgumentException("This payment is not an advance");
        }
        if (payment.getInvoiceId() != null) {
            throw new IllegalArgumentException("This advance is already applied to an invoice");
        }
        if (invoiceId == null) {
            throw new IllegalArgumentException("The invoice is required");
        }
        UUID orgId = payment.getOrganizationId();
        Invoice inv = invoiceRepository.findByOrganizationIdAndId(orgId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        checkInvoice(orgId, payment.getPartnerId(), invoiceId, true);
        if (inv.getStatus() == Invoice.Status.DRAFT) {
            throw new IllegalArgumentException("This invoice is still a draft");
        }
        BigDecimal owed = (inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal())
                .subtract(paidAmount(orgId, invoiceId)).subtract(creditedAmount(orgId, invoiceId));
        if (payment.getAmount().compareTo(owed.add(new BigDecimal("0.01"))) > 0) {
            throw new IllegalArgumentException("The advance is more than what is still owed on the invoice ("
                    + owed.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ")");
        }
        payment.setInvoiceId(invoiceId);
        LocalDate today = LocalDate.now();
        payment.setAdvanceAppliedOn(payment.getPaymentDate() != null && payment.getPaymentDate().isAfter(today)
                ? payment.getPaymentDate() : today);
        Payment saved = paymentRepository.saveAndFlush(payment);
        syncInvoiceStatus(orgId, invoiceId);
        ledger.payment(saved);
        return saved;
    }

    private BigDecimal paidAmount(UUID orgId, UUID invoiceId) {
        return paymentRepository.findByOrganizationIdAndInvoiceId(orgId, invoiceId).stream()
                .map(Payment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The total of the live credit notes issued against an invoice. */
    public BigDecimal creditedAmount(UUID orgId, UUID invoiceId) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM invoice WHERE organization_id = ?"
                + " AND credited_invoice_id = ? AND kind = 'CREDIT_NOTE' AND deleted_at IS NULL", BigDecimal.class, orgId, invoiceId);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Re-derives an invoice's status from its payments and credit notes. */
    @Transactional
    public void refreshInvoiceStatus(UUID orgId, UUID invoiceId) {
        syncInvoiceStatus(orgId, invoiceId);
    }

    private void syncInvoiceStatus(UUID invoiceId) {
        if (invoiceId == null) return;
        syncInvoiceStatus(TenantContext.getCurrentOrganizationId(), invoiceId);
    }

    /**
     * Keeps {@code invoice.status} and {@code invoice.paidAt} in step with the payments recorded
     * against it (and, from phase 3, the credit notes issued against it), so the finance list and
     * the partner ledger cannot disagree about whether an invoice is settled.
     */
    private void syncInvoiceStatus(UUID orgId, UUID invoiceId) {
        if (invoiceId == null) return;
        invoiceRepository.findByOrganizationIdAndId(orgId, invoiceId).ifPresent(invoice -> {
            if (invoice.getKind() == Invoice.Kind.CREDIT_NOTE) return;
            BigDecimal paid = paidAmount(orgId, invoiceId);
            BigDecimal credited = creditedAmount(orgId, invoiceId);
            BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;

            if (credited.signum() > 0 && credited.compareTo(total) >= 0) {
                invoice.setStatus(Invoice.Status.CREDITED);
                invoice.setPaidAt(null);
            } else if (paid.compareTo(BigDecimal.ZERO) <= 0) {
                invoice.setPaidAt(null);
                invoice.setStatus(isOverdue(invoice) ? Invoice.Status.OVERDUE : Invoice.Status.SENT);
            } else if (paid.add(credited).compareTo(total) < 0) {
                invoice.setPaidAt(null);
                invoice.setStatus(Invoice.Status.PARTIALLY_PAID);
            } else {
                invoice.setStatus(Invoice.Status.PAID);
                if (invoice.getPaidAt() == null) {
                    invoice.setPaidAt(Instant.now());
                }
                // A settled invoice has nothing left to pay online: close its open payment link.
                jdbc.update("UPDATE payment_link SET status = 'CANCELLED', updated_at = now()"
                        + " WHERE organization_id = ? AND invoice_id = ? AND status = 'OPEN' AND deleted_at IS NULL",
                        orgId, invoiceId);
            }
            invoiceRepository.save(invoice);
        });
    }

    private boolean isOverdue(Invoice invoice) {
        return invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now());
    }
}
