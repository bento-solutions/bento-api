package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.InvoiceReminderPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceReminderPolicyRepository extends JpaRepository<InvoiceReminderPolicy, UUID> {

    @Query("SELECT p FROM InvoiceReminderPolicy p WHERE p.organizationId = :orgId AND p.deletedAt IS NULL")
    Optional<InvoiceReminderPolicy> findByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT p FROM InvoiceReminderPolicy p WHERE p.enabled = true AND p.deletedAt IS NULL")
    List<InvoiceReminderPolicy> findEnabled();
}
