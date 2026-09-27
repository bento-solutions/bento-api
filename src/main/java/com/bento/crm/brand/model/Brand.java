package com.bento.crm.brand.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A product line partners are attributed to (BentoCars, BentoTravel, CRMbento...),
 * assigned at scraping/creation time so leads generated for different products stay
 * distinguishable in the Partners list.
 */
@Entity
@Table(name = "brand")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand extends BaseTenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    /** Short identifier (e.g. CARS, TRAVEL) for compact display and integrations; not required. */
    @Column(length = 20)
    private String code;

    @Column(columnDefinition = "text")
    private String description;

    /** Hex color (e.g. #2563EB) shown as the pastille next to a partner's brand in the list. */
    @Column(length = 7)
    private String colorHex;

    /** Applied to a lead created without an explicit brand; exactly one per organization. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
