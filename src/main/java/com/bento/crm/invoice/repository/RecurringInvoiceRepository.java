package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.RecurringInvoice;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecurringInvoiceRepository extends JpaRepository<RecurringInvoice, UUID> {

    @Query("SELECT r FROM RecurringInvoice r WHERE r.organizationId = :orgId AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    Page<RecurringInvoice> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT r FROM RecurringInvoice r WHERE r.organizationId = :orgId AND r.id = :id AND r.deletedAt IS NULL")
    Optional<RecurringInvoice> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    /** Ids of templates with a run due on or before {@code today}, across every organization. */
    @Query("""
            SELECT r.id FROM RecurringInvoice r
            WHERE r.status = com.bento.crm.invoice.model.RecurringInvoice.Status.ACTIVE
              AND r.nextRunDate <= :today AND r.deletedAt IS NULL
            ORDER BY r.nextRunDate ASC
            """)
    List<UUID> findDueIds(@Param("today") LocalDate today, Pageable pageable);

    /**
     * Locks one template for generation. SKIP LOCKED makes a template another instance is
     * already generating come back empty instead of blocking, so no period is invoiced twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT r FROM RecurringInvoice r WHERE r.id = :id AND r.deletedAt IS NULL")
    Optional<RecurringInvoice> lockById(@Param("id") UUID id);

    @Query("SELECT r FROM RecurringInvoice r WHERE r.deletedAt IS NOT NULL AND r.deletedAt < :cutoff")
    List<RecurringInvoice> findPurgeable(@Param("cutoff") Instant cutoff);
}
