package com.bento.crm.businesstype.repository;

import com.bento.crm.businesstype.model.BusinessType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessTypeRepository extends JpaRepository<BusinessType, UUID> {

    @Query("SELECT b FROM BusinessType b WHERE b.organizationId = :orgId AND b.deletedAt IS NULL ORDER BY b.name ASC")
    Page<BusinessType> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT b FROM BusinessType b WHERE b.organizationId = :orgId AND b.deletedAt IS NULL ORDER BY b.name ASC")
    List<BusinessType> findAllActiveByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT b FROM BusinessType b WHERE b.organizationId = :orgId AND b.id = :id AND b.deletedAt IS NULL")
    Optional<BusinessType> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT b FROM BusinessType b WHERE b.organizationId = :orgId AND b.id = :id")
    Optional<BusinessType> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT b FROM BusinessType b WHERE b.organizationId = :orgId AND b.deletedAt IS NOT NULL ORDER BY b.deletedAt DESC")
    Page<BusinessType> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);
}
