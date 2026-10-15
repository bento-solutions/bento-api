package com.bento.crm.invoice.service;

import com.bento.crm.brand.model.Brand;
import com.bento.crm.brand.repository.BrandRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.pdf.InvoiceBranding;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.invoice.dto.BrandInvoiceStyleRequest;
import com.bento.crm.invoice.dto.InvoiceTemplateRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.model.InvoiceTemplate;
import com.bento.crm.invoice.repository.InvoiceTemplateRepository;
import com.bento.crm.partner.model.Partner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.util.Optional;
import java.util.UUID;

/**
 * The organization's invoice layout settings, and the {@link InvoiceBranding} a given invoice is
 * printed with. A customer's brand overrides the organization's logo, footer and colour.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceTemplateService {

    private final InvoiceTemplateRepository templateRepository;
    private final BrandRepository brandRepository;
    private final FileStorageService fileStorageService;

    /** The caller's template, or an empty one when none has been saved. */
    public InvoiceTemplate getTemplate() {
        return templateFor(TenantContext.getCurrentOrganizationId());
    }

    @Transactional
    public InvoiceTemplate updateTemplate(InvoiceTemplateRequest r) {
        InvoiceTemplate t = getTemplate();
        UUID orgId = t.getOrganizationId();
        requireLogo(orgId, r.getLogoFileId());
        t.setLegalName(clean(r.getLegalName()));
        t.setAddress(clean(r.getAddress()));
        t.setPhone(clean(r.getPhone()));
        t.setEmail(clean(r.getEmail()));
        t.setWebsite(clean(r.getWebsite()));
        t.setIce(clean(r.getIce()));
        t.setIfNumber(clean(r.getIfNumber()));
        t.setRc(clean(r.getRc()));
        t.setPatente(clean(r.getPatente()));
        t.setCnss(clean(r.getCnss()));
        t.setCapital(clean(r.getCapital()));
        t.setBankName(clean(r.getBankName()));
        t.setRib(clean(r.getRib()));
        t.setAccentColor(clean(r.getAccentColor()));
        t.setLogoFileId(r.getLogoFileId());
        t.setPaymentTermsText(clean(r.getPaymentTermsText()));
        t.setFooterText(clean(r.getFooterText()));
        return templateRepository.save(t);
    }

    @Transactional
    public Brand updateBrandStyle(UUID brandId, BrandInvoiceStyleRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Brand brand = brandRepository.findByOrganizationIdAndId(orgId, brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
        requireLogo(orgId, r.getLogoFileId());
        brand.setInvoiceLogoFileId(r.getLogoFileId());
        brand.setInvoiceFooter(clean(r.getFooter()));
        return brandRepository.save(brand);
    }

    /** How this invoice is printed: its customer's brand first, then the organization's template. */
    public InvoiceBranding brandingFor(Invoice invoice, Partner partner) {
        UUID orgId = invoice.getOrganizationId();
        Optional<Brand> brand = Optional.ofNullable(partner).map(Partner::getBrandId)
                .flatMap(id -> brandRepository.findByOrganizationIdAndId(orgId, id))
                .or(() -> brandRepository.findDefaultByOrganizationId(orgId));
        return branding(templateFor(orgId), brand.orElse(null));
    }

    /** Logo and accent colour for the public pay page: the same choice the PDF makes. */
    public PublicLook publicLookFor(Invoice invoice, Partner partner) {
        UUID orgId = invoice.getOrganizationId();
        Brand brand = Optional.ofNullable(partner).map(Partner::getBrandId)
                .flatMap(id -> brandRepository.findByOrganizationIdAndId(orgId, id))
                .or(() -> brandRepository.findDefaultByOrganizationId(orgId))
                .orElse(null);
        InvoiceTemplate t = templateFor(orgId);
        UUID logoId = brand != null && brand.getInvoiceLogoFileId() != null ? brand.getInvoiceLogoFileId() : t.getLogoFileId();
        String colour = brand != null && brand.getColorHex() != null ? brand.getColorHex() : t.getAccentColor();
        String name = t.getLegalName() != null && !t.getLegalName().isBlank() ? t.getLegalName()
                : brand != null ? brand.getName() : null;
        return new PublicLook(name, logoId, colour);
    }

    public record PublicLook(String displayName, UUID logoFileId, String accentColor) {}

    /** The branding a given brand (or the organization alone, when null) prints with. */
    public InvoiceBranding brandingFor(UUID brandId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Brand brand = brandId == null ? null : brandRepository.findByOrganizationIdAndId(orgId, brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
        return branding(templateFor(orgId), brand);
    }

    private InvoiceBranding branding(InvoiceTemplate t, Brand brand) {
        UUID logoId = brand != null && brand.getInvoiceLogoFileId() != null ? brand.getInvoiceLogoFileId() : t.getLogoFileId();
        String footer = brand != null && brand.getInvoiceFooter() != null ? brand.getInvoiceFooter() : t.getFooterText();
        String colour = brand != null && brand.getColorHex() != null ? brand.getColorHex() : t.getAccentColor();
        return new InvoiceBranding(t.getLegalName(), t.getAddress(), t.getPhone(), t.getEmail(), t.getWebsite(),
                t.getIce(), t.getIfNumber(), t.getRc(), t.getPatente(), t.getCnss(), t.getCapital(),
                t.getBankName(), t.getRib(), color(colour), logo(t.getOrganizationId(), logoId),
                t.getPaymentTermsText(), footer);
    }

    private InvoiceTemplate templateFor(UUID orgId) {
        return templateRepository.findByOrganizationId(orgId).orElseGet(() -> {
            InvoiceTemplate t = new InvoiceTemplate();
            t.setOrganizationId(orgId);
            return t;
        });
    }

    /** A logo must be a PNG or JPEG uploaded against this organization, so it is served publicly. */
    private void requireLogo(UUID orgId, UUID fileId) {
        if (fileId == null) {
            return;
        }
        StoredFile file;
        try {
            file = fileStorageService.getPublicMetadata(fileId);
        } catch (ResourceNotFoundException e) {
            throw new IllegalArgumentException("The logo must be uploaded as an organization file");
        }
        String type = file.getContentType() == null ? "" : file.getContentType();
        if (!orgId.equals(file.getOrganizationId())) {
            throw new IllegalArgumentException("The logo must be uploaded as an organization file");
        }
        if (!type.equals("image/png") && !type.equals("image/jpeg")) {
            throw new IllegalArgumentException("The logo must be a PNG or JPEG image");
        }
    }

    private byte[] logo(UUID orgId, UUID fileId) {
        if (fileId == null) {
            return null;
        }
        try {
            StoredFile file = fileStorageService.getPublicMetadata(fileId);
            if (!orgId.equals(file.getOrganizationId())) {
                return null;
            }
            return fileStorageService.loadPublic(fileId).getContentAsByteArray();
        } catch (Exception e) {
            log.warn("Invoice logo {} could not be read: {}", fileId, e.getMessage());
            return null;
        }
    }

    private static Color color(String hex) {
        if (hex == null || !hex.matches("^#[0-9A-Fa-f]{6}$")) {
            return null;
        }
        return Color.decode(hex);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
