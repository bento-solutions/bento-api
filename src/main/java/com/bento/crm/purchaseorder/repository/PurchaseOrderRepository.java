package com.bento.crm.purchaseorder.repository;

import com.bento.crm.purchaseorder.model.PurchaseOrder;
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
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("SELECT po FROM PurchaseOrder po WHERE po.organizationId = :organizationId AND po.deletedAt IS NULL")
    Page<PurchaseOrder> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.organizationId = :organizationId AND po.id = :id AND po.deletedAt IS NULL")
    Optional<PurchaseOrder> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.organizationId = :organizationId AND po.id = :id")
    Optional<PurchaseOrder> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.organizationId = :organizationId AND po.deletedAt IS NOT NULL ORDER BY po.deletedAt DESC")
    Page<PurchaseOrder> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.deletedAt IS NOT NULL AND po.deletedAt < :cutoff")
    List<PurchaseOrder> findPurgeable(@Param("cutoff") Instant cutoff);
}
