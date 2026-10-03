package com.bento.crm.category.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * A single-word coloured label ("CRMbento", "Orthoflow") telling which product or project a
 * ticket or task concerns. A ticket or task carries at most one; tasks on a ticket take the
 * ticket's.
 */
@Entity
@Table(name = "category")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends BaseTenantEntity {

    /** The hues the UI can render; they map onto the design system's entity tones. */
    public static final Set<String> COLORS = Set.of("slate", "blue", "sky", "violet", "emerald", "amber", "rose");

    @Column(nullable = false, length = 24)
    private String name;

    @Column(nullable = false, length = 12)
    @Builder.Default
    private String color = "blue";
}
