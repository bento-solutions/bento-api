package com.bento.crm.ledger.repository;

import com.bento.crm.ledger.model.LedgerJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerJournalRepository extends JpaRepository<LedgerJournal, UUID> {

    @Query("SELECT j FROM LedgerJournal j WHERE j.organizationId = :orgId AND j.id = :id AND j.deletedAt IS NULL")
    Optional<LedgerJournal> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT j FROM LedgerJournal j WHERE j.organizationId = :orgId AND j.code = :code AND j.deletedAt IS NULL")
    Optional<LedgerJournal> findByOrganizationIdAndCode(@Param("orgId") UUID orgId, @Param("code") String code);

    @Query("SELECT j FROM LedgerJournal j WHERE j.organizationId = :orgId AND j.deletedAt IS NULL ORDER BY j.code")
    List<LedgerJournal> findAll(@Param("orgId") UUID orgId);
}
