package com.bento.crm.bank.repository;

import com.bento.crm.bank.model.BankImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankImportRepository extends JpaRepository<BankImport, UUID> {

    @Query("SELECT x FROM BankImport x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<BankImport> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM BankImport x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<BankImport> all(@Param("orgId") UUID orgId);
}
