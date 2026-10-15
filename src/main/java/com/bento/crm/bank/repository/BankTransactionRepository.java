package com.bento.crm.bank.repository;

import com.bento.crm.bank.model.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    @Query("SELECT x FROM BankTransaction x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<BankTransaction> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM BankTransaction x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<BankTransaction> all(@Param("orgId") UUID orgId);

    @Query("SELECT count(x) FROM BankTransaction x WHERE x.organizationId = :orgId AND x.paymentId = :paymentId AND x.deletedAt IS NULL")
    long countByPayment(@Param("orgId") UUID orgId, @Param("paymentId") UUID paymentId);

    @Query("SELECT count(x) FROM BankTransaction x WHERE x.organizationId = :orgId AND x.expenseId = :expenseId AND x.deletedAt IS NULL")
    long countByExpense(@Param("orgId") UUID orgId, @Param("expenseId") UUID expenseId);
}
