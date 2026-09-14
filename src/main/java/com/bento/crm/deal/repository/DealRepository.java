package com.bento.crm.deal.repository;

import com.bento.crm.deal.model.Deal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DealRepository extends JpaRepository<Deal, UUID>, JpaSpecificationExecutor<Deal> {

    @Query("SELECT d FROM Deal d WHERE d.organizationId = :organizationId AND d.deletedAt IS NULL")
    Page<Deal> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT d FROM Deal d WHERE d.organizationId = :organizationId AND d.id = :id AND d.deletedAt IS NULL")
    Optional<Deal> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT d FROM Deal d WHERE d.organizationId = :organizationId AND d.id = :id")
    Optional<Deal> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT d FROM Deal d WHERE d.organizationId = :organizationId AND d.deletedAt IS NOT NULL ORDER BY d.deletedAt DESC")
    Page<Deal> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT d FROM Deal d WHERE d.deletedAt IS NOT NULL AND d.deletedAt < :cutoff")
    List<Deal> findPurgeable(@Param("cutoff") Instant cutoff);
}
