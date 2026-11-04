package com.bento.crm.expense.repository;

import com.bento.crm.expense.model.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    @Query("SELECT e FROM Expense e WHERE e.organizationId = :orgId AND e.id = :id AND e.deletedAt IS NULL")
    Optional<Expense> findForOrganization(@Param("orgId") UUID orgId, @Param("id") UUID id);

    /** The lines of an expense report, in date order. */
    @Query("SELECT e FROM Expense e WHERE e.organizationId = :orgId AND e.expenseReportId = :reportId AND e.deletedAt IS NULL"
            + " ORDER BY e.expenseDate, e.createdAt")
    List<Expense> findReportLines(@Param("orgId") UUID orgId, @Param("reportId") UUID reportId);

    @Query("""
            SELECT e FROM Expense e
            WHERE e.organizationId = :orgId AND e.deletedAt IS NULL
              AND e.expenseDate >= :from AND e.expenseDate <= :to
              AND (:category IS NULL OR e.category = :category)
              AND (e.expenseReportId IS NULL OR NOT EXISTS (SELECT 1 FROM ExpenseReport r WHERE r.id = e.expenseReportId
                   AND (r.status = com.bento.crm.expense.model.ExpenseReport.Status.CANCELLED OR r.deletedAt IS NOT NULL)))
            ORDER BY e.expenseDate DESC, e.createdAt DESC
            """)
    Page<Expense> search(@Param("orgId") UUID orgId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                         @Param("category") String category, Pageable pageable);

    @Query("""
            SELECT e.category, SUM(e.amount), SUM(COALESCE(e.vatAmount, 0)), COUNT(e) FROM Expense e
            WHERE e.organizationId = :orgId AND e.deletedAt IS NULL AND e.expenseDate >= :from AND e.expenseDate <= :to
              AND (e.expenseReportId IS NULL OR NOT EXISTS (SELECT 1 FROM ExpenseReport r WHERE r.id = e.expenseReportId
                   AND (r.status = com.bento.crm.expense.model.ExpenseReport.Status.CANCELLED OR r.deletedAt IS NOT NULL)))
            GROUP BY e.category ORDER BY SUM(e.amount) DESC
            """)
    List<Object[]> totalsByCategory(@Param("orgId") UUID orgId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
