package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerEntryLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryLineRepository extends JpaRepository<LedgerEntryLine, UUID> {

    @Query("SELECT l FROM LedgerEntryLine l WHERE l.organizationId = :orgId AND l.entryId IN :entryIds"
            + " AND l.deletedAt IS NULL ORDER BY l.entryId, l.lineNo")
    List<LedgerEntryLine> findByOrganizationIdAndEntryIdIn(@Param("orgId") UUID orgId,
                                                          @Param("entryIds") Collection<UUID> entryIds);

    @Query("SELECT COUNT(l) > 0 FROM LedgerEntryLine l WHERE l.organizationId = :orgId AND l.accountId = :accountId"
            + " AND l.deletedAt IS NULL")
    boolean existsByAccount(@Param("orgId") UUID orgId, @Param("accountId") UUID accountId);

    @Query("SELECT COUNT(l) > 0 FROM LedgerEntryLine l WHERE l.organizationId = :orgId AND l.costCenterId = :costCenterId"
            + " AND l.deletedAt IS NULL")
    boolean existsByCostCenter(@Param("orgId") UUID orgId, @Param("costCenterId") UUID costCenterId);
}
