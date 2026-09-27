package com.bento.crm.businesstype.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateBusinessTypeRequest {

    @NotBlank
    private String name;

    @JsonProperty("is_active")
    private Boolean isActive;
}
