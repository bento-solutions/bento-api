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

    private Boolean trackStock;

    @jakarta.validation.constraints.PositiveOrZero
    private BigDecimal reorderLevel;

    /**
     * Ledger product posting group (null = GOODS for stocked products, SERVICES otherwise). On an
     * update a field left out keeps the product's group; an explicit null clears it.
     */
    private java.util.UUID productGroupId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean productGroupIdSet;

    public void setProductGroupId(java.util.UUID id) {
        this.productGroupId = id;
        this.productGroupIdSet = true;
    }
}
