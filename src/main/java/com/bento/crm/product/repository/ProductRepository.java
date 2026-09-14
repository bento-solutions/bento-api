package com.bento.crm.product.repository;

import com.bento.crm.product.model.Product;
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
public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Query("SELECT p FROM Product p WHERE p.organizationId = :orgId AND p.deletedAt IS NULL ORDER BY p.name ASC")
    Page<Product> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.organizationId = :orgId AND p.id = :id AND p.deletedAt IS NULL")
    Optional<Product> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Product p WHERE p.organizationId = :orgId AND p.sku = :sku AND p.deletedAt IS NULL")
    Optional<Product> findByOrganizationIdAndSku(@Param("orgId") UUID orgId, @Param("sku") String sku);

    @Query("SELECT p FROM Product p WHERE p.organizationId = :orgId AND p.id = :id")
    Optional<Product> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Product p WHERE p.organizationId = :orgId AND p.deletedAt IS NOT NULL ORDER BY p.deletedAt DESC")
    Page<Product> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.deletedAt IS NOT NULL AND p.deletedAt < :cutoff")
    List<Product> findPurgeable(@Param("cutoff") Instant cutoff);
}
