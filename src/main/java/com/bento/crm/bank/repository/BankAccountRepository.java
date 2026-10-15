package com.bento.crm.bank.repository;

import com.bento.crm.bank.model.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    @Query("SELECT x FROM BankAccount x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<BankAccount> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM BankAccount x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<BankAccount> all(@Param("orgId") UUID orgId);
}
