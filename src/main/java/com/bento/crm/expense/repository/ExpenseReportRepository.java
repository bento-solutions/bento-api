package com.bento.crm.expense.repository;

import com.bento.crm.expense.model.ExpenseReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {

    @Query("SELECT r FROM ExpenseReport r WHERE r.organizationId = :orgId AND r.id = :id AND r.deletedAt IS NULL")
    Optional<ExpenseReport> findForOrganization(@Param("orgId") UUID orgId, @Param("id") UUID id);
}
