package com.bento.crm.businesstype.service;

import com.bento.crm.businesstype.dto.CreateBusinessTypeRequest;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.businesstype.repository.BusinessTypeRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessTypeService {

    private final BusinessTypeRepository businessTypeRepository;

    @Transactional
    public BusinessType createBusinessType(CreateBusinessTypeRequest request) {
        BusinessType businessType = new BusinessType();
        businessType.setOrganizationId(TenantContext.getCurrentOrganizationId());
        applyRequest(businessType, request);
        return businessTypeRepository.save(businessType);
    }

    public BusinessType getBusinessType(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return businessTypeRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Business type not found"));
    }

    public Page<BusinessType> listBusinessTypes(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return businessTypeRepository.findByOrganizationId(orgId, pageable);
    }

    public List<BusinessType> listActiveBusinessTypes() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return businessTypeRepository.findAllActiveByOrganizationId(orgId);
    }

    @Transactional
    public BusinessType updateBusinessType(UUID id, CreateBusinessTypeRequest request) {
        BusinessType businessType = getBusinessType(id);
        applyRequest(businessType, request);
        return businessTypeRepository.save(businessType);
    }

    @Transactional
    public void deleteBusinessType(UUID id) {
        BusinessType businessType = getBusinessType(id);
        businessType.setDeletedAt(Instant.now());
        businessTypeRepository.save(businessType);
    }

    @Transactional
    public BusinessType restoreBusinessType(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        BusinessType businessType = businessTypeRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Business type not found"));
        businessType.setDeletedAt(null);
        return businessTypeRepository.save(businessType);
    }

    public Page<BusinessType> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return businessTypeRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    private void applyRequest(BusinessType businessType, CreateBusinessTypeRequest request) {
        businessType.setName(request.getName());
        if (request.getIsActive() != null) {
            businessType.setIsActive(request.getIsActive());
        }
    }
}
