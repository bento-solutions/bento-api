package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.InvoiceReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvoiceReminderLogRepository extends JpaRepository<InvoiceReminderLog, UUID> {

    boolean existsByInvoiceIdAndOffsetDays(UUID invoiceId, Integer offsetDays);

    @Query("SELECT l FROM InvoiceReminderLog l WHERE l.organizationId = :orgId AND l.invoiceId = :invoiceId ORDER BY l.createdAt DESC")
    List<InvoiceReminderLog> findByOrganizationIdAndInvoiceId(@Param("orgId") UUID orgId, @Param("invoiceId") UUID invoiceId);
}
