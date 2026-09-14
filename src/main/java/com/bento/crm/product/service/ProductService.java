package com.bento.crm.product.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.product.dto.CreateProductRequest;
import com.bento.crm.product.model.Product;
import com.bento.crm.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        Product p = new Product();
        p.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(p, request);
        return productRepository.save(p);
    }

    public Product getProduct(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return productRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    public Page<Product> listProducts(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return productRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public Product updateProduct(UUID id, CreateProductRequest request) {
        Product p = getProduct(id);
        applyRequest(p, request);
        return productRepository.save(p);
    }

    private void applyRequest(Product p, CreateProductRequest request) {
        p.setSku(request.getSku());
        p.setName(request.getName());
        p.setDescription(request.getDescription());
        p.setCategory(request.getCategory());
        p.setUnitPrice(request.getUnitPrice());
        p.setCostPrice(request.getCostPrice() != null ? request.getCostPrice() : BigDecimal.ZERO);
        if (request.getTaxRate() != null) {
            p.setTaxRate(request.getTaxRate());
        }
        if (request.getIsActive() != null) {
            p.setIsActive(request.getIsActive());
        }
    }

    @Transactional
    public void deleteProduct(UUID id) {
        Product p = getProduct(id);
        p.setDeletedAt(Instant.now());
        productRepository.save(p);
    }

    @Transactional
    public Product restoreProduct(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Product p = productRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        p.setDeletedAt(null);
        return productRepository.save(p);
    }

    public Page<Product> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return productRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
