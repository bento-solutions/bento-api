package com.bento.crm.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateProductRequest {

    @NotBlank
    private String sku;

    @NotBlank
    private String name;

    private String description;

    private String category;

    @NotNull
    private BigDecimal unitPrice;

    private BigDecimal costPrice;

    private BigDecimal taxRate;

    private Boolean isActive;
}
