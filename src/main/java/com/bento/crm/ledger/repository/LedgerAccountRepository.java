package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    @Query("SELECT a FROM LedgerAccount a WHERE a.organizationId = :orgId AND a.id = :id AND a.deletedAt IS NULL")
    Optional<LedgerAccount> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT a FROM LedgerAccount a WHERE a.organizationId = :orgId AND a.code = :code AND a.deletedAt IS NULL")
    Optional<LedgerAccount> findByOrganizationIdAndCode(@Param("orgId") UUID orgId, @Param("code") String code);

    @Query("SELECT a FROM LedgerAccount a WHERE a.organizationId = :orgId AND a.id IN :ids AND a.deletedAt IS NULL")
    List<LedgerAccount> findByOrganizationIdAndIdIn(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids);

    @Query("SELECT a FROM LedgerAccount a WHERE a.organizationId = :orgId AND a.deletedAt IS NULL"
            + " AND (:includeInactive = TRUE OR a.active = TRUE) ORDER BY a.code")
    List<LedgerAccount> findAll(@Param("orgId") UUID orgId, @Param("includeInactive") boolean includeInactive);
}
