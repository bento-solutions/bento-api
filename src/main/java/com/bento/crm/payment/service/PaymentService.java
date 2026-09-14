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

    @Transactional
    public Payment createPayment(CreatePaymentRequest request) {
        Payment payment = new Payment();
        applyRequest(payment, request);
        payment.setOrganizationId(TenantContext.getCurrentOrganizationId());
        Payment saved = paymentRepository.save(payment);
        syncInvoiceStatus(saved.getInvoiceId());
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
        return saved;
    }

    public Page<Payment> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return paymentRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    private void applyRequest(Payment payment, CreatePaymentRequest request) {
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
            }
            invoiceRepository.save(invoice);
        });
    }

    private boolean isOverdue(Invoice invoice) {
        return invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now());
    }
}
