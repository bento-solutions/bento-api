package com.bento.crm.partner.controller;

import com.bento.crm.brand.model.Brand;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.partner.dto.BatchDeleteRequest;
import com.bento.crm.partner.dto.CreatePartnerRequest;
import com.bento.crm.partner.dto.LeadKpiResponse;
import com.bento.crm.partner.dto.PartnerResponse;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.service.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(enrich(partner));
    }

    @PostMapping("/batch-import")
    @PreAuthorize("hasAuthority('PARTNERS_CREATE')")
    @Operation(summary = "Batch import partners", description = "Create a scraped/imported lot of leads sharing one brand and business type")
    public ResponseEntity<Map<String, Object>> batchImport(
            @Valid @RequestBody com.bento.crm.partner.dto.BatchImportPartnersRequest request) {
        List<Partner> created = partnerService.batchImport(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "created", created.size(),
                "ids", created.stream().map(Partner::getId).toList()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "Get partner", description = "Full details of one lead, prospect, customer or vendor: "
            + "contact info, stage, score, brand, notes… Field names are snake_case (`company_name`, `assigned_to_user_id`…).")
    public ResponseEntity<PartnerResponse> getPartner(@Parameter(description = "Partner id, from the partner list") @PathVariable UUID id) {
        Partner partner = partnerService.getPartner(id);
        return ResponseEntity.ok(enrich(partner));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List partners", description = "Search leads, prospects, customers and vendors. "
            + "Combine any filters. Results are paged: use `page` (starting at 0) and `size`, and read "
            + "`total_pages` / `total_elements` in the answer to know when you have everything. "
            + "The `id` of a result is what the WhatsApp endpoints call `partnerId`.")
    public ResponseEntity<PageResponse<PartnerResponse>> listPartners(
            @Parameter(description = "Search text: matches name, company, email, phone or city", example = "Atlas")
            @RequestParam(required = false) String q,
            @Parameter(description = "LEAD, PROSPECT, CUSTOMER or VENDOR") @RequestParam(required = false) Partner.PartnerType type,
            @Parameter(description = "Pipeline stage, e.g. NEW, CONTACTED, QUALIFIED, PROPOSAL_SENT, CUSTOMER, LOST") @RequestParam(required = false) Partner.PartnerStage stage,
            @Parameter(description = "Only records assigned to this user id") @RequestParam(required = false) UUID assignedToUserId,
            @Parameter(description = "Only records of this brand (marque) id") @RequestParam(required = false) UUID brandId,
            @Parameter(description = "Only records of this business type id") @RequestParam(required = false) UUID businessTypeId,
            @Parameter(description = "Only records interested in this product (text)") @RequestParam(required = false) String interestedProduct,
            Pageable pageable) {
        Page<Partner> page = partnerService.listPartners(
                q, type, stage, assignedToUserId, brandId, businessTypeId, interestedProduct, pageable);
        return ResponseEntity.ok(PageResponse.fromPage(enrich(page)));
    }

    @GetMapping("/type/{type}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List partners by type", description = "List partners filtered by type")
    public ResponseEntity<PageResponse<PartnerResponse>> listPartnersByType(@PathVariable String type, Pageable pageable) {
        Page<PartnerResponse> page = partnerService.listPartnersByType(Partner.PartnerType.valueOf(type), pageable)
                .map(PartnerResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping("/stats/leads")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "Lead KPI", description = "Lead totals and a 12-month creation series for the dashboard's New Leads tile")
    public ResponseEntity<LeadKpiResponse> leadKpi() {
        return ResponseEntity.ok(partnerService.getLeadKpi());
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
        return ResponseEntity.ok(enrich(partner));
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

    private PartnerResponse enrich(Partner partner) {
        Map<UUID, Brand> brands = partnerService.loadBrandsByIds(java.util.Collections.singletonList(partner.getBrandId()));
        Map<UUID, BusinessType> businessTypes =
                partnerService.loadBusinessTypesByIds(java.util.Collections.singletonList(partner.getBusinessTypeId()));
        return PartnerResponse.fromEntity(partner, lookup(brands, partner.getBrandId()), lookup(businessTypes, partner.getBusinessTypeId()));
    }

    /** Immutable maps from the service reject a null key, and brand/business type are optional on a partner. */
    private static <T> T lookup(Map<UUID, T> byId, UUID id) {
        return id == null ? null : byId.get(id);
    }

    private Page<PartnerResponse> enrich(Page<Partner> page) {
        List<UUID> brandIds = page.getContent().stream().map(Partner::getBrandId).toList();
        List<UUID> businessTypeIds = page.getContent().stream().map(Partner::getBusinessTypeId).toList();
        Map<UUID, Brand> brands = partnerService.loadBrandsByIds(brandIds);
        Map<UUID, BusinessType> businessTypes = partnerService.loadBusinessTypesByIds(businessTypeIds);
        return page.map(partner -> PartnerResponse.fromEntity(
                partner, lookup(brands, partner.getBrandId()), lookup(businessTypes, partner.getBusinessTypeId())));
    }
}
