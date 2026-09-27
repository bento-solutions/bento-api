package com.bento.crm.businesstype.dto;

import com.bento.crm.businesstype.model.BusinessType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessTypeResponse {

    private UUID id;

    @JsonProperty("organization_id")
    private UUID organizationId;

    private String name;

    @JsonProperty("is_active")
    private Boolean isActive;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    public static BusinessTypeResponse fromEntity(BusinessType businessType) {
        return BusinessTypeResponse.builder()
                .id(businessType.getId())
                .organizationId(businessType.getOrganizationId())
                .name(businessType.getName())
                .isActive(businessType.getIsActive())
                .createdAt(businessType.getCreatedAt())
                .updatedAt(businessType.getUpdatedAt())
                .build();
    }
}
