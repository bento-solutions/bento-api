package com.bento.crm.partner.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.notification.event.AssignmentNotificationFactory;
import com.bento.crm.partner.dto.CreatePartnerRequest;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.partner.repository.PartnerSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartnerService {

    private final PartnerRepository partnerRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Partner createPartner(CreatePartnerRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();

        String externalId = blankToNull(request.getExternalId());
        String email = normalizeEmail(request.getEmail());
        String phone = blankToNull(request.getPhone());

        // Bot-ingested leads must carry a contact channel: email OR phone.
        // Hand-entered rows (no external_id) keep the old lenient behaviour.
        if (externalId != null && email == null && phone == null) {
            throw new IllegalArgumentException("Bot leads require email or phone when external_id is present");
        }

        if (externalId != null) {
            Optional<Partner> existing = partnerRepository.findByOrganizationIdAndExternalId(orgId, externalId);
            if (existing.isPresent()) {
                throw new IllegalStateException(
                        "Duplicate lead: external_id already exists id=" + existing.get().getId());
            }
        }

        Partner partner = Partner.builder()
                .type(parseEnum(Partner.PartnerType.class, request.getType(), "type", true))
                .name(request.getName())
                .companyName(request.getCompanyName())
                .email(email)
                .phone(phone)
                .city(request.getCity())
                .country(request.getCountry())
                .source(parseEnum(Partner.PartnerSource.class, request.getSource(), "source", false))
                .score(request.getScore())
                .temperature(parseEnum(Partner.Temperature.class, request.getTemperature(), "temperature", false))
                .priority(parseEnum(Partner.Priority.class, request.getPriority(), "priority", false))
                .qualification(parseEnum(Partner.Qualification.class, request.getQualification(), "qualification", false))
                .stage(parseEnum(Partner.PartnerStage.class,
                        request.getStage() != null ? request.getStage() : "NEW", "stage", true))
                .assignedToUserId(request.getAssignedToUserId() != null ? UUID.fromString(request.getAssignedToUserId()) : null)
                .ownerId(request.getOwnerId() != null ? UUID.fromString(request.getOwnerId()) : null)
                .estimatedDealValue(request.getEstimatedDealValue())
                .probability(request.getProbability())
                .expectedCloseDate(request.getExpectedCloseDate())
                .comments(request.getComments())
                .company(request.getCompany())
                .productInterests(request.getProductInterests() != null ? request.getProductInterests() : List.<Map<String, Object>>of())
                .campaigns(request.getCampaigns() != null ? request.getCampaigns() : List.<Map<String, Object>>of())
                .notes(request.getNotes())
                .externalId(externalId)
                .sourceUrl(blankToNull(request.getSourceUrl()))
                .build();
        partner.setOrganizationId(orgId);

        Partner saved = partnerRepository.save(partner);
        notifyIfAssigned(orgId, null, saved);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(orgId)
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.PARTNER_CREATED)
                .entityType("PARTNER")
                .entityId(saved.getId())
                .payload(partnerToPayload(saved))
                .build());
        return saved;
    }

    public Partner getPartner(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return partnerRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
    }

    public Page<Partner> listPartners(Pageable pageable) {
        return listPartners(null, null, null, null, pageable);
    }

    public Page<Partner> listPartners(String q, Partner.PartnerType type, Partner.PartnerStage stage, UUID assignedToUserId, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if ((q == null || q.isBlank()) && type == null && stage == null && assignedToUserId == null) {
            return partnerRepository.findByOrganizationId(orgId, pageable);
        }
        return partnerRepository.findAll(PartnerSpecification.filter(orgId, q, type, stage, assignedToUserId), pageable);
    }

    public Page<Partner> listPartnersByType(Partner.PartnerType type, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return partnerRepository.findByOrganizationIdAndType(orgId, type, pageable);
    }

    public Page<Partner> listPartnersByStage(Partner.PartnerStage stage, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return partnerRepository.findByOrganizationIdAndStage(orgId, stage, pageable);
    }

    @Transactional
    public Partner updatePartner(UUID id, CreatePartnerRequest request) {
        Partner partner = getPartner(id);
        UUID previousAssignee = partner.getAssignedToUserId();

        partner.setType(parseEnum(Partner.PartnerType.class, request.getType(), "type", true));
        partner.setName(request.getName());
        partner.setCompanyName(request.getCompanyName());
        partner.setEmail(normalizeEmail(request.getEmail()));
        partner.setPhone(blankToNull(request.getPhone()));
        partner.setCity(request.getCity());
        partner.setCountry(request.getCountry());
        partner.setSource(parseEnum(Partner.PartnerSource.class, request.getSource(), "source", false));
        partner.setScore(request.getScore());
        partner.setTemperature(parseEnum(Partner.Temperature.class, request.getTemperature(), "temperature", false));
        partner.setPriority(parseEnum(Partner.Priority.class, request.getPriority(), "priority", false));
        partner.setQualification(parseEnum(Partner.Qualification.class, request.getQualification(), "qualification", false));
        if (request.getStage() != null) {
            partner.setStage(parseEnum(Partner.PartnerStage.class, request.getStage(), "stage", true));
        }
        partner.setAssignedToUserId(request.getAssignedToUserId() != null ? UUID.fromString(request.getAssignedToUserId()) : null);
        partner.setOwnerId(request.getOwnerId() != null ? UUID.fromString(request.getOwnerId()) : null);
        partner.setEstimatedDealValue(request.getEstimatedDealValue());
        partner.setProbability(request.getProbability());
        partner.setExpectedCloseDate(request.getExpectedCloseDate());
        partner.setComments(request.getComments());
        partner.setCompany(request.getCompany());
        partner.setProductInterests(request.getProductInterests() != null ? request.getProductInterests() : List.<Map<String, Object>>of());
        partner.setCampaigns(request.getCampaigns() != null ? request.getCampaigns() : List.<Map<String, Object>>of());
        partner.setNotes(request.getNotes());

        Partner saved = partnerRepository.save(partner);
        notifyIfAssigned(saved.getOrganizationId(), previousAssignee, saved);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(saved.getOrganizationId())
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.PARTNER_UPDATED)
                .entityType("PARTNER")
                .entityId(saved.getId())
                .payload(partnerToPayload(saved))
                .build());
        return saved;
    }

    private java.util.Map<String, Object> partnerToPayload(Partner p) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("companyName", p.getCompanyName());
        map.put("type", p.getType() != null ? p.getType().name() : null);
        map.put("stage", p.getStage() != null ? p.getStage().name() : null);
        map.put("score", p.getScore());
        map.put("city", p.getCity());
        map.put("country", p.getCountry());
        map.put("source", p.getSource() != null ? p.getSource().name() : null);
        map.put("estimatedDealValue", p.getEstimatedDealValue());
        return map;
    }

    private void notifyIfAssigned(UUID orgId, UUID previousAssignee, Partner partner) {
        UUID current = partner.getAssignedToUserId();
        if (current == null || java.util.Objects.equals(current, previousAssignee)) {
            return;
        }
        eventPublisher.publishEvent(AssignmentNotificationFactory.forLead(
                orgId, current, currentActor(), partner.getId(), partner.getName()));
    }

    private static UUID currentActor() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                    : null;
            return principal instanceof String s ? UUID.fromString(s) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Soft delete: the partner drops out of every list but stays restorable until
     * {@link PartnerPurgeScheduler} removes it after the retention window.
     */
    @Transactional
    public void deletePartner(UUID id) {
        Partner partner = getPartner(id);
        partner.setDeletedAt(Instant.now());
        partnerRepository.save(partner);
    }

    @Transactional
    public int batchDelete(List<UUID> ids) {
        Instant now = Instant.now();
        int deleted = 0;
        for (UUID id : ids) {
            Partner partner = getPartner(id);
            partner.setDeletedAt(now);
            partnerRepository.save(partner);
            deleted++;
        }
        return deleted;
    }

    @Transactional
    public Partner restorePartner(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Partner partner = partnerRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
        partner.setDeletedAt(null);
        return partnerRepository.save(partner);
    }

    public Page<Partner> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return partnerRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeEmail(String email) {
        String clean = blankToNull(email);
        return clean != null ? clean.toLowerCase() : null;
    }

    /**
     * Enum parsing with a 400-friendly error instead of the raw
     * {@code IllegalArgumentException} from {@code valueOf()}, which the bot
     * needs to distinguish bad payloads from server failures.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, boolean required) {
        String clean = blankToNull(raw);
        if (clean == null) {
            if (required) {
                throw new IllegalArgumentException("Field '" + field + "' is required");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, clean);
        } catch (IllegalArgumentException ex) {
            String allowed = Arrays.stream(type.getEnumConstants())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Field '" + field + "' has invalid value '" + raw + "'. Allowed: " + allowed);
        }
    }
}
