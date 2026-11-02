package com.bento.crm.identity.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "crm_group")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmGroup extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    // Eager because the controllers return this entity directly; listings load it in batches
    // (hibernate.default_batch_fetch_size), so a page of groups is not one query per group.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_member", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "user_id", columnDefinition = "uuid")
    @Builder.Default
    private Set<UUID> memberUserIds = new LinkedHashSet<>();
}
