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
        Payment payment = new Payment();
        payment.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(payment, request);
        Payment saved = paymentRepository.save(payment);
        syncInvoiceStatus(saved.getInvoiceId());
        ledger.payment(saved);
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
        payment.setPartnerId(request.getPartnerId());
        payment.setInvoiceId(request.getInvoiceId());
        payment.setPaymentDate(request.getPaymentDate());
        payment.setAmount(request.getAmount());
        payment.setMethod(request.getMethod());
        payment.setReference(request.getReference());
        payment.setNotes(request.getNotes());
    }

    /**
     * Keeps {@code invoice.status} and {@code invoice.paidAt} in step with the payments recorded
     * against it, so the finance list and the partner ledger cannot disagree about whether an
     * invoice is settled.
     */
    private void syncInvoiceStatus(UUID invoiceId) {
        if (invoiceId == null) return;
        UUID orgId = TenantContext.getCurrentOrganizationId();
        invoiceRepository.findByOrganizationIdAndId(orgId, invoiceId).ifPresent(invoice -> {
            BigDecimal paid = paymentRepository.findByOrganizationIdAndInvoiceId(orgId, invoiceId).stream()
                    .map(Payment::getAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;

            if (paid.compareTo(BigDecimal.ZERO) <= 0) {
                invoice.setPaidAt(null);
                invoice.setStatus(isOverdue(invoice) ? Invoice.Status.OVERDUE : Invoice.Status.SENT);
            } else if (paid.compareTo(total) < 0) {
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
