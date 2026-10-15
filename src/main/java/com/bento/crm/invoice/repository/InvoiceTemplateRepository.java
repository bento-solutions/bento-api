package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.InvoiceTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceTemplateRepository extends JpaRepository<InvoiceTemplate, UUID> {

    @Query("SELECT t FROM InvoiceTemplate t WHERE t.organizationId = :orgId AND t.deletedAt IS NULL")
    Optional<InvoiceTemplate> findByOrganizationId(@Param("orgId") UUID orgId);
}
