package com.bento.crm.brand.dto;

import com.bento.crm.brand.model.Brand;
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
public class BrandResponse {

    private UUID id;

    @JsonProperty("organization_id")
    private UUID organizationId;

    private String name;

    private String code;

    private String description;

    @JsonProperty("color_hex")
    private String colorHex;

    @JsonProperty("is_default")
    private Boolean isDefault;

    @JsonProperty("is_active")
    private Boolean isActive;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    public static BrandResponse fromEntity(Brand brand) {
        return BrandResponse.builder()
                .id(brand.getId())
                .organizationId(brand.getOrganizationId())
                .name(brand.getName())
                .code(brand.getCode())
                .description(brand.getDescription())
                .colorHex(brand.getColorHex())
                .isDefault(brand.getIsDefault())
                .isActive(brand.getIsActive())
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
