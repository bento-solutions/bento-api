package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("SELECT e FROM LedgerEntry e WHERE e.organizationId = :orgId AND e.id = :id AND e.deletedAt IS NULL")
    Optional<LedgerEntry> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT e FROM LedgerEntry e WHERE e.organizationId = :orgId AND e.id IN :ids AND e.deletedAt IS NULL")
    List<LedgerEntry> findByOrganizationIdAndIdIn(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids);
}
