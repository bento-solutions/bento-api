package com.bento.crm.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
@EntityListeners({AuditingEntityListener.class, TenantEntityListener.class})
@FilterDef(name = "organizationFilter", parameters = @org.hibernate.annotations.ParamDef(name = "organizationId", type = java.util.UUID.class))
@Filter(name = "organizationFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
public abstract class BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID organizationId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    // Nullable, matching the migrations: records written before any user exists (the admin
    // created during organization signup) have no auditor to attribute them to.
    @CreatedBy
    @Column(updatable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @LastModifiedBy
    @Column(columnDefinition = "uuid")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    /**
     * Set when the record is removed. Soft-deleted entities are hidden from standard listings
     * but remain restorable for a 30-day grace period, after which EntityPurgeScheduler hard-deletes them.
     */
    private Instant deletedAt;
}
