package com.bento.crm.businesstype.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured referential for a partner's line of business (agence de location, agence de
 * voyage, concessionnaire moto, import/export...), replacing the free-text
 * {@code company.business_type} JSON field so the Partners list can filter on it reliably.
 */
@Entity
@Table(name = "business_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessType extends BaseTenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
