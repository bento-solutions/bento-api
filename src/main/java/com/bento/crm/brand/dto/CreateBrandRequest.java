package com.bento.crm.brand.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateBrandRequest {

    @NotBlank
    private String name;

    private String code;

    private String description;

    @JsonProperty("color_hex")
    private String colorHex;

    @JsonProperty("is_default")
    private Boolean isDefault;

    @JsonProperty("is_active")
    private Boolean isActive;
}
