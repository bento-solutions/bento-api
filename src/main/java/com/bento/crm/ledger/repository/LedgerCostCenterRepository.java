package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerCostCenter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerCostCenterRepository extends JpaRepository<LedgerCostCenter, UUID> {

    @Query("SELECT c FROM LedgerCostCenter c WHERE c.organizationId = :orgId AND c.id = :id AND c.deletedAt IS NULL")
    Optional<LedgerCostCenter> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT c FROM LedgerCostCenter c WHERE c.organizationId = :orgId AND c.id IN :ids AND c.deletedAt IS NULL")
    List<LedgerCostCenter> findByOrganizationIdAndIdIn(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids);

    @Query("SELECT COUNT(c) > 0 FROM LedgerCostCenter c WHERE c.organizationId = :orgId AND LOWER(c.code) = LOWER(:code)"
            + " AND c.deletedAt IS NULL")
    boolean existsCode(@Param("orgId") UUID orgId, @Param("code") String code);

    @Query("SELECT c FROM LedgerCostCenter c WHERE c.organizationId = :orgId AND c.deletedAt IS NULL"
            + " AND (:includeInactive = TRUE OR c.active = TRUE) ORDER BY c.code")
    List<LedgerCostCenter> findAll(@Param("orgId") UUID orgId, @Param("includeInactive") boolean includeInactive);
}
