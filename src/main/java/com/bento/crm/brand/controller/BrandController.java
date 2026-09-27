package com.bento.crm.brand.controller;

import com.bento.crm.brand.dto.BrandResponse;
import com.bento.crm.brand.dto.CreateBrandRequest;
import com.bento.crm.brand.model.Brand;
import com.bento.crm.brand.service.BrandService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
@Tag(name = "Brands", description = "Product-line referential (BentoCars, BentoTravel, CRMbento...) leads are attributed to")
public class BrandController {

    private final BrandService brandService;

    @PostMapping
    @PreAuthorize("hasAuthority('PARTNERS_WRITE')")
    @Operation(summary = "Create brand")
    public ResponseEntity<BrandResponse> createBrand(@Valid @RequestBody CreateBrandRequest request) {
        Brand created = brandService.createBrand(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BrandResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "Get brand")
    public ResponseEntity<BrandResponse> getBrand(@PathVariable UUID id) {
        return ResponseEntity.ok(BrandResponse.fromEntity(brandService.getBrand(id)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PARTNERS_READ')")
    @Operation(summary = "List brands", description = "Paged listing; use size=1000 to populate a filter/select control")
    public ResponseEntity<PageResponse<BrandResponse>> listBrands(Pageable pageable) {
        Page<BrandResponse> page = brandService.listBrands(pageable).map(BrandResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_WRITE')")
    @Operation(summary = "Update brand")
    public ResponseEntity<BrandResponse> updateBrand(@PathVariable UUID id, @Valid @RequestBody CreateBrandRequest request) {
        return ResponseEntity.ok(BrandResponse.fromEntity(brandService.updateBrand(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Delete brand", description = "Soft delete; refused with 409 while any lead still carries this brand")
    public ResponseEntity<Void> deleteBrand(@PathVariable UUID id) {
        brandService.deleteBrand(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "Restore brand")
    public ResponseEntity<BrandResponse> restoreBrand(@PathVariable UUID id) {
        return ResponseEntity.ok(BrandResponse.fromEntity(brandService.restoreBrand(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE')")
    @Operation(summary = "List deleted brands")
    public ResponseEntity<PageResponse<BrandResponse>> listDeleted(Pageable pageable) {
        Page<BrandResponse> page = brandService.listDeleted(pageable).map(BrandResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
