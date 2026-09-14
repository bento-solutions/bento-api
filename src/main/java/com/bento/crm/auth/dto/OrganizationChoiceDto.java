package com.bento.crm.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationChoiceDto {

    @JsonProperty("organization_id")
    private UUID organizationId;

    @JsonProperty("organization_name")
    private String organizationName;

    @JsonProperty("role")
    private String role;

    @JsonProperty("last_active_at")
    private Instant lastActiveAt;
}
