package com.bento.crm.organization.service;

import com.bento.crm.brand.model.Brand;
import com.bento.crm.brand.repository.BrandRepository;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.businesstype.repository.BusinessTypeRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.organization.dto.CreateOrganizationRequest;
import com.bento.crm.organization.dto.UpdateOrganizationRequest;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.service.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final BrandRepository brandRepository;
    private final BusinessTypeRepository businessTypeRepository;

    @Transactional
    public Organization createOrganization(CreateOrganizationRequest request) {
        // Signup is unauthenticated, so reject an address that is already registered anywhere.
        // Without this an attacker could create an organization under a customer's email and add
        // a second account resolvable by the cross-tenant login lookup.
        String adminEmail = request.getAdminEmail().trim().toLowerCase();
        if (!userRepository.findAllByEmailAcrossOrganizations(adminEmail).isEmpty()) {
            throw new IllegalStateException("An account with this email already exists");
        }

        Organization organization = Organization.builder()
                .name(request.getName())
                .industry(request.getIndustry())
                .defaultCurrency(request.getDefaultCurrency() != null ? request.getDefaultCurrency() : "USD")
                .timezone(request.getTimezone() != null ? request.getTimezone() : "UTC")
                .fiscalYearStartMonth(1)
                .plan("FREE")
                .build();

        organization = organizationRepository.save(organization);

        TenantContext.setCurrentOrganizationId(organization.getId());

        AppUser adminUser = AppUser.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(request.getAdminPassword()))
                .displayName(request.getAdminName())
                .role(UserRole.ADMIN)
                .isActive(true)
                .language("en")
                .avatarColor("blue")
                .build();
        adminUser.setOrganizationId(organization.getId());

        userRepository.save(adminUser);
        seedDefaultBrandsAndBusinessTypes(organization.getId());
        TenantContext.clear();

        log.info("Organization created: {} with admin user: {}", organization.getId(), adminEmail);
        return organization;
    }

    /**
     * Every organization starts with the Brand/Business type referentials the Partners module
     * needs so a lead can be attributed from day one; without this a fresh organization would have
     * no default brand to fall back to when a lead is created without one.
     */
    private void seedDefaultBrandsAndBusinessTypes(UUID organizationId) {
        List<Brand> brands = List.of(
                Brand.builder().name("BentoCars").code("CARS").colorHex("#2563EB").isDefault(true).isActive(true).build(),
                Brand.builder().name("BentoTravel").code("TRAVEL").colorHex("#059669").isDefault(false).isActive(true).build(),
                Brand.builder().name("CRMbento").code("CRM").colorHex("#7C3AED").isDefault(false).isActive(true).build()
        );
        brands.forEach(brand -> {
            brand.setOrganizationId(organizationId);
            brandRepository.save(brand);
        });

        List<String> businessTypeNames = List.of(
                "Agence de location", "Agence de voyage", "Concessionnaire moto", "Import/export", "Centre de formation"
        );
        businessTypeNames.forEach(name -> {
            BusinessType businessType = BusinessType.builder().name(name).isActive(true).build();
            businessType.setOrganizationId(organizationId);
            businessTypeRepository.save(businessType);
        });
    }

    public Organization getCurrentOrganization() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    @Transactional
    public Organization updateCurrentOrganization(UpdateOrganizationRequest request) {
        Organization organization = getCurrentOrganization();

        if (request.getName() != null && !request.getName().isBlank()) {
            organization.setName(request.getName());
        }
        if (request.getIndustry() != null) {
            organization.setIndustry(request.getIndustry());
        }
        if (request.getLogoUrl() != null) {
            organization.setLogoUrl(request.getLogoUrl().isBlank() ? null : request.getLogoUrl());
        }
        if (request.getTimezone() != null) {
            organization.setTimezone(request.getTimezone());
        }
        if (request.getDefaultCurrency() != null) {
            organization.setDefaultCurrency(request.getDefaultCurrency());
        }
        if (request.getFiscalYearStartMonth() != null) {
            organization.setFiscalYearStartMonth(request.getFiscalYearStartMonth());
        }

        organization = organizationRepository.save(organization);
        log.info("Organization updated: {}", organization.getId());
        return organization;
    }

    @Transactional
    public Organization uploadLogo(MultipartFile file) {
        Organization organization = getCurrentOrganization();
        StoredFile storedFile = fileStorageService.store(file, "ORGANIZATION", organization.getId());
        organization.setLogoUrl("/api/v1/files/public/" + storedFile.getId());
        organization = organizationRepository.save(organization);
        log.info("Organization {} logo uploaded: {}", organization.getId(), storedFile.getId());
        return organization;
    }
}
