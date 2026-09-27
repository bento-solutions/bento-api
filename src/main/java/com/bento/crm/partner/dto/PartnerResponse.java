package com.bento.crm.partner.dto;

import com.bento.crm.brand.model.Brand;
import com.bento.crm.businesstype.model.BusinessType;
import com.bento.crm.partner.model.Partner;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uses the same snake_case wire format as {@link CreatePartnerRequest} so a
 * partner read back from GET /partners round-trips through the same field
 * names it was written with.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerResponse {

    private UUID id;

    @JsonProperty("organization_id")
    private UUID organizationId;

    private Partner.PartnerType type;

    private String name;

    @JsonProperty("company_name")
    private String companyName;

    @JsonProperty("record_type")
    private Partner.RecordType recordType;

    private String email;

    private String phone;

    private String city;

    private String country;

    private Partner.PartnerSource source;

    private Integer score;

    private Partner.Temperature temperature;

    private Partner.Priority priority;

    private Partner.Qualification qualification;

    private Partner.PartnerStage stage;

    @JsonProperty("brand_id")
    private UUID brandId;

    @JsonProperty("brand_name")
    private String brandName;

    @JsonProperty("brand_color")
    private String brandColor;

    @JsonProperty("business_type_id")
    private UUID businessTypeId;

    @JsonProperty("business_type_name")
    private String businessTypeName;

    @JsonProperty("assigned_to_user_id")
    private UUID assignedToUserId;

    @JsonProperty("owner_id")
    private UUID ownerId;

    @JsonProperty("converted_from_partner_id")
    private UUID convertedFromPartnerId;

    @JsonProperty("estimated_deal_value")
    private BigDecimal estimatedDealValue;

    private Integer probability;

    @JsonProperty("expected_close_date")
    private LocalDate expectedCloseDate;

    private String comments;

    private Map<String, Object> company;

    @JsonProperty("product_interests")
    private List<Map<String, Object>> productInterests;

    private List<Map<String, Object>> campaigns;

    private String notes;

    @JsonProperty("external_id")
    private String externalId;

    @JsonProperty("source_url")
    private String sourceUrl;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    /** Non-null only in the "recently deleted" listing; drives the restore/purge countdown. */
    @JsonProperty("deleted_at")
    private Instant deletedAt;

    public static PartnerResponse fromEntity(Partner partner) {
        return fromEntity(partner, null, null);
    }

    public static PartnerResponse fromEntity(Partner partner, Brand brand, BusinessType businessType) {
        return PartnerResponse.builder()
                .id(partner.getId())
                .organizationId(partner.getOrganizationId())
                .type(partner.getType())
                .name(partner.getName())
                .companyName(partner.getCompanyName())
                .recordType(partner.getRecordType())
                .email(partner.getEmail())
                .phone(partner.getPhone())
                .city(partner.getCity())
                .country(partner.getCountry())
                .source(partner.getSource())
                .score(partner.getScore())
                .temperature(partner.getTemperature())
                .priority(partner.getPriority())
                .qualification(partner.getQualification())
                .stage(partner.getStage())
                .brandId(partner.getBrandId())
                .brandName(brand != null ? brand.getName() : null)
                .brandColor(brand != null ? brand.getColorHex() : null)
                .businessTypeId(partner.getBusinessTypeId())
                .businessTypeName(businessType != null ? businessType.getName() : null)
                .assignedToUserId(partner.getAssignedToUserId())
                .ownerId(partner.getOwnerId())
                .convertedFromPartnerId(partner.getConvertedFromPartnerId())
                .estimatedDealValue(partner.getEstimatedDealValue())
                .probability(partner.getProbability())
                .expectedCloseDate(partner.getExpectedCloseDate())
                .comments(partner.getComments())
                .company(partner.getCompany())
                .productInterests(partner.getProductInterests())
                .campaigns(partner.getCampaigns())
                .notes(partner.getNotes())
                .externalId(partner.getExternalId())
                .sourceUrl(partner.getSourceUrl())
                .createdAt(partner.getCreatedAt())
                .deletedAt(partner.getDeletedAt())
                .updatedAt(partner.getUpdatedAt())
                .build();
    }
}
