package com.bento.crm.campaign.repository;

import com.bento.crm.campaign.model.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    @Query("SELECT c FROM Campaign c WHERE c.organizationId = :organizationId AND c.deletedAt IS NULL")
    Page<Campaign> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT c FROM Campaign c WHERE c.organizationId = :organizationId AND c.id = :id AND c.deletedAt IS NULL")
    Optional<Campaign> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT c FROM Campaign c WHERE c.organizationId = :organizationId AND c.id = :id")
    Optional<Campaign> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT c FROM Campaign c WHERE c.organizationId = :organizationId AND c.deletedAt IS NOT NULL ORDER BY c.deletedAt DESC")
    Page<Campaign> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT c FROM Campaign c WHERE c.deletedAt IS NOT NULL AND c.deletedAt < :cutoff")
    List<Campaign> findPurgeable(@Param("cutoff") Instant cutoff);
}
