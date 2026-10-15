package com.bento.crm.stock.repository;

import com.bento.crm.stock.model.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    @Query("""
            SELECT m FROM StockMovement m
            WHERE m.organizationId = :orgId AND m.deletedAt IS NULL
              AND (:productId IS NULL OR m.productId = :productId)
              AND (:warehouseId IS NULL OR m.warehouseId = :warehouseId)
            ORDER BY m.createdAt DESC
            """)
    Page<StockMovement> search(@Param("orgId") UUID orgId, @Param("productId") UUID productId,
                               @Param("warehouseId") UUID warehouseId, Pageable pageable);

    @Query("""
            SELECT COUNT(m) > 0 FROM StockMovement m
            WHERE m.organizationId = :orgId AND m.referenceType = :type AND m.referenceId = :id AND m.deletedAt IS NULL
            """)
    boolean existsForReference(@Param("orgId") UUID orgId, @Param("type") String type, @Param("id") UUID id);
}
