package com.bento.crm.product.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.product.dto.CreateProductRequest;
import com.bento.crm.product.dto.ProductResponse;
import com.bento.crm.product.model.Product;
import com.bento.crm.product.service.ProductService;
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
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product and SKU catalog endpoints")
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @PreAuthorize("hasAuthority('PARTNERS_WRITE') or hasAuthority('DEALS_WRITE')")
    @Operation(summary = "Create product", description = "Create a new product in the catalog")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        Product created = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(ProductResponse.fromEntity(productService.getProduct(id)));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List products")
    public ResponseEntity<PageResponse<ProductResponse>> listProducts(Pageable pageable) {
        Page<ProductResponse> page = productService.listProducts(pageable).map(ProductResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_WRITE') or hasAuthority('DEALS_WRITE')")
    @Operation(summary = "Update product")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable UUID id, @Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(ProductResponse.fromEntity(productService.updateProduct(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE') or hasAuthority('DEALS_DELETE')")
    @Operation(summary = "Delete product", description = "Soft delete a product")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE') or hasAuthority('DEALS_DELETE')")
    @Operation(summary = "Restore product", description = "Undo a soft delete on a product")
    public ResponseEntity<ProductResponse> restoreProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(ProductResponse.fromEntity(productService.restoreProduct(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PARTNERS_DELETE') or hasAuthority('DEALS_DELETE')")
    @Operation(summary = "List deleted products", description = "Soft-deleted products still inside the retention window")
    public ResponseEntity<PageResponse<ProductResponse>> listDeleted(Pageable pageable) {
        Page<ProductResponse> page = productService.listDeleted(pageable).map(ProductResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
