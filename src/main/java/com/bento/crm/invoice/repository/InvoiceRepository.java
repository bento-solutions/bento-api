package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.deletedAt IS NULL")
    Page<Invoice> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.id = :id AND i.deletedAt IS NULL")
    Optional<Invoice> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.status = :status AND i.deletedAt IS NULL")
    Page<Invoice> findByOrganizationIdAndStatus(@Param("orgId") UUID orgId, @Param("status") Invoice.Status status, Pageable pageable);

    /**
     * Every invoice for one partner, unpaged: the partner ledger has to total the whole
     * relationship, so a page of it would give a wrong balance.
     */
    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.partnerId = :partnerId AND i.deletedAt IS NULL ORDER BY i.invoiceDate, i.createdAt")
    List<Invoice> findByOrganizationIdAndPartnerId(@Param("orgId") UUID orgId, @Param("partnerId") UUID partnerId);

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.id = :id")
    Optional<Invoice> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.deletedAt IS NOT NULL ORDER BY i.deletedAt DESC")
    Page<Invoice> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.deletedAt IS NOT NULL AND i.deletedAt < :cutoff")
    List<Invoice> findPurgeable(@Param("cutoff") Instant cutoff);

    /**
     * Flips sent invoices whose due date has passed to OVERDUE. Partially paid invoices keep
     * their status, so the money already received stays visible.
     */
    @Modifying
    @Query("""
            UPDATE Invoice i SET i.status = com.bento.crm.invoice.model.Invoice.Status.OVERDUE,
                   i.updatedAt = :now, i.version = i.version + 1
            WHERE i.organizationId = :orgId
              AND i.status = com.bento.crm.invoice.model.Invoice.Status.SENT
              AND i.dueDate < :today AND i.deletedAt IS NULL
            """)
    int markOverdue(@Param("orgId") UUID orgId, @Param("today") LocalDate today, @Param("now") Instant now);

    /** Unpaid customer invoices with a due date: the ones automatic reminders may chase. */
    @Query("""
            SELECT i FROM Invoice i
            WHERE i.organizationId = :orgId
              AND i.type = com.bento.crm.invoice.model.Invoice.InvoiceType.CUSTOMER
              AND i.status IN (com.bento.crm.invoice.model.Invoice.Status.SENT,
                               com.bento.crm.invoice.model.Invoice.Status.PARTIALLY_PAID,
                               com.bento.crm.invoice.model.Invoice.Status.OVERDUE)
              AND i.dueDate IS NOT NULL AND i.deletedAt IS NULL
            """)
    List<Invoice> findReminderCandidates(@Param("orgId") UUID orgId);

    @Query("SELECT i FROM Invoice i WHERE i.organizationId = :orgId AND i.recurringInvoiceId = :recurringId AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<Invoice> findByOrganizationIdAndRecurringInvoiceId(@Param("orgId") UUID orgId, @Param("recurringId") UUID recurringId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.organizationId = :orgId AND i.purchaseOrderId = :poId
              AND i.type = com.bento.crm.invoice.model.Invoice.InvoiceType.VENDOR AND i.deletedAt IS NULL
            """)
    List<Invoice> findBillsForPurchaseOrder(@Param("orgId") UUID orgId, @Param("poId") UUID poId);

    /** Unpaid invoices of one type: what the aging report and the bill alerts look at. */
    @Query("""
            SELECT i FROM Invoice i
            WHERE i.organizationId = :orgId AND i.type = :type AND i.deletedAt IS NULL
              AND i.status <> com.bento.crm.invoice.model.Invoice.Status.DRAFT
            """)
    List<Invoice> findIssuedByType(@Param("orgId") UUID orgId, @Param("type") Invoice.InvoiceType type);

    @Query("""
            SELECT DISTINCT i.organizationId FROM Invoice i
            WHERE i.type = com.bento.crm.invoice.model.Invoice.InvoiceType.VENDOR AND i.deletedAt IS NULL
              AND i.status IN (com.bento.crm.invoice.model.Invoice.Status.SENT,
                               com.bento.crm.invoice.model.Invoice.Status.PARTIALLY_PAID,
                               com.bento.crm.invoice.model.Invoice.Status.OVERDUE)
              AND i.dueDate IS NOT NULL AND i.dueDate <= :horizon
            """)
    List<UUID> findOrganizationsWithBillsDueBy(@Param("horizon") LocalDate horizon);
}
