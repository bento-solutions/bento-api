package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    String LIVE = "SELECT e FROM LedgerEntry e WHERE e.organizationId = :orgId AND e.sourceType = :sourceType"
            + " AND e.sourceId = :sourceId AND e.reversedById IS NULL AND e.reversalOfId IS NULL AND e.deletedAt IS NULL";

    /** The document's live (not reversed) automatic entry. */
    @Query(LIVE)
    Optional<LedgerEntry> findLive(@Param("orgId") UUID orgId, @Param("sourceType") String sourceType,
                                   @Param("sourceId") UUID sourceId);

    /** Same, locked until the posting transaction ends, so two edits of one document queue up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(LIVE)
    Optional<LedgerEntry> lockLive(@Param("orgId") UUID orgId, @Param("sourceType") String sourceType,
                                   @Param("sourceId") UUID sourceId);
}
