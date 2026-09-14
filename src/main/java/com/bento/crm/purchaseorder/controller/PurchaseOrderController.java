package com.bento.crm.purchaseorder.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.purchaseorder.dto.CreatePurchaseOrderRequest;
import com.bento.crm.purchaseorder.dto.PurchaseOrderResponse;
import com.bento.crm.purchaseorder.model.PurchaseOrder;
import com.bento.crm.purchaseorder.service.PurchaseOrderService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/purchase-orders")
@Tag(name = "Purchase Orders", description = "Purchase order management endpoints")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final PdfDocumentService pdfDocumentService;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService,
                                   PdfDocumentService pdfDocumentService,
                                   PartnerRepository partnerRepository,
                                   OrganizationRepository organizationRepository) {
        this.purchaseOrderService = purchaseOrderService;
        this.pdfDocumentService = pdfDocumentService;
        this.partnerRepository = partnerRepository;
        this.organizationRepository = organizationRepository;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_CREATE')")
    @Operation(summary = "Create purchase order", description = "Create a new purchase order")
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        PurchaseOrder created = purchaseOrderService.createPurchaseOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PurchaseOrderResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    @Operation(summary = "Get purchase order by ID", description = "Retrieve purchase order details")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(@PathVariable UUID id) {
        PurchaseOrder po = purchaseOrderService.getPurchaseOrder(id);
        return ResponseEntity.ok(PurchaseOrderResponse.fromEntity(po));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    @Operation(summary = "List purchase orders", description = "List all purchase orders in the organization")
    public ResponseEntity<PageResponse<PurchaseOrderResponse>> listPurchaseOrders(Pageable pageable) {
        Page<PurchaseOrder> page = purchaseOrderService.listPurchaseOrders(pageable);
        Page<PurchaseOrderResponse> dtoPage = page.map(PurchaseOrderResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_WRITE')")
    @Operation(summary = "Update purchase order", description = "Update purchase order information")
    public ResponseEntity<PurchaseOrderResponse> updatePurchaseOrder(@PathVariable UUID id, @Valid @RequestBody CreatePurchaseOrderRequest request) {
        PurchaseOrder po = purchaseOrderService.updatePurchaseOrder(id, request);
        return ResponseEntity.ok(PurchaseOrderResponse.fromEntity(po));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_DELETE')")
    @Operation(summary = "Delete purchase order", description = "Soft delete purchase order record")
    public ResponseEntity<Void> deletePurchaseOrder(@PathVariable UUID id) {
        purchaseOrderService.deletePurchaseOrder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_DELETE')")
    @Operation(summary = "Restore purchase order", description = "Undo a soft delete on a purchase order")
    public ResponseEntity<PurchaseOrderResponse> restorePurchaseOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(PurchaseOrderResponse.fromEntity(purchaseOrderService.restorePurchaseOrder(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_DELETE')")
    @Operation(summary = "List deleted purchase orders", description = "Soft-deleted purchase orders still inside the retention window")
    public ResponseEntity<PageResponse<PurchaseOrderResponse>> listDeleted(Pageable pageable) {
        Page<PurchaseOrderResponse> page = purchaseOrderService.listDeleted(pageable).map(PurchaseOrderResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    @Operation(summary = "Download purchase order PDF", description = "Generates a PDF document for a purchase order")
    public ResponseEntity<byte[]> getPurchaseOrderPdf(@PathVariable UUID id) {
        PurchaseOrder po = purchaseOrderService.getPurchaseOrder(id);
        Partner vendor = null;
        if (po.getVendorPartnerId() != null) {
            vendor = partnerRepository.findByOrganizationIdAndId(po.getOrganizationId(), po.getVendorPartnerId()).orElse(null);
        }
        Organization org = organizationRepository.findById(po.getOrganizationId()).orElse(null);
        byte[] pdf = pdfDocumentService.generatePurchaseOrderPdf(po, vendor, org);

        String filename = "bon-commande-" + (po.getOrderNumber() != null ? po.getOrderNumber() : po.getId().toString().substring(0, 8)) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
