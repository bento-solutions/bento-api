package com.bento.crm.partner.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.partner.dto.BatchDeleteRequest;
import com.bento.crm.partner.dto.CreatePartnerRequest;
import com.bento.crm.partner.dto.PartnerResponse;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.service.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/partners")
@RequiredArgsConstructor
@Tag(name = "Partners", description = "Partner (Lead/Prospect/Customer/Vendor) management")
public class PartnerController {

    private final PartnerService partnerService;

    @PostMapping
    @PreAuthorize("hasAuthority('PARTNERS_CREATE')")
    @Operation(summary = "Create partner", description = "Create new partner (lead/prospect/customer/vendor)")
    public ResponseEntity<PartnerResponse> createPartner(@Valid @RequestBody CreatePartnerRequest request) {
        Partner partner = partnerService.createPartner(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PartnerResponse.fromEntity(partner));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "Get partner", description = "Retrieve partner details")
    public ResponseEntity<PartnerResponse> getPartner(@PathVariable UUID id) {
        Partner partner = partnerService.getPartner(id);
        return ResponseEntity.ok(PartnerResponse.fromEntity(partner));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List partners", description = "List all partners with optional search and multi-criteria filters")
    public ResponseEntity<PageResponse<PartnerResponse>> listPartners(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Partner.PartnerType type,
            @RequestParam(required = false) Partner.PartnerStage stage,
            @RequestParam(required = false) UUID assignedToUserId,
            Pageable pageable) {
        Page<PartnerResponse> page = partnerService.listPartners(q, type, stage, assignedToUserId, pageable)
                .map(PartnerResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping("/type/{type}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List partners by type", description = "List partners filtered by type")
    public ResponseEntity<PageResponse<PartnerResponse>> listPartnersByType(@PathVariable String type, Pageable pageable) {
        Page<PartnerResponse> page = partnerService.listPartnersByType(Partner.PartnerType.valueOf(type), pageable)
                .map(PartnerResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping("/stage/{stage}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List partners by stage", description = "List partners filtered by stage")
    public ResponseEntity<PageResponse<PartnerResponse>> listPartnersByStage(@PathVariable String stage, Pageable pageable) {
        Page<PartnerResponse> page = partnerService.listPartnersByStage(Partner.PartnerStage.valueOf(stage), pageable)
                .map(PartnerResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_WRITE')")
    @Operation(summary = "Update partner", description = "Update partner information")
    public ResponseEntity<PartnerResponse> updatePartner(@PathVariable UUID id, @Valid @RequestBody CreatePartnerRequest request) {
        Partner partner = partnerService.updatePartner(id, request);
        return ResponseEntity.ok(PartnerResponse.fromEntity(partner));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Delete partner",
            description = "Soft delete: the record leaves every listing but stays restorable until the retention window expires")
    public ResponseEntity<Void> deletePartner(@PathVariable UUID id) {
        partnerService.deletePartner(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Batch delete partners", description = "Soft delete several partners in one call")
    public ResponseEntity<Map<String, Integer>> batchDelete(@Valid @RequestBody BatchDeleteRequest request) {
        int deleted = partnerService.batchDelete(request.getIds());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Restore partner", description = "Undo a soft delete that has not yet been purged")
    public ResponseEntity<PartnerResponse> restorePartner(@PathVariable UUID id) {
        return ResponseEntity.ok(PartnerResponse.fromEntity(partnerService.restorePartner(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "List deleted partners", description = "Soft-deleted partners still inside the retention window")
    public ResponseEntity<PageResponse<PartnerResponse>> listDeleted(Pageable pageable) {
        Page<PartnerResponse> page = partnerService.listDeleted(pageable).map(PartnerResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
