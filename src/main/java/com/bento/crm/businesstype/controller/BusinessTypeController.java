package com.bento.crm.businesstype.controller;

import com.bento.crm.businesstype.dto.BusinessTypeResponse;
import com.bento.crm.businesstype.dto.CreateBusinessTypeRequest;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.businesstype.service.BusinessTypeService;
import com.bento.crm.common.dto.PageResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/business-types")
@RequiredArgsConstructor
@Tag(name = "Business Types", description = "Structured referential for a partner's line of business")
public class BusinessTypeController {

    private final BusinessTypeService businessTypeService;

    @PostMapping
    @PreAuthorize("hasAuthority('PARTNERS_WRITE')")
    @Operation(summary = "Create business type")
    public ResponseEntity<BusinessTypeResponse> createBusinessType(@Valid @RequestBody CreateBusinessTypeRequest request) {
        BusinessType created = businessTypeService.createBusinessType(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessTypeResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "Get business type")
    public ResponseEntity<BusinessTypeResponse> getBusinessType(@PathVariable UUID id) {
        return ResponseEntity.ok(BusinessTypeResponse.fromEntity(businessTypeService.getBusinessType(id)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List business types", description = "Paged listing; use size=1000 to populate a filter/select control")
    public ResponseEntity<PageResponse<BusinessTypeResponse>> listBusinessTypes(Pageable pageable) {
        Page<BusinessTypeResponse> page = businessTypeService.listBusinessTypes(pageable).map(BusinessTypeResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_WRITE')")
    @Operation(summary = "Update business type")
    public ResponseEntity<BusinessTypeResponse> updateBusinessType(@PathVariable UUID id, @Valid @RequestBody CreateBusinessTypeRequest request) {
        return ResponseEntity.ok(BusinessTypeResponse.fromEntity(businessTypeService.updateBusinessType(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Delete business type", description = "Soft delete; partners already carrying this type keep it")
    public ResponseEntity<Void> deleteBusinessType(@PathVariable UUID id) {
        businessTypeService.deleteBusinessType(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Restore business type")
    public ResponseEntity<BusinessTypeResponse> restoreBusinessType(@PathVariable UUID id) {
        return ResponseEntity.ok(BusinessTypeResponse.fromEntity(businessTypeService.restoreBusinessType(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "List deleted business types")
    public ResponseEntity<PageResponse<BusinessTypeResponse>> listDeleted(Pageable pageable) {
        Page<BusinessTypeResponse> page = businessTypeService.listDeleted(pageable).map(BusinessTypeResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
