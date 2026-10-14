package com.bento.crm.invoice.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.dto.RecurringInvoiceRequest;
import com.bento.crm.invoice.dto.RecurringInvoiceResponse;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.model.RecurringInvoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.invoice.repository.RecurringInvoiceRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recurring invoice templates and the invoices they produce.
 *
 * <p>The request-scoped methods work in the caller's organization. {@link #generateDue} is the
 * scheduler's entry point: it runs outside any request and takes the organization from the
 * template it locks.
 */
@Service
@RequiredArgsConstructor
public class RecurringInvoiceService {

    /** Most periods one run will catch up on, should the job have been down for a long time. */
    static final int MAX_CATCH_UP = 12;

    private final RecurringInvoiceRepository recurringInvoiceRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final PartnerRepository partnerRepository;
    private final BillingCalendar billingCalendar;

    @Transactional
    public RecurringInvoice create(RecurringInvoiceRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        requirePartner(orgId, request.getPartnerId());
        if (request.getStartDate().isBefore(billingCalendar.today(orgId))) {
            throw new IllegalArgumentException("The start date cannot be in the past");
        }
        RecurringInvoice template = new RecurringInvoice();
        template.setOrganizationId(orgId);
        applySchedule(template, request);
        applyContent(template, request);
        template.setStatus(RecurringInvoice.Status.ACTIVE);
        template.scheduleNext();
        return recurringInvoiceRepository.save(template);
    }

    public RecurringInvoice get(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return recurringInvoiceRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Recurring invoice not found"));
    }

    public Page<RecurringInvoice> list(Pageable pageable) {
        return recurringInvoiceRepository.findByOrganizationId(TenantContext.getCurrentOrganizationId(), pageable);
    }

    /**
     * Updates a template. What each invoice contains can always change; when it repeats can
     * change only before the first invoice, because later occurrences are counted from the
     * start date. The end date can always move, which can end or revive the template.
     */
    @Transactional
    public RecurringInvoice update(UUID id, RecurringInvoiceRequest request) {
        RecurringInvoice template = get(id);
        requirePartner(template.getOrganizationId(), request.getPartnerId());

        boolean scheduleChanged = template.getFrequency() != request.getFrequency()
                || !Objects.equals(template.getIntervalCount(), orDefault(request.getIntervalCount(), 1))
                || !template.getStartDate().equals(request.getStartDate());
        if (scheduleChanged) {
            if (template.getPeriodIndex() > 0) {
                throw new IllegalStateException(
                        "The frequency and start date cannot change once the template has billed a period; create a new one instead");
            }
            if (request.getStartDate().isBefore(billingCalendar.today(template.getOrganizationId()))) {
                throw new IllegalArgumentException("The start date cannot be in the past");
            }
        }
        applySchedule(template, request);
        applyContent(template, request);

        if (template.getStatus() == RecurringInvoice.Status.ENDED) {
            template.setStatus(RecurringInvoice.Status.ACTIVE);
        }
        template.scheduleNext();
        return recurringInvoiceRepository.save(template);
    }

    @Transactional
    public RecurringInvoice pause(UUID id) {
        RecurringInvoice template = get(id);
        if (template.getStatus() == RecurringInvoice.Status.ACTIVE) {
            template.setStatus(RecurringInvoice.Status.PAUSED);
        }
        return recurringInvoiceRepository.save(template);
    }

    /**
     * Resumes a paused template from the next period that has not started yet: the periods
     * that passed while it was paused are skipped, not billed in one go.
     */
    @Transactional
    public RecurringInvoice resume(UUID id) {
        RecurringInvoice template = get(id);
        if (template.getStatus() != RecurringInvoice.Status.PAUSED) {
            return template;
        }
        LocalDate today = billingCalendar.today(template.getOrganizationId());
        while (template.occurrence(template.getPeriodIndex()).isBefore(today)) {
            template.setPeriodIndex(template.getPeriodIndex() + 1);
        }
        template.setStatus(RecurringInvoice.Status.ACTIVE);
        template.scheduleNext();
        return recurringInvoiceRepository.save(template);
    }

    @Transactional
    public void delete(UUID id) {
        RecurringInvoice template = get(id);
        template.setDeletedAt(Instant.now());
        recurringInvoiceRepository.save(template);
    }

    /** Bills the next period now, dated today, and moves the schedule on by one period. */
    @Transactional
    public Generated generateNow(UUID id) {
        RecurringInvoice template = get(id);
        if (template.getStatus() == RecurringInvoice.Status.ENDED) {
            throw new IllegalStateException("This recurring invoice has ended");
        }
        LocalDate today = billingCalendar.today(template.getOrganizationId());
        Generated generated = bill(template, today);
        template.scheduleNext();
        recurringInvoiceRepository.save(template);
        return generated;
    }

    public List<Invoice> invoicesOf(UUID id) {
        RecurringInvoice template = get(id);
        return invoiceRepository.findByOrganizationIdAndRecurringInvoiceId(template.getOrganizationId(), template.getId());
    }

