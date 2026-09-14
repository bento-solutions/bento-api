package com.bento.crm.product.dto;

import com.bento.crm.product.model.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private UUID id;
    private UUID organizationId;
    private String sku;
    private String name;
    private String description;
    private String category;
    private BigDecimal unitPrice;
    private BigDecimal costPrice;
    private BigDecimal taxRate;
    private Boolean isActive;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdBy;
    private UUID updatedBy;

    public static ProductResponse fromEntity(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .organizationId(p.getOrganizationId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .category(p.getCategory())
                .unitPrice(p.getUnitPrice())
                .costPrice(p.getCostPrice())
                .taxRate(p.getTaxRate())
                .isActive(p.getIsActive())
                .version(p.getVersion())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .createdBy(p.getCreatedBy())
                .updatedBy(p.getUpdatedBy())
                .build();
    }
}
