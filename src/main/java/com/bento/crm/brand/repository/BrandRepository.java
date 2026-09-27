package com.bento.crm.brand.repository;

import com.bento.crm.brand.model.Brand;
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
public interface BrandRepository extends JpaRepository<Brand, UUID> {

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.deletedAt IS NULL ORDER BY b.name ASC")
    Page<Brand> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.deletedAt IS NULL ORDER BY b.name ASC")
    List<Brand> findAllActiveByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.id = :id AND b.deletedAt IS NULL")
    Optional<Brand> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.id = :id")
    Optional<Brand> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.isDefault = true AND b.deletedAt IS NULL")
    Optional<Brand> findDefaultByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND lower(b.name) = lower(:name) AND b.deletedAt IS NULL")
    Optional<Brand> findByOrganizationIdAndNameIgnoreCase(@Param("orgId") UUID orgId, @Param("name") String name);

    @Query("SELECT b FROM Brand b WHERE b.organizationId = :orgId AND b.deletedAt IS NOT NULL ORDER BY b.deletedAt DESC")
    Page<Brand> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);
}
