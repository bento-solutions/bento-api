package com.bento.crm.brand.service;

import com.bento.crm.brand.dto.CreateBrandRequest;
import com.bento.crm.brand.model.Brand;
import com.bento.crm.brand.repository.BrandRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brandRepository;
    private final PartnerRepository partnerRepository;

    @Transactional
    public Brand createBrand(CreateBrandRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Brand brand = new Brand();
        brand.setOrganizationId(orgId);
        applyRequest(brand, request);
        if (Boolean.TRUE.equals(brand.getIsDefault())) {
            clearExistingDefault(orgId);
        } else if (brandRepository.findDefaultByOrganizationId(orgId).isEmpty()) {
            // The very first brand in an organization becomes the fallback used for leads
            // created without an explicit brand.
            brand.setIsDefault(true);
        }
        return brandRepository.save(brand);
    }

    public Brand getBrand(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return brandRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
    }

    public Page<Brand> listBrands(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return brandRepository.findByOrganizationId(orgId, pageable);
    }

    public List<Brand> listActiveBrands() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return brandRepository.findAllActiveByOrganizationId(orgId);
    }

    /** The brand applied to a lead created without one; empty only for an organization with no brand yet. */
    public Optional<Brand> findDefault(UUID orgId) {
        return brandRepository.findDefaultByOrganizationId(orgId);
    }

    @Transactional
    public Brand updateBrand(UUID id, CreateBrandRequest request) {
        Brand brand = getBrand(id);
        applyRequest(brand, request);
        if (Boolean.TRUE.equals(brand.getIsDefault())) {
            clearExistingDefault(brand.getOrganizationId(), id);
        }
        return brandRepository.save(brand);
    }

    @Transactional
    public void deleteBrand(UUID id) {
        Brand brand = getBrand(id);
        if (partnerRepository.existsByOrganizationIdAndBrandIdAndDeletedAtIsNull(brand.getOrganizationId(), id)) {
            throw new IllegalStateException("Brand is attached to existing leads and cannot be deleted");
        }
        brand.setDeletedAt(Instant.now());
        brandRepository.save(brand);
    }

    @Transactional
    public Brand restoreBrand(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Brand brand = brandRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
        brand.setDeletedAt(null);
        return brandRepository.save(brand);
    }

    public Page<Brand> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return brandRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    private void applyRequest(Brand brand, CreateBrandRequest request) {
        brand.setName(request.getName());
        brand.setCode(request.getCode());
        brand.setDescription(request.getDescription());
        brand.setColorHex(request.getColorHex());
        if (request.getIsDefault() != null) {
            brand.setIsDefault(request.getIsDefault());
        }
        if (request.getIsActive() != null) {
            brand.setIsActive(request.getIsActive());
        }
    }

    private void clearExistingDefault(UUID orgId) {
        clearExistingDefault(orgId, null);
    }

    private void clearExistingDefault(UUID orgId, UUID exceptId) {
        brandRepository.findDefaultByOrganizationId(orgId)
                .filter(existing -> !existing.getId().equals(exceptId))
                .ifPresent(existing -> {
                    existing.setIsDefault(false);
                    brandRepository.save(existing);
                });
    }
}
