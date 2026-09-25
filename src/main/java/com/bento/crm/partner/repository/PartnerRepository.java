package com.bento.crm.partner.repository;

import com.bento.crm.partner.model.Partner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, UUID>, JpaSpecificationExecutor<Partner> {

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.type = :type AND p.deletedAt IS NULL")
    Page<Partner> findByOrganizationIdAndType(@Param("orgId") UUID orgId, @Param("type") Partner.PartnerType type, Pageable pageable);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.stage = :stage AND p.deletedAt IS NULL")
    Page<Partner> findByOrganizationIdAndStage(@Param("orgId") UUID orgId, @Param("stage") Partner.PartnerStage stage, Pageable pageable);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.id = :id AND p.deletedAt IS NULL")
    Optional<Partner> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.externalId = :externalId AND p.deletedAt IS NULL")
    Optional<Partner> findByOrganizationIdAndExternalId(@Param("orgId") UUID orgId,
                                                        @Param("externalId") String externalId);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.deletedAt IS NULL ORDER BY p.createdAt DESC")
    Page<Partner> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    /**
     * Matches a contact by phone number for inbound WhatsApp routing.
     *
     * <p>Partner phone numbers are free text, so the same person may be stored as
     * {@code 0661234567} while Meta reports {@code +212661234567}. Comparing the
     * last nine digits of the stripped number makes both forms collide, which no
     * equality match on the raw column could do.
     */
    @Query(value = """
            SELECT * FROM partner p
            WHERE p.organization_id = :orgId
              AND p.deleted_at IS NULL
              AND length(regexp_replace(coalesce(p.phone, ''), '[^0-9]', '', 'g')) >= 9
              AND right(regexp_replace(coalesce(p.phone, ''), '[^0-9]', '', 'g'), 9) = right(:digits, 9)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Partner> findByOrganizationIdAndPhoneDigits(@Param("orgId") UUID orgId,
                                                         @Param("digits") String digits);

    /**
     * Resolves a partner regardless of its deleted state. Only the restore path uses this —
     * every read path must go through the filtered lookups above.
     */
    /**
     * External-id lookup that also sees soft-deleted rows. The unique index on
     * (organization_id, external_id) covers deleted rows too, so a create must find and reuse a
     * deleted match rather than collide with it.
     */
    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.externalId = :externalId")
    Optional<Partner> findByOrganizationIdAndExternalIdIncludingDeleted(@Param("orgId") UUID orgId,
                                                                       @Param("externalId") String externalId);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.id = :id")
    Optional<Partner> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Partner p WHERE p.organizationId = :orgId AND p.deletedAt IS NOT NULL ORDER BY p.deletedAt DESC")
    Page<Partner> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    /** Org-wide on purpose: the purge job runs outside any tenant request context. */
    @Query("SELECT p FROM Partner p WHERE p.deletedAt IS NOT NULL AND p.deletedAt < :cutoff")
    List<Partner> findPurgeable(@Param("cutoff") java.time.Instant cutoff);
}
