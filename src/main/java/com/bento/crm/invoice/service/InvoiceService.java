package com.bento.crm.invoice.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.service.BusinessNumberService;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.invoice.repository.InvoiceSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final BusinessNumberService businessNumberService;
    private final InvoiceMailer invoiceMailer;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final com.bento.crm.ledger.service.LedgerAutoPostingService ledger;
    private final com.bento.crm.ledger.service.VatDeterminationService vatDetermination;
    private final com.bento.crm.payment.service.PaymentService paymentService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Moroccan standard VAT, applied when the client sends lines but no tax breakdown. */
    static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("0.20");
    static final String LOCKED = "Issue a credit note to correct a sent invoice";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Transactional
    public Invoice createInvoice(CreateInvoiceRequest request) {
        return createInvoice(TenantContext.getCurrentOrganizationId(), request);
    }

    /** Creates an invoice for an explicit organization, for callers outside a request. */
    @Transactional
    public Invoice createInvoice(UUID organizationId, CreateInvoiceRequest request) {
        Invoice invoice = new Invoice();
        invoice.setOrganizationId(organizationId);
        Invoice.Kind kind = request.getKind() == null ? Invoice.Kind.INVOICE : request.getKind();
        if (kind == Invoice.Kind.CREDIT_NOTE) {
            throw new IllegalArgumentException("Issue a credit note from the invoice it corrects");
        }
        invoice.setKind(kind);
        applyRequest(invoice, request);
        Invoice saved = invoiceRepository.save(invoice);
        ledger.invoice(saved);
        return issued(saved);
    }

    public Invoice getInvoice(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return invoiceRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
    }

    public Page<Invoice> listInvoices(Pageable pageable) {
        return listInvoices(null, null, null, null, null, null, pageable);
    }

    public Page<Invoice> listInvoices(
            String q,
            Invoice.InvoiceType type,
            Invoice.Status status,
            UUID partnerId,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable) {
        return listInvoices(q, type, status, partnerId, fromDate, toDate, null, null, null, pageable);
    }

    public Page<Invoice> listInvoices(
            String q,
            Invoice.InvoiceType type,
            Invoice.Status status,
            UUID partnerId,
            LocalDate fromDate,
            LocalDate toDate,
            UUID dealId,
            UUID proposalId,
            Invoice.Kind kind,
            Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if ((q == null || q.isBlank()) && type == null && status == null && partnerId == null && fromDate == null
                && toDate == null && dealId == null && proposalId == null && kind == null) {
            return invoiceRepository.findByOrganizationId(orgId, pageable);
        }
        return invoiceRepository.findAll(InvoiceSpecification.filter(orgId, q, type, status, partnerId, fromDate, toDate,
                dealId, proposalId, kind), pageable);
    }

    public Page<Invoice> listInvoicesByStatus(Invoice.Status status, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return invoiceRepository.findByOrganizationIdAndStatus(orgId, status, pageable);
    }

    @Transactional
    public Invoice updateInvoice(UUID id, CreateInvoiceRequest request) {
        Invoice invoice = getInvoice(id);
        if (invoice.getKind() == Invoice.Kind.CREDIT_NOTE) {
            throw new IllegalArgumentException("A credit note cannot be edited");
        }
        // From phase 3 a customer invoice that went out is corrected with a credit note: only
        // what does not change the books (due date, address...) can still be edited.
        boolean locked = invoice.getType() == Invoice.InvoiceType.CUSTOMER && invoice.getStatus() != null
                && invoice.getStatus() != Invoice.Status.DRAFT;
        if (locked && request.getStatus() == Invoice.Status.DRAFT) {
            throw new IllegalArgumentException(LOCKED);
        }
        String before = locked ? books(invoice) : null;
        boolean wasOut = isOut(invoice);
        if (request.getKind() != null && request.getKind() != invoice.getKind()) {
            if (invoice.getStatus() != Invoice.Status.DRAFT || request.getKind() == Invoice.Kind.CREDIT_NOTE) {
                throw new IllegalArgumentException("The kind of a document can only change while it is a draft");
            }
            invoice.setKind(request.getKind());
        }
        applyRequest(invoice, request);
        if (locked && !before.equals(books(invoice))) {
            throw new IllegalArgumentException(LOCKED);
        }
        Invoice saved = invoiceRepository.save(invoice);
        // Issuing posts the invoice; an edit reverses and re-posts it; back to draft reverses it.
        ledger.invoice(saved);
        // Only the change from draft to sent issues stock; later edits only adjust it to the new
        // quantities, and moving a sent invoice back to draft puts its goods back.
        if (!wasOut) return issued(saved);
        eventPublisher.publishEvent(isOut(saved)
                ? new com.bento.crm.stock.event.StockEvents.InvoiceChanged(saved.getOrganizationId(), saved.getId())
                : new com.bento.crm.stock.event.StockEvents.InvoiceCancelled(saved.getOrganizationId(), saved.getId()));
        return saved;
    }

    /** What a sent customer invoice may not change. */
    private static String books(Invoice i) {
        return i.getType() + "|" + i.getKind() + "|" + i.getPartnerId() + "|" + i.getInvoiceNumber() + "|"
                + i.getInvoiceDate() + "|" + plain(i.getSubtotal()) + "|" + plain(i.getTax()) + "|" + plain(i.getTotal())
                + "|" + (i.getStatus() == Invoice.Status.DRAFT) + "|" + deductionKey(i.getAdvanceDeductions())
                + "|" + lineKey(i.getLines());
    }

    /** The lines as the books see them: what, how many, at what price and VAT code. */
    private static String lineKey(List<Map<String, Object>> lines) {
        if (lines == null) return "";
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> l : lines) {
            if (l == null) continue;
            BigDecimal net = com.bento.crm.ledger.service.VatDeterminationService.net(l);
            b.append(l.getOrDefault("productId", "")).append(':')
                    .append(l.getOrDefault("description", l.getOrDefault("item", ""))).append(':')
                    .append(net == null ? "" : plain(net)).append(':').append(l.get("vatCode")).append(';');
        }
        return b.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String deductionKey(List<Map<String, Object>> deductions) {
        if (deductions == null) return "";
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> d : deductions) {
            b.append(d.get("advanceInvoiceId")).append(':').append(plain(InvoiceVat.money(d.get("base")))).append(';');
        }
        return b.toString();
    }

    private static boolean isOut(Invoice invoice) {
        return invoice.getType() == Invoice.InvoiceType.CUSTOMER && invoice.getStatus() != null
                && invoice.getStatus() != Invoice.Status.DRAFT
                && (invoice.getKind() == null || invoice.getKind() == Invoice.Kind.INVOICE);
    }

    /** A customer invoice that has just gone out may take its goods out of stock (StockService decides). */
    private Invoice issued(Invoice invoice) {
        if (isOut(invoice)) {
            eventPublisher.publishEvent(new com.bento.crm.stock.event.StockEvents.InvoiceIssued(invoice.getOrganizationId(), invoice.getId()));
        }
        return invoice;
    }

    private void applyRequest(Invoice invoice, CreateInvoiceRequest request) {
        applyRequestFields(invoice, request);
        fillDerivedFields(invoice, request);
    }

    /**
     * What the API does not receive it derives, so an invoice created from a one-line form is
     * still complete: a sequential number, an issue date, the sent/paid timestamps implied by
     * the status, and a subtotal/VAT/total breakdown computed from the lines (each line gets its
     * VAT code by the determination order), net of the advances it deducts.
     */
    private void fillDerivedFields(Invoice invoice, CreateInvoiceRequest request) {
        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()) {
            invoice.setInvoiceNumber(businessNumberService.next(invoice.getOrganizationId(), prefix(invoice)));
        }
        if (invoice.getInvoiceDate() == null) {
            invoice.setInvoiceDate(LocalDate.now());
        }
        boolean sent = invoice.getStatus() != null && invoice.getStatus() != Invoice.Status.DRAFT;
        if (sent && invoice.getSentAt() == null) {
            invoice.setSentAt(Instant.now());
        }
        if (invoice.getStatus() == Invoice.Status.PAID && invoice.getPaidAt() == null) {
            invoice.setPaidAt(Instant.now());
        }

        List<com.bento.crm.ledger.service.VatDeterminationService.BreakdownRow> breakdown =
                vatDetermination.fillLines(invoice.getOrganizationId(), invoice.getPartnerId(), invoice.getLines());
        if (request != null) {
            resolveDeductions(invoice, request);
        }
        if (!breakdown.isEmpty()) {
            BigDecimal base = BigDecimal.ZERO, vat = BigDecimal.ZERO;
            for (var r : breakdown) {
                base = base.add(r.base());
                vat = vat.add(r.vat());
            }
            for (Map<String, Object> d : invoice.getAdvanceDeductions()) {
                base = base.subtract(InvoiceVat.money(d.get("base")));
                vat = vat.subtract(InvoiceVat.money(d.get("vat")));
            }
            invoice.setSubtotal(base.setScale(2, RoundingMode.HALF_UP));
            invoice.setTax(vat.setScale(2, RoundingMode.HALF_UP));
            invoice.setTotal(invoice.getSubtotal().add(invoice.getTax()));
            return;
        }
        if (!invoice.getAdvanceDeductions().isEmpty()) {
            throw new IllegalArgumentException("Add the invoice lines to deduct advances");
        }
        BigDecimal fromLines = sumOfLines(invoice.getLines());
        if (invoice.getSubtotal() == null) {
            // Prefer the lines; otherwise treat a lone total as the tax-exclusive amount the
            // form labels "Subtotal".
            invoice.setSubtotal(fromLines != null ? fromLines : invoice.getTotal());
        }
        if (invoice.getSubtotal() != null) {
            if (invoice.getTax() == null) {
                invoice.setTax(calculateTaxFromLines(invoice.getLines(), invoice.getSubtotal()));
            }
            if (invoice.getTotal() == null || fromLines != null || invoice.getTotal().compareTo(invoice.getSubtotal()) == 0) {
                invoice.setTotal(invoice.getSubtotal().add(invoice.getTax()));
            }
        }
    }

    private static String prefix(Invoice invoice) {
        boolean vendor = invoice.getType() == Invoice.InvoiceType.VENDOR;
        Invoice.Kind kind = invoice.getKind() == null ? Invoice.Kind.INVOICE : invoice.getKind();
        return switch (kind) {
            case ADVANCE -> vendor ? "ACPF" : "ACP";
            case CREDIT_NOTE -> vendor ? "AVF" : "AV";
            default -> vendor ? "FRS" : "FAC";
        };
    }

    // Advance deductions

    /**
     * Sets the advances a final invoice deducts: the request's list, or with {@code
     * deductAdvances: true} every open advance of the same deal (else proposal, else partner) in
     * full. Without either, an update keeps what the invoice had.
     */
    private void resolveDeductions(Invoice invoice, CreateInvoiceRequest request) {
        if (invoice.getAdvanceDeductions() == null) {
            invoice.setAdvanceDeductions(new java.util.ArrayList<>());
        }
        if (invoice.getKind() != Invoice.Kind.INVOICE) {
            if (invoice.getKind() == Invoice.Kind.ADVANCE) invoice.setAdvanceDeductions(new java.util.ArrayList<>());
            return;
        }
        List<Map<String, Object>> wanted;
        if (request.getAdvanceDeductions() != null) {
            wanted = request.getAdvanceDeductions();
        } else if (Boolean.TRUE.equals(request.getDeductAdvances())) {
            wanted = new java.util.ArrayList<>();
            for (OpenAdvance a : openAdvances(invoice.getOrganizationId(), invoice.getType(), invoice.getPartnerId(),
                    invoice.getDealId(), invoice.getDealId() == null ? invoice.getSourceProposalId() : null, invoice.getId())) {
                wanted.add(new java.util.HashMap<>(Map.of("advanceInvoiceId", a.id().toString())));
            }
        } else {
            wanted = invoice.getAdvanceDeductions();
        }
        List<Map<String, Object>> resolved = new java.util.ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Map<String, Object> w : wanted) {
            if (w == null || w.get("advanceInvoiceId") == null) {
                throw new IllegalArgumentException("Each deduction needs its advanceInvoiceId");
            }
            UUID advanceId;
            try {
                advanceId = UUID.fromString(w.get("advanceInvoiceId").toString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid advance invoice id");
            }
            if (!seen.add(advanceId)) {
                throw new IllegalArgumentException("An advance cannot be deducted twice");
            }
            Invoice advance = invoiceRepository.findByOrganizationIdAndId(invoice.getOrganizationId(), advanceId)
                    .orElseThrow(() -> new IllegalArgumentException("Advance invoice not found"));
            if (advance.getKind() != Invoice.Kind.ADVANCE || advance.getType() != invoice.getType()) {
                throw new IllegalArgumentException(advance.getInvoiceNumber() + " is not an advance invoice of this kind");
            }
            if (!advance.getPartnerId().equals(invoice.getPartnerId())) {
                throw new IllegalArgumentException("The advance " + advance.getInvoiceNumber() + " is for another partner");
            }
            if (advance.getStatus() == Invoice.Status.DRAFT || advance.getStatus() == Invoice.Status.CREDITED) {
                throw new IllegalArgumentException("The advance " + advance.getInvoiceNumber() + " has not been issued");
            }
            BigDecimal[] used = deducted(invoice.getOrganizationId(), advanceId, invoice.getId());
            BigDecimal remainingBase = nz(advance.getSubtotal()).subtract(used[0]);
            BigDecimal remainingVat = nz(advance.getTax()).subtract(used[1]);
            BigDecimal base = w.get("base") == null ? remainingBase : InvoiceVat.money(w.get("base"));
            if (base.signum() <= 0 || base.compareTo(remainingBase.add(TOLERANCE)) > 0) {
                throw new IllegalArgumentException("An advance cannot be deducted twice: " + advance.getInvoiceNumber()
                        + " has " + remainingBase.setScale(2, RoundingMode.HALF_UP).toPlainString() + " left to deduct");
            }
            BigDecimal vat;
            if (w.get("vat") != null) {
                vat = InvoiceVat.money(w.get("vat"));
            } else if (base.compareTo(remainingBase) == 0) {
                vat = remainingVat;
            } else {
                BigDecimal fullBase = nz(advance.getSubtotal());
                vat = fullBase.signum() == 0 ? BigDecimal.ZERO
                        : nz(advance.getTax()).multiply(base).divide(fullBase, 2, RoundingMode.HALF_UP);
            }
            if (vat.signum() < 0 || vat.compareTo(remainingVat.add(TOLERANCE)) > 0) {
                throw new IllegalArgumentException("The VAT deducted is more than the advance's VAT left");
            }
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("advanceInvoiceId", advanceId.toString());
            d.put("base", base.setScale(2, RoundingMode.HALF_UP));
            d.put("vat", vat.setScale(2, RoundingMode.HALF_UP));
            String code = advanceVatCode(advance);
            if (code != null) d.put("vatCode", code);
            d.put("number", advance.getInvoiceNumber());
            d.put("date", advance.getInvoiceDate() == null ? null : advance.getInvoiceDate().toString());
            resolved.add(d);
        }
        invoice.setAdvanceDeductions(resolved);
    }

    private static String advanceVatCode(Invoice advance) {
        if (advance.getLines() == null) return null;
        for (Map<String, Object> l : advance.getLines()) {
            if (l != null && l.get("vatCode") != null) return l.get("vatCode").toString();
        }
        return null;
    }

    /** base and VAT of an advance already deducted by live final invoices other than {@code except}. */
    private BigDecimal[] deducted(UUID orgId, UUID advanceId, UUID except) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COALESCE(SUM((d->>'base')::numeric), 0) AS base, COALESCE(SUM((d->>'vat')::numeric), 0) AS vat
                FROM invoice i, jsonb_array_elements(i.advance_deductions) d
                WHERE i.organization_id = ? AND i.deleted_at IS NULL AND i.kind = 'INVOICE'
                  AND d->>'advanceInvoiceId' = ? AND (CAST(? AS uuid) IS NULL OR i.id <> CAST(? AS uuid))
                """, orgId, advanceId.toString(), except, except);
        Map<String, Object> r = rows.get(0);
        return new BigDecimal[]{(BigDecimal) r.get("base"), (BigDecimal) r.get("vat")};
    }

    /** An advance invoice not yet fully deducted. */
    public record OpenAdvance(UUID id, String number, LocalDate date, BigDecimal base, BigDecimal vat, BigDecimal total,
                              Invoice.Status status, BigDecimal remainingBase, BigDecimal remainingVat, UUID dealId,
                              UUID proposalId) {}

    /**
     * Issued advance invoices of a partner (and deal or proposal, when given) with something left
     * to deduct, oldest first. {@code exceptInvoice} leaves that final invoice's own deductions out.
     */
    public List<OpenAdvance> openAdvances(UUID orgId, Invoice.InvoiceType type, UUID partnerId, UUID dealId,
                                          UUID proposalId, UUID exceptInvoice) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.invoice_number, i.invoice_date, i.subtotal, i.tax, i.total, i.status, i.deal_id,
                       i.source_proposal_id
                FROM invoice i
                WHERE i.organization_id = ? AND i.deleted_at IS NULL AND i.kind = 'ADVANCE'
                  AND i.status NOT IN ('DRAFT', 'CREDITED')
                  AND (CAST(? AS varchar) IS NULL OR i.type = CAST(? AS varchar))
                  AND (CAST(? AS uuid) IS NULL OR i.partner_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR i.deal_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR i.source_proposal_id = CAST(? AS uuid))
                ORDER BY i.invoice_date, i.created_at
                """, orgId, type == null ? null : type.name(), type == null ? null : type.name(), partnerId, partnerId,
                dealId, dealId, proposalId, proposalId);
        List<OpenAdvance> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            BigDecimal[] used = deducted(orgId, id, exceptInvoice);
            BigDecimal base = nz((BigDecimal) r.get("subtotal"));
            BigDecimal vat = nz((BigDecimal) r.get("tax"));
            BigDecimal remainingBase = base.subtract(used[0]);
            if (remainingBase.signum() <= 0) continue;
            Object d = r.get("invoice_date");
            out.add(new OpenAdvance(id, (String) r.get("invoice_number"),
                    d instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) d, base, vat,
                    nz((BigDecimal) r.get("total")), Invoice.Status.valueOf((String) r.get("status")),
                    remainingBase.setScale(2, RoundingMode.HALF_UP), vat.subtract(used[1]).setScale(2, RoundingMode.HALF_UP),
                    (UUID) r.get("deal_id"), (UUID) r.get("source_proposal_id")));
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Moroccan multi-tier VAT calculation (0%, 7%, 10%, 14%, 20%).
     * Computes line-level tax if specified, or applies DEFAULT_VAT_RATE (20%).
     */
    static BigDecimal calculateTaxFromLines(List<Map<String, Object>> lines, BigDecimal subtotal) {
        if (lines == null || lines.isEmpty()) {
            return subtotal != null ? subtotal.multiply(DEFAULT_VAT_RATE).setScale(2, RoundingMode.HALF_UP) : null;
        }
        BigDecimal totalTax = BigDecimal.ZERO;
        boolean computedFromLines = false;
        for (Map<String, Object> line : lines) {
            BigDecimal qty = toDecimal(line.get("qty"));
            BigDecimal unitPrice = toDecimal(line.get("unitPrice"));
            if (unitPrice == null) {
                unitPrice = toDecimal(line.get("unit_price"));
            }
            if (qty == null || unitPrice == null) {
                continue;
            }
            BigDecimal lineSubtotal = qty.multiply(unitPrice);
            BigDecimal rate = toDecimal(line.get("vatRate"));
            if (rate == null) {
                rate = toDecimal(line.get("vat_rate"));
            }
            if (rate == null) {
                rate = toDecimal(line.get("taxRate"));
            }
            if (rate == null) {
                rate = DEFAULT_VAT_RATE;
            } else if (rate.compareTo(BigDecimal.ONE) > 0) {
                // A percentage (20) rather than a fraction (0.20).
                rate = rate.movePointLeft(2);
            }
            totalTax = totalTax.add(lineSubtotal.multiply(rate));
            computedFromLines = true;
        }
        return computedFromLines ? totalTax.setScale(2, RoundingMode.HALF_UP) : (subtotal != null ? subtotal.multiply(DEFAULT_VAT_RATE).setScale(2, RoundingMode.HALF_UP) : null);
    }

    /** What a set of lines comes to with VAT (TTC), or {@code null} when there are no usable lines. */
    public static BigDecimal grossOfLines(List<Map<String, Object>> lines) {
        BigDecimal net = sumOfLines(lines);
        return net == null ? null : net.add(calculateTaxFromLines(lines, net));
    }

    /** Σ qty × unitPrice over the JSON lines, or {@code null} when there are no usable lines. */
    static BigDecimal sumOfLines(List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (Map<String, Object> line : lines) {
            BigDecimal qty = toDecimal(line.get("qty"));
            BigDecimal unitPrice = toDecimal(line.get("unitPrice"));
            if (unitPrice == null) {
                unitPrice = toDecimal(line.get("unit_price"));
            }
            if (qty == null || unitPrice == null) {
                continue;
            }
            sum = sum.add(qty.multiply(unitPrice));
            any = true;
        }
        return any ? sum.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void applyRequestFields(Invoice invoice, CreateInvoiceRequest request) {
        invoice.setType(request.getType());
        invoice.setPartnerId(request.getPartnerId());
        invoice.setDealId(request.getDealId());
        invoice.setPurchaseOrderId(request.getPurchaseOrderId());
        invoice.setStatus(request.getStatus());
        // A save that leaves the number out keeps the one the invoice has.
        if (request.getInvoiceNumber() != null && !request.getInvoiceNumber().isBlank()) {
            invoice.setInvoiceNumber(request.getInvoiceNumber());
        }
        invoice.setInvoiceDate(request.getInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setSentAt(request.getSentAt());
        invoice.setPaidAt(request.getPaidAt());
        invoice.setCustomerAccount(request.getCustomerAccount());
        invoice.setCustomerName(request.getCustomerName());
        invoice.setDeliveryAddress(request.getDeliveryAddress());
        invoice.setVatNumber(request.getVatNumber());
        invoice.setSubtotal(request.getSubtotal());
        invoice.setTax(request.getTax());
        invoice.setTotal(request.getTotal());
        invoice.setLines(request.getLines() != null ? new java.util.ArrayList<>(request.getLines()) : new java.util.ArrayList<>());
        if (request.getSourceProposalId() != null) {
            Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM proposal WHERE organization_id = ? AND id = ?)",
                    Boolean.class, invoice.getOrganizationId(), request.getSourceProposalId());
            if (!Boolean.TRUE.equals(known)) {
                throw new IllegalArgumentException("Unknown proposal");
            }
            invoice.setSourceProposalId(request.getSourceProposalId());
        }
    }

    @Transactional
    public void deleteInvoice(UUID id) {
        Invoice invoice = getInvoice(id);
        if (invoice.getStatus() == Invoice.Status.PAID || invoice.getStatus() == Invoice.Status.PARTIALLY_PAID) {
            throw new IllegalStateException("Cannot delete paid or partially paid invoice");
        }
        if (invoice.getKind() == Invoice.Kind.ADVANCE
                && deducted(invoice.getOrganizationId(), invoice.getId(), null)[0].signum() > 0) {
            throw new IllegalArgumentException("This advance is deducted on a final invoice: remove the deduction first");
        }
        invoice.setDeletedAt(Instant.now());
        invoiceRepository.save(invoice);
        ledger.invoice(invoice);
        if (invoice.getCreditedInvoiceId() != null) {
            invoiceRepository.flush();
            paymentService.refreshInvoiceStatus(invoice.getOrganizationId(), invoice.getCreditedInvoiceId());
        }
        if (isOut(invoice)) {
            eventPublisher.publishEvent(new com.bento.crm.stock.event.StockEvents.InvoiceCancelled(invoice.getOrganizationId(), invoice.getId()));
        }
    }

    @Transactional
    public Invoice restoreInvoice(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Invoice invoice = invoiceRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        boolean wasDeleted = invoice.getDeletedAt() != null;
        if (wasDeleted && invoice.getKind() == Invoice.Kind.INVOICE && invoice.getAdvanceDeductions() != null) {
            for (Map<String, Object> d : invoice.getAdvanceDeductions()) {
                UUID advanceId = UUID.fromString(d.get("advanceInvoiceId").toString());
                BigDecimal[] used = deducted(orgId, advanceId, invoice.getId());
                BigDecimal full = invoiceRepository.findByOrganizationIdAndId(orgId, advanceId)
                        .map(Invoice::getSubtotal).orElse(BigDecimal.ZERO);
                if (used[0].add(InvoiceVat.money(d.get("base"))).compareTo(nz(full).add(TOLERANCE)) > 0) {
                    throw new IllegalArgumentException("An advance this invoice deducted is now deducted elsewhere");
                }
            }
        }
        invoice.setDeletedAt(null);
        Invoice saved = invoiceRepository.save(invoice);
        ledger.invoice(saved);
        if (saved.getCreditedInvoiceId() != null) {
            invoiceRepository.flush();
            paymentService.refreshInvoiceStatus(orgId, saved.getCreditedInvoiceId());
        }
        // A restored sent invoice takes its goods out again (they went back when it was deleted).
        return wasDeleted ? issued(saved) : saved;
    }

    public Page<Invoice> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return invoiceRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    @Transactional
    public int sendReminders(List<UUID> invoiceIds, String channel, String message) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        int count = 0;
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return 0;
        }

        boolean isEmail = channel != null && channel.equalsIgnoreCase("email");

        for (UUID id : invoiceIds) {
            Optional<Invoice> opt = invoiceRepository.findByOrganizationIdAndId(orgId, id);
            if (opt.isPresent()) {
                Invoice inv = opt.get();
                if (inv.getSentAt() == null) {
                    inv.setSentAt(Instant.now());
                    invoiceRepository.save(inv);
                }

                if (isEmail) {
                    invoiceMailer.sendReminder(inv, message);
                }
                count++;
            }
        }
        return count;
    }

    // Advance invoices (factures d'acompte)

    /**
     * Creates a draft advance invoice for a deal or a proposal: one line of {@code amount} (before
     * VAT unless {@code amountIncludesVat}) or {@code percent} of the deal's or proposal's amount
     * before VAT, at the VAT code given or determined for the partner.
     */
    @Transactional
    public Invoice createAdvance(com.bento.crm.invoice.dto.AdvanceInvoiceRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if (r.partnerId() == null) {
            throw new IllegalArgumentException("The partner is required");
        }
        Boolean partnerKnown = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM partner WHERE organization_id = ? AND id = ?"
                + " AND deleted_at IS NULL)", Boolean.class, orgId, r.partnerId());
        if (!Boolean.TRUE.equals(partnerKnown)) {
            throw new IllegalArgumentException("Unknown partner");
        }
        if ((r.amount() == null) == (r.percent() == null)) {
            throw new IllegalArgumentException("Give either the amount or the percent");
        }
        String source = null;
        BigDecimal sourceAmount = null;
        if (r.proposalId() != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT title, opportunity_value FROM proposal"
                    + " WHERE organization_id = ? AND id = ? AND deleted_at IS NULL", orgId, r.proposalId());
            if (rows.isEmpty()) throw new IllegalArgumentException("Unknown proposal");
            source = (String) rows.get(0).get("title");
            sourceAmount = (BigDecimal) rows.get(0).get("opportunity_value");
        }
        if (r.dealId() != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT d.title, COALESCE(d.amount, (SELECT SUM(l.total) FROM deal_order_line l WHERE l.deal_id = d.id)) AS amount
                    FROM deal d WHERE d.organization_id = ? AND d.id = ? AND d.deleted_at IS NULL
                    """, orgId, r.dealId());
            if (rows.isEmpty()) throw new IllegalArgumentException("Unknown deal");
            if (source == null) source = (String) rows.get(0).get("title");
            if (sourceAmount == null) sourceAmount = (BigDecimal) rows.get(0).get("amount");
        }
        Invoice.InvoiceType type = r.type() == null ? Invoice.InvoiceType.CUSTOMER : r.type();

        var setup = vatDetermination.determine(orgId, r.partnerId(), null, null);
        String vatCode = r.vatCode();
        BigDecimal ratePercent;
        if (vatCode != null && !vatCode.isBlank()) {
            var row = jdbc.queryForList("SELECT code, rate FROM ledger_vat_setup WHERE organization_id = ? AND code = ?",
                    orgId, vatCode.trim().toUpperCase(java.util.Locale.ROOT));
            if (row.isEmpty()) throw new IllegalArgumentException("Unknown VAT code: " + vatCode);
            vatCode = (String) row.get(0).get("code");
            ratePercent = (BigDecimal) row.get(0).get("rate");
        } else {
            vatCode = setup.vatCode();
            ratePercent = setup.rate();
        }
        BigDecimal fraction = ratePercent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);

        BigDecimal base;
        String label;
        if (r.percent() != null) {
            if (r.percent().signum() <= 0 || r.percent().compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("The percent is between 0 and 100");
            }
            if (sourceAmount == null || sourceAmount.signum() <= 0) {
                throw new IllegalArgumentException("A percent needs a deal or a proposal with an amount");
            }
            base = sourceAmount.multiply(r.percent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            label = "Acompte de " + r.percent().stripTrailingZeros().toPlainString() + " %"
                    + (source == null ? "" : " sur devis " + source);
        } else {
            if (r.amount().signum() <= 0) {
                throw new IllegalArgumentException("The amount must be positive");
            }
            base = Boolean.TRUE.equals(r.amountIncludesVat())
                    ? r.amount().divide(BigDecimal.ONE.add(fraction), 2, RoundingMode.HALF_UP)
                    : r.amount().setScale(2, RoundingMode.HALF_UP);
            label = "Acompte" + (source == null ? "" : " sur devis " + source);
        }
        if (r.label() != null && !r.label().isBlank()) label = r.label().trim();

        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("description", label);
        line.put("item", label);
        line.put("qty", 1);
        line.put("unitPrice", base);
        line.put("vatCode", vatCode);
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setType(type);
        req.setPartnerId(r.partnerId());
        req.setDealId(r.dealId());
        req.setSourceProposalId(r.proposalId());
        req.setStatus(Invoice.Status.DRAFT);
        req.setInvoiceDate(r.date());
        req.setDueDate(r.dueDate());
        req.setLines(new java.util.ArrayList<>(List.of(line)));
        req.setKind(Invoice.Kind.ADVANCE);
        return createInvoice(orgId, req);
    }

    // Credit notes (avoirs)

    /**
     * Issues a credit note against a sent invoice or bill: every line in full by default, or the
     * lines given. It is SENT at once, posts the mirror of the original, and lowers what the
     * original still owes (a fully credited invoice becomes CREDITED).
     */
    @Transactional
    public Invoice issueCreditNote(UUID id, com.bento.crm.invoice.dto.CreditNoteRequest r) {
        Invoice original = getInvoice(id);
        UUID orgId = original.getOrganizationId();
        if (original.getKind() == Invoice.Kind.CREDIT_NOTE) {
            throw new IllegalArgumentException("A credit note cannot be credited");
        }
        if (original.getStatus() == null || original.getStatus() == Invoice.Status.DRAFT) {
            throw new IllegalArgumentException("Only a sent invoice can be credited: edit or delete the draft instead");
        }
        if (original.getStatus() == Invoice.Status.CREDITED) {
            throw new IllegalArgumentException("This invoice is already fully credited");
        }
        if (r == null || r.reason() == null || r.reason().isBlank()) {
            throw new IllegalArgumentException("The reason is required");
        }
        boolean full = r.lines() == null || r.lines().isEmpty();
        Invoice cn = new Invoice();
        cn.setOrganizationId(orgId);
        cn.setType(original.getType());
        cn.setKind(Invoice.Kind.CREDIT_NOTE);
        cn.setPartnerId(original.getPartnerId());
        cn.setDealId(original.getDealId());
        cn.setSourceProposalId(original.getSourceProposalId());
        cn.setCreditedInvoiceId(original.getId());
        cn.setCreditReason(r.reason().trim());
        cn.setStatus(Invoice.Status.SENT);
        cn.setInvoiceDate(r.date() != null ? r.date() : LocalDate.now());
        cn.setCustomerAccount(original.getCustomerAccount());
        cn.setCustomerName(original.getCustomerName());
        cn.setDeliveryAddress(original.getDeliveryAddress());
        cn.setVatNumber(original.getVatNumber());
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (Map<String, Object> l : full ? (original.getLines() == null ? List.<Map<String, Object>>of() : original.getLines())
                : r.lines()) {
            if (l != null) lines.add(new java.util.LinkedHashMap<>(l));
        }
        cn.setLines(lines);
        cn.setAdvanceDeductions(new java.util.ArrayList<>());
        if (full && original.getAdvanceDeductions() != null) {
            for (Map<String, Object> d : original.getAdvanceDeductions()) {
                cn.getAdvanceDeductions().add(new java.util.LinkedHashMap<>(d));
            }
        }
        if (lines.isEmpty() || sumOfLines(lines) == null) {
            if (!full) throw new IllegalArgumentException("The credit note lines need a quantity and a unit price");
            cn.setSubtotal(original.getSubtotal());
            cn.setTax(original.getTax());
            cn.setTotal(original.getTotal());
            cn.setAdvanceDeductions(new java.util.ArrayList<>());
        }
        fillDerivedFields(cn, null);
        BigDecimal credited = paymentService.creditedAmount(orgId, original.getId());
        BigDecimal left = nz(original.getTotal()).subtract(credited);
        if (nz(cn.getTotal()).signum() <= 0) {
            throw new IllegalArgumentException("The credit note amount must be positive");
        }
        if (nz(cn.getTotal()).compareTo(left.add(TOLERANCE)) > 0) {
            throw new IllegalArgumentException("The credit note is more than what is left to credit on this invoice ("
                    + left.setScale(2, RoundingMode.HALF_UP).toPlainString() + ")");
        }
        Invoice saved = invoiceRepository.saveAndFlush(cn);
        ledger.invoice(saved);
        paymentService.refreshInvoiceStatus(orgId, original.getId());
        return saved;
    }
}