    /**
     * Generates every period of one template that is due on or before {@code today}. Runs in
     * its own transaction under a row lock, so a template another instance is generating is
     * skipped rather than billed twice.
     *
     * @return the invoices created, for the caller to email once this transaction has committed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Generated> generateDue(UUID id) {
        Optional<RecurringInvoice> locked = recurringInvoiceRepository.lockById(id);
        if (locked.isEmpty()) {
            return List.of();
        }
        RecurringInvoice template = locked.get();
        LocalDate today = billingCalendar.today(template.getOrganizationId());
        List<Generated> created = new ArrayList<>();
        while (template.getStatus() == RecurringInvoice.Status.ACTIVE
                && template.getNextRunDate() != null
                && !template.getNextRunDate().isAfter(today)
                && created.size() < MAX_CATCH_UP) {
            created.add(bill(template, template.getNextRunDate()));
            template.scheduleNext();
        }
        if (!created.isEmpty()) {
            recurringInvoiceRepository.save(template);
        }
        return created;
    }

    /** Creates the invoice for the template's current period, dated {@code invoiceDate}. */
    private Generated bill(RecurringInvoice template, LocalDate invoiceDate) {
        UUID orgId = template.getOrganizationId();
        Partner partner = partnerRepository.findByOrganizationIdAndId(orgId, template.getPartnerId()).orElse(null);
        boolean canEmail = partner != null && partner.getEmail() != null && partner.getEmail().contains("@");
        boolean send = Boolean.TRUE.equals(template.getAutoSend()) && canEmail;

        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setType(Invoice.InvoiceType.CUSTOMER);
        request.setPartnerId(template.getPartnerId());
        // Without an address to send it to, the invoice waits as a draft rather than claiming
        // to have been sent.
        request.setStatus(send ? Invoice.Status.SENT : Invoice.Status.DRAFT);
        request.setInvoiceDate(invoiceDate);
        request.setDueDate(invoiceDate.plusDays(orDefault(template.getPaymentTermsDays(), 30)));
        request.setCustomerName(template.getCustomerName() != null ? template.getCustomerName()
                : partner != null ? partner.getName() : null);
        request.setDeliveryAddress(template.getDeliveryAddress());
        request.setVatNumber(template.getVatNumber());
        request.setLines(copyLines(template.getLines()));

        Invoice invoice = invoiceService.createInvoice(orgId, request);
        invoice.setRecurringInvoiceId(template.getId());
        invoice = invoiceRepository.save(invoice);

        template.setPeriodIndex(template.getPeriodIndex() + 1);
        template.setGeneratedCount(template.getGeneratedCount() + 1);
        template.setLastRunAt(Instant.now());
        template.setLastInvoiceId(invoice.getId());
        return new Generated(invoice, send);
    }

    public RecurringInvoiceResponse toResponse(RecurringInvoice template) {
        BigDecimal subtotal = InvoiceService.sumOfLines(template.getLines());
        BigDecimal tax = subtotal != null ? InvoiceService.calculateTaxFromLines(template.getLines(), subtotal) : null;
        return RecurringInvoiceResponse.builder()
                .id(template.getId())
                .partnerId(template.getPartnerId())
                .name(template.getName())
                .frequency(template.getFrequency())
                .intervalCount(template.getIntervalCount())
                .startDate(template.getStartDate())
                .endDate(template.getEndDate())
                .nextRunDate(template.getNextRunDate())
                .status(template.getStatus())
                .paymentTermsDays(template.getPaymentTermsDays())
                .autoSend(template.getAutoSend())
                .customerName(template.getCustomerName())
                .deliveryAddress(template.getDeliveryAddress())
                .vatNumber(template.getVatNumber())
                .lines(template.getLines())
                .subtotal(subtotal)
                .tax(tax)
                .total(subtotal != null ? subtotal.add(tax) : null)
                .generatedCount(template.getGeneratedCount())
                .scheduleLocked(template.getPeriodIndex() > 0)
                .lastRunAt(template.getLastRunAt())
                .lastInvoiceId(template.getLastInvoiceId())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

    private void applySchedule(RecurringInvoice template, RecurringInvoiceRequest request) {
        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("The end date must be on or after the start date");
        }
        template.setFrequency(request.getFrequency());
        template.setIntervalCount(orDefault(request.getIntervalCount(), 1));
        template.setStartDate(request.getStartDate());
        template.setEndDate(request.getEndDate());
    }

    private void applyContent(RecurringInvoice template, RecurringInvoiceRequest request) {
        if (InvoiceService.sumOfLines(request.getLines()) == null) {
            throw new IllegalArgumentException("Add at least one line with a quantity and a unit price");
        }
        template.setPartnerId(request.getPartnerId());
        template.setName(request.getName().trim());
        template.setPaymentTermsDays(orDefault(request.getPaymentTermsDays(), 30));
        template.setAutoSend(request.getAutoSend() == null || request.getAutoSend());
        template.setCustomerName(request.getCustomerName());
        template.setDeliveryAddress(request.getDeliveryAddress());
        template.setVatNumber(request.getVatNumber());
        template.setLines(copyLines(request.getLines()));
    }

    private void requirePartner(UUID orgId, UUID partnerId) {
        partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    private static List<Map<String, Object>> copyLines(List<Map<String, Object>> lines) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (lines != null) {
            for (Map<String, Object> line : lines) {
                copy.add(new HashMap<>(line));
            }
        }
        return copy;
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    /** An invoice a template produced, and whether it should now be emailed. */
    public record Generated(Invoice invoice, boolean send) {
    }
}
