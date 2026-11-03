package com.bento.crm.delivery.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.delivery.dto.DeliveryNoteDtos;
import com.bento.crm.delivery.service.DeliveryNoteService;
import com.bento.crm.invoice.dto.InvoiceResponse;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.service.InvoiceVat;
import com.bento.crm.ledger.service.VatDeterminationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Delivery notes (bons de livraison). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Delivery notes")
public class DeliveryNoteController {

    private static final String READ = "hasAuthority('INVOICES_READ')";
    private static final String WRITE = "hasAuthority('INVOICES_CREATE')";

    private final DeliveryNoteService service;
    private final VatDeterminationService vatDetermination;

    @GetMapping("/delivery-notes")
    @PreAuthorize(READ)
    public PageResponse<DeliveryNoteDtos.DeliveryNote> list(@RequestParam(required = false) UUID partnerId,
                                                            @RequestParam(required = false) UUID dealId,
                                                            @RequestParam(required = false) UUID missionId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return service.list(partnerId, dealId, missionId, status, page, size);
    }

    @GetMapping("/delivery-notes/{id}")
    @PreAuthorize(READ)
    public DeliveryNoteDtos.DeliveryNote get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/delivery-notes")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryNoteDtos.DeliveryNote create(@RequestBody DeliveryNoteDtos.DeliveryNoteRequest request) {
        return service.create(request);
    }

    @PutMapping("/delivery-notes/{id}")
    @PreAuthorize(WRITE)
    public DeliveryNoteDtos.DeliveryNote update(@PathVariable UUID id, @RequestBody DeliveryNoteDtos.DeliveryNoteRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/delivery-notes/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/deals/{id}/delivery-note")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryNoteDtos.DeliveryNote createFromDeal(@PathVariable UUID id) {
        return service.createFromDeal(id);
    }

    @PostMapping("/delivery-notes/{id}/deliver")
    @PreAuthorize(WRITE)
    public DeliveryNoteDtos.DeliveryNote deliver(@PathVariable UUID id,
                                                 @RequestBody(required = false) DeliveryNoteDtos.DeliverRequest request) {
        return service.deliver(id, request);
    }

    /** A DRAFT customer invoice from the deal (advances deducted); returns the invoice. */
    @PostMapping("/delivery-notes/{id}/invoice")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse invoice(@PathVariable UUID id) {
        Invoice inv = service.invoice(id);
        InvoiceResponse dto = InvoiceResponse.fromEntity(inv);
        BigDecimal total = inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal();
        dto.setAmountPaid(BigDecimal.ZERO.setScale(2));
        dto.setAmountCredited(BigDecimal.ZERO.setScale(2));
        dto.setBalanceDue(total.setScale(2, RoundingMode.HALF_UP));
        dto.setVatBreakdown(InvoiceVat.breakdown(inv, vatDetermination.mentions(inv.getOrganizationId())));
        return dto;
    }

    @GetMapping(value = "/delivery-notes/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        DeliveryNoteDtos.DeliveryNote n = service.get(id);
        byte[] body = service.pdf(id);
        String name = "bon-de-livraison-" + (n.number() != null ? n.number() : id.toString().substring(0, 8)) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
