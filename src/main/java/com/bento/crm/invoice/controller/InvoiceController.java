package com.bento.crm.invoice.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.dto.InvoiceResponse;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.invoice.dto.AgingReport;
import com.bento.crm.invoice.dto.BillFromPurchaseOrderRequest;
import com.bento.crm.invoice.dto.PaymentScheduleRequest;
import com.bento.crm.invoice.service.AgingService;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.invoice.service.SupplierBillService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;

import java.time.LocalDate;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/invoices")
@Tag(name = "Invoices", description = "Invoice management endpoints")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final PdfDocumentService pdfDocumentService;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final InvoiceTemplateService invoiceTemplateService;
    private final SupplierBillService supplierBillService;
    private final AgingService agingService;
    private final com.bento.crm.ledger.service.VatDeterminationService vatDetermination;

    public InvoiceController(InvoiceService invoiceService,
                             PdfDocumentService pdfDocumentService,
                             PartnerRepository partnerRepository,
                             OrganizationRepository organizationRepository,
                             InvoiceTemplateService invoiceTemplateService,
                             SupplierBillService supplierBillService,
                             AgingService agingService,
                             com.bento.crm.ledger.service.VatDeterminationService vatDetermination) {
        this.invoiceService = invoiceService;
        this.pdfDocumentService = pdfDocumentService;
        this.partnerRepository = partnerRepository;
        this.organizationRepository = organizationRepository;
        this.invoiceTemplateService = invoiceTemplateService;
        this.supplierBillService = supplierBillService;
        this.agingService = agingService;
        this.vatDetermination = vatDetermination;
    }

    /** The response with what was paid and credited, the balance due, and the VAT mentions. */
    private InvoiceResponse dto(Invoice invoice, java.util.Map<UUID, java.math.BigDecimal> paid,
                                java.util.Map<UUID, java.math.BigDecimal> credited, java.util.Map<String, String> mentions) {
        InvoiceResponse dto = InvoiceResponse.fromEntity(invoice);
        java.math.BigDecimal p = paid.getOrDefault(invoice.getId(), java.math.BigDecimal.ZERO);
        java.math.BigDecimal c = credited.getOrDefault(invoice.getId(), java.math.BigDecimal.ZERO);
        dto.setAmountPaid(p);
        dto.setAmountCredited(c.setScale(2, java.math.RoundingMode.HALF_UP));
        java.math.BigDecimal total = invoice.getTotal() == null ? java.math.BigDecimal.ZERO : invoice.getTotal();
        java.math.BigDecimal due = invoice.getKind() == Invoice.Kind.CREDIT_NOTE || invoice.getStatus() == Invoice.Status.PAID
                ? java.math.BigDecimal.ZERO : total.subtract(p).subtract(c).max(java.math.BigDecimal.ZERO);
        dto.setBalanceDue(due.setScale(2, java.math.RoundingMode.HALF_UP));
        dto.setVatBreakdown(com.bento.crm.invoice.service.InvoiceVat.breakdown(invoice, mentions));
        return dto;
    }

    private InvoiceResponse dto(Invoice invoice) {
        UUID orgId = invoice.getOrganizationId();
        return dto(invoice, agingService.paidByInvoice(orgId), agingService.creditedByInvoice(orgId),
                vatDetermination.mentions(orgId));
    }

    // Advance invoices and credit notes (phase 3)

    @PostMapping("/advance")
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    @Operation(summary = "Create an advance invoice", description = "A draft advance invoice (ACP) for a deal or proposal")
    public ResponseEntity<InvoiceResponse> createAdvance(@RequestBody com.bento.crm.invoice.dto.AdvanceInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(invoiceService.createAdvance(request)));
    }

    @GetMapping("/advances/open")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Open advances", description = "Issued advance invoices with something left to deduct")
    public ResponseEntity<java.util.List<com.bento.crm.invoice.service.InvoiceService.OpenAdvance>> openAdvances(
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) UUID dealId,
            @RequestParam(required = false) UUID proposalId,
            @RequestParam(required = false) Invoice.InvoiceType type) {
        return ResponseEntity.ok(invoiceService.openAdvances(com.bento.crm.common.context.TenantContext.getCurrentOrganizationId(),
                type, partnerId, dealId, proposalId, null));
    }

    @PostMapping("/{id}/credit-note")
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    @Operation(summary = "Issue a credit note", description = "A credit note (AV/AVF) against a sent invoice or bill")
    public ResponseEntity<InvoiceResponse> creditNote(@PathVariable UUID id,
                                                      @RequestBody(required = false) com.bento.crm.invoice.dto.CreditNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(invoiceService.issueCreditNote(id, request)));
    }

    @GetMapping("/aging")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Aging report", description = "Outstanding customer or supplier invoices by days past due")
    public ResponseEntity<AgingReport> aging(@RequestParam(defaultValue = "CUSTOMER") Invoice.InvoiceType type) {
        return ResponseEntity.ok(agingService.report(type));
    }

    @PostMapping("/from-purchase-order/{purchaseOrderId}")
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    @Operation(summary = "Bill a purchase order", description = "Books the supplier's bill for a purchase order and marks the order invoiced")
    public ResponseEntity<InvoiceResponse> billPurchaseOrder(@PathVariable UUID purchaseOrderId,
                                                             @Valid @RequestBody(required = false) BillFromPurchaseOrderRequest request) {
        Invoice bill = supplierBillService.billPurchaseOrder(purchaseOrderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.fromEntity(bill));
    }

    @PatchMapping("/{id}/payment-schedule")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Schedule a supplier bill payment", description = "Sets or clears the day a supplier bill is planned to be paid")
    public ResponseEntity<InvoiceResponse> schedulePayment(@PathVariable UUID id, @RequestBody PaymentScheduleRequest request) {
        return ResponseEntity.ok(InvoiceResponse.fromEntity(supplierBillService.schedulePayment(id, request.getScheduledPaymentDate())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    @Operation(summary = "Create invoice", description = "Create a new invoice")
    public ResponseEntity<InvoiceResponse> createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        Invoice created = invoiceService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Get invoice by ID", description = "Retrieve invoice details")
    public ResponseEntity<InvoiceResponse> getInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(dto(invoiceService.getInvoice(id)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "List invoices", description = "List all invoices with optional search and multi-criteria filters")
    public ResponseEntity<PageResponse<InvoiceResponse>> listInvoices(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Invoice.InvoiceType type,
            @RequestParam(required = false) Invoice.Status status,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID dealId,
            @RequestParam(required = false) UUID proposalId,
            @RequestParam(required = false) Invoice.Kind kind,
            Pageable pageable) {
        Page<Invoice> page = invoiceService.listInvoices(q, type, status, partnerId, fromDate, toDate, dealId, proposalId,
                kind, pageable);
        UUID orgId = com.bento.crm.common.context.TenantContext.getCurrentOrganizationId();
        java.util.Map<UUID, java.math.BigDecimal> paid = agingService.paidByInvoice(orgId);
        java.util.Map<UUID, java.math.BigDecimal> credited = agingService.creditedByInvoice(orgId);
        java.util.Map<String, String> mentions = vatDetermination.mentions(orgId);
        Page<InvoiceResponse> dtoPage = page.map(invoice -> dto(invoice, paid, credited, mentions));
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Update invoice", description = "Update invoice information")
    public ResponseEntity<InvoiceResponse> updateInvoice(@PathVariable UUID id, @Valid @RequestBody CreateInvoiceRequest request) {
        Invoice invoice = invoiceService.updateInvoice(id, request);
        return ResponseEntity.ok(dto(invoice));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICES_DELETE')")
    @Operation(summary = "Delete invoice", description = "Soft delete invoice record")
    public ResponseEntity<Void> deleteInvoice(@PathVariable UUID id) {
        invoiceService.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('INVOICES_DELETE')")
    @Operation(summary = "Restore invoice", description = "Undo a soft delete on an invoice")
    public ResponseEntity<InvoiceResponse> restoreInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(InvoiceResponse.fromEntity(invoiceService.restoreInvoice(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('INVOICES_DELETE')")
    @Operation(summary = "List deleted invoices", description = "Soft-deleted invoices still inside the retention window")
    public ResponseEntity<PageResponse<InvoiceResponse>> listDeleted(Pageable pageable) {
        Page<InvoiceResponse> page = invoiceService.listDeleted(pageable).map(InvoiceResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PostMapping("/reminders")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Send invoice reminders", description = "Send recovery reminders for selected invoices")
    public ResponseEntity<java.util.Map<String, Object>> sendReminders(@RequestBody java.util.Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        java.util.List<String> rawIds = (java.util.List<String>) request.get("invoiceIds");
        java.util.List<UUID> ids = rawIds != null
                ? rawIds.stream().map(UUID::fromString).toList()
                : java.util.Collections.emptyList();
        String channel = (String) request.getOrDefault("channel", "email");
        String message = (String) request.getOrDefault("message", "");
        int sent = invoiceService.sendReminders(ids, channel, message);
        return ResponseEntity.ok(java.util.Map.of("sent", sent, "success", true));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Download invoice PDF", description = "Generates a PDF document for an invoice")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getInvoice(id);
        Partner partner = null;
        if (invoice.getPartnerId() != null) {
            partner = partnerRepository.findByOrganizationIdAndId(invoice.getOrganizationId(), invoice.getPartnerId()).orElse(null);
        }
        Organization org = organizationRepository.findById(invoice.getOrganizationId()).orElse(null);
        byte[] pdf = pdfDocumentService.generateInvoicePdf(invoice, partner, org,
                invoiceTemplateService.brandingFor(invoice, partner),
                com.bento.crm.invoice.service.InvoiceVat.breakdown(invoice, vatDetermination.mentions(invoice.getOrganizationId())));

        String filename = "facture-" + (invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId().toString().substring(0, 8)) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
