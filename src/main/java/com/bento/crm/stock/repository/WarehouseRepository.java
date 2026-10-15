package com.bento.crm.stock.repository;

import com.bento.crm.stock.model.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    @Query("SELECT w FROM Warehouse w WHERE w.organizationId = :orgId AND w.deletedAt IS NULL ORDER BY w.isDefault DESC, lower(w.name)")
    List<Warehouse> findAllForOrganization(@Param("orgId") UUID orgId);

    @Query("SELECT w FROM Warehouse w WHERE w.organizationId = :orgId AND w.id = :id AND w.deletedAt IS NULL")
    Optional<Warehouse> findForOrganization(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT w FROM Warehouse w WHERE w.organizationId = :orgId AND w.isDefault = true AND w.deletedAt IS NULL")
    Optional<Warehouse> findDefault(@Param("orgId") UUID orgId);
}
