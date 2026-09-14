package com.bento.crm.payment.repository;

import com.bento.crm.payment.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.deletedAt IS NULL ORDER BY p.paymentDate DESC")
    Page<Payment> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.id = :id AND p.deletedAt IS NULL")
    Optional<Payment> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.partnerId = :partnerId AND p.deletedAt IS NULL ORDER BY p.paymentDate")
    List<Payment> findByOrganizationIdAndPartnerId(@Param("orgId") UUID orgId, @Param("partnerId") UUID partnerId);

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.invoiceId = :invoiceId AND p.deletedAt IS NULL ORDER BY p.paymentDate")
    List<Payment> findByOrganizationIdAndInvoiceId(@Param("orgId") UUID orgId, @Param("invoiceId") UUID invoiceId);

    /**
     * Settled-to-date per invoice for one partner, so the ledger can compute remaining balances
     * without issuing a query per invoice. On-account payments (null invoiceId) are excluded —
     * they are not allocated to any invoice and are reported as standalone ledger movements.
     */
    @Query("""
            SELECT p.invoiceId, SUM(p.amount) FROM Payment p
            WHERE p.organizationId = :orgId AND p.partnerId = :partnerId AND p.invoiceId IS NOT NULL AND p.deletedAt IS NULL
            GROUP BY p.invoiceId
            """)
    List<Object[]> sumPaidByInvoiceForPartner(@Param("orgId") UUID orgId, @Param("partnerId") UUID partnerId);

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.id = :id")
    Optional<Payment> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT p FROM Payment p WHERE p.organizationId = :orgId AND p.deletedAt IS NOT NULL ORDER BY p.deletedAt DESC")
    Page<Payment> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.deletedAt IS NOT NULL AND p.deletedAt < :cutoff")
    List<Payment> findPurgeable(@Param("cutoff") Instant cutoff);
}
