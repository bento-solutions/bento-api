package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
}
