package com.bento.crm.invoice.controller;

import com.bento.crm.brand.dto.BrandResponse;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.invoice.dto.BrandInvoiceStyleRequest;
import com.bento.crm.invoice.dto.InvoiceTemplateRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.model.InvoiceTemplate;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/invoice-template")
@RequiredArgsConstructor
@Tag(name = "Invoice template", description = "Logo, colours, legal details and footer printed on invoices")
public class InvoiceTemplateController {

    private final InvoiceTemplateService invoiceTemplateService;
    private final PdfDocumentService pdfDocumentService;
    private final OrganizationRepository organizationRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Get the invoice template")
    public ResponseEntity<InvoiceTemplate> get() {
        return ResponseEntity.ok(invoiceTemplateService.getTemplate());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Save the invoice template")
    public ResponseEntity<InvoiceTemplate> update(@Valid @RequestBody InvoiceTemplateRequest request) {
        return ResponseEntity.ok(invoiceTemplateService.updateTemplate(request));
    }

    @PutMapping("/brands/{brandId}")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Set a brand's invoice logo and footer")
    public ResponseEntity<BrandResponse> updateBrand(@PathVariable UUID brandId, @Valid @RequestBody BrandInvoiceStyleRequest request) {
        return ResponseEntity.ok(BrandResponse.fromEntity(invoiceTemplateService.updateBrandStyle(brandId, request)));
    }

    /** A sample invoice printed with the saved template, or a brand's version of it. */
    @GetMapping(value = "/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Preview the invoice template as a PDF")
    public ResponseEntity<byte[]> preview(@RequestParam(required = false) UUID brandId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Invoice sample = Invoice.builder()
                .type(Invoice.InvoiceType.CUSTOMER)
                .status(Invoice.Status.SENT)
                .invoiceNumber("FAC-EXEMPLE-0001")
                .invoiceDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(30))
                .subtotal(new BigDecimal("12500.00"))
                .tax(new BigDecimal("2290.00"))
                .total(new BigDecimal("14790.00"))
                .lines(List.of(
                        Map.of("description", "Abonnement logiciel (mensuel)", "qty", 1, "unitPrice", 9500, "vatRate", 0.2),
                        Map.of("description", "Formation sur site", "qty", 2, "unitPrice", 1500, "vatRate", 0.14)))
                .build();
        sample.setOrganizationId(orgId);
        sample.setId(UUID.randomUUID());
        Partner customer = new Partner();
        customer.setName("Client Exemple SARL");
        customer.setCity("Casablanca");
        customer.setCountry("Maroc");
        customer.setEmail("compta@client-exemple.ma");
        customer.setCompany(Map.of("ice", "001234567000089"));
        byte[] pdf = pdfDocumentService.generateInvoicePdf(sample, customer,
                organizationRepository.findById(orgId).orElse(null), invoiceTemplateService.brandingFor(brandId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"apercu-facture.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
