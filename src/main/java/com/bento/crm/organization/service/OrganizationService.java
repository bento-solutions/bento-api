package com.bento.crm.organization.service;

import com.bento.crm.brand.model.Brand;
import com.bento.crm.brand.repository.BrandRepository;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.businesstype.repository.BusinessTypeRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.dto.AccountDtos;
import com.bento.crm.identity.model.Account;
import com.bento.crm.identity.service.AccountService;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AccountRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final BrandRepository brandRepository;
    private final BusinessTypeRepository businessTypeRepository;
    private final com.bento.crm.stock.service.StockService stockService;

    /**
     * What sign-up produced: the new Organization, or, when the rest happens from an emailed link,
     * the address that link went to. The two outcomes look the same to the caller whether or not
     * the address already had an Account.
     */
    public record SignUpResult(Organization organization, String checkEmail) {
    }

    /**
     * Unauthenticated sign-up: a new Account with its first Organization. It never touches an
     * existing Account, since that would let anyone who knows an address attach an Organization
     * (and a password) to someone else's sign-in.
     *
     * <p>When addresses must be verified, the answer is "check your email" either way: a new
     * address gets a confirmation link, a known one is told to sign in instead.
     */
    @Transactional
    public SignUpResult signUp(CreateOrganizationRequest request) {
        String adminEmail = Account.normalizeEmail(request.getAdminEmail());
        Optional<Account> existing = accountRepository.findByEmail(adminEmail);
        if (existing.isPresent()) {
            if (!accountService.verificationRequired()) {
                throw new IllegalStateException("An account with this email already exists");
            }
            passwordEncoder.encode(request.getAdminPassword()); // same cost as a new address
            accountService.sendAlreadyRegistered(existing.get());
            return new SignUpResult(null, adminEmail);
        }

        Account account = accountRepository.save(Account.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(request.getAdminPassword()))
                .passwordChangedAt(Instant.now())
                .build());
        AppUser admin = createOrganizationFor(account.getId(), adminEmail, request.getAdminName(), "en",
                request.getName(), request.getIndustry(), request.getDefaultCurrency(), request.getTimezone(),
                request.getStockIssueTrigger());

        if (accountService.verificationRequired()) {
            accountService.sendVerification(account);
            return new SignUpResult(null, adminEmail);
        }
        return new SignUpResult(organizationRepository.findById(admin.getOrganizationId()).orElseThrow(), null);
    }

    /** A signed-in person creates another Organization and becomes its admin. */
    @Transactional
    public AppUser createAnotherOrganization(AppUser current, AccountDtos.CreateOrganizationRequest request) {
        return createOrganizationFor(current.getAccountId(), current.getEmail(), current.getDisplayName(),
                current.getLanguage() != null ? current.getLanguage() : "en",
                request.name(), request.industry(), request.defaultCurrency(), request.timezone(), null);
    }

    /** Creates an Organization with the Account's admin Membership and the referentials it starts with. */
    private AppUser createOrganizationFor(UUID accountId, String email, String displayName, String language,
                                          String name, String industry, String currency, String timezone,
                                          com.bento.crm.stock.model.StockSettings.IssueTrigger stockIssueTrigger) {
        Organization organization = organizationRepository.save(Organization.builder()
                .name(name)
                .industry(industry)
                .defaultCurrency(currency != null ? currency.toUpperCase(java.util.Locale.ROOT) : "USD")
                .timezone(timezone != null ? timezone : "UTC")
                .fiscalYearStartMonth(1)
                .plan("FREE")
                .build());

        // The rest of the request acts in the new Organization: its rows are written there, and a
        // signed-in caller gets a session in it. TenantFilterInterceptor clears this when the
        // request ends.
        TenantContext.setCurrentOrganizationId(organization.getId());
        AppUser admin = AppUser.builder()
                .accountId(accountId)
                .email(email)
                .displayName(displayName)
                .role(UserRole.ADMIN)
                .isActive(true)
                .language(language)
                .avatarColor("blue")
                .build();
        admin.setOrganizationId(organization.getId());
        admin = userRepository.save(admin);
        seedDefaultBrandsAndBusinessTypes(organization.getId());
        stockService.initialize(organization.getId(), stockIssueTrigger, industry);
        log.info("Organization {} created with admin Membership {} of Account {}", organization.getId(), admin.getId(), accountId);
        return admin;
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
