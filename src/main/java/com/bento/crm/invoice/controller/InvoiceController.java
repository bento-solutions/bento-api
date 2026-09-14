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

    public InvoiceController(InvoiceService invoiceService,
                             PdfDocumentService pdfDocumentService,
                             PartnerRepository partnerRepository,
                             OrganizationRepository organizationRepository) {
        this.invoiceService = invoiceService;
        this.pdfDocumentService = pdfDocumentService;
        this.partnerRepository = partnerRepository;
        this.organizationRepository = organizationRepository;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    @Operation(summary = "Create invoice", description = "Create a new invoice")
    public ResponseEntity<InvoiceResponse> createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        Invoice created = invoiceService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Get invoice by ID", description = "Retrieve invoice details")
    public ResponseEntity<InvoiceResponse> getInvoice(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getInvoice(id);
        return ResponseEntity.ok(InvoiceResponse.fromEntity(invoice));
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
            Pageable pageable) {
        Page<Invoice> page = invoiceService.listInvoices(q, type, status, partnerId, fromDate, toDate, pageable);
        Page<InvoiceResponse> dtoPage = page.map(InvoiceResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Update invoice", description = "Update invoice information")
    public ResponseEntity<InvoiceResponse> updateInvoice(@PathVariable UUID id, @Valid @RequestBody CreateInvoiceRequest request) {
        Invoice invoice = invoiceService.updateInvoice(id, request);
        return ResponseEntity.ok(InvoiceResponse.fromEntity(invoice));
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
        byte[] pdf = pdfDocumentService.generateInvoicePdf(invoice, partner, org);

        String filename = "facture-" + (invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId().toString().substring(0, 8)) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
