package com.bento.crm.partner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePartnerRequest {

    @NotBlank
    private String type;

    @NotBlank
    private String name;

    @JsonProperty("company_name")
    private String companyName;

    private String email;

    private String phone;

    private String city;

    private String country;

    private String source;

    private Integer score;

    private String temperature;

    private String priority;

    private String qualification;

    private String stage;

    /** Product line this lead was prospected for; defaults to the organization's default brand when omitted. */
    @JsonProperty("brand_id")
    private String brandId;

    @JsonProperty("business_type_id")
    private String businessTypeId;

    @JsonProperty("assigned_to_user_id")
    private String assignedToUserId;

    @JsonProperty("owner_id")
    private String ownerId;

    @JsonProperty("estimated_deal_value")
    private BigDecimal estimatedDealValue;

    private Integer probability;

    private String comments;

    @JsonProperty("expected_close_date")
    private LocalDate expectedCloseDate;

    private Map<String, Object> company;

    @JsonProperty("product_interests")
    private List<Map<String, Object>> productInterests;

    private List<Map<String, Object>> campaigns;

    private String notes;

    @JsonProperty("external_id")
    private String externalId;

    @JsonProperty("source_url")
    private String sourceUrl;
}
