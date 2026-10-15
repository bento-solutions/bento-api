package com.bento.crm.inbound.repository;

import com.bento.crm.inbound.model.InboundDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface InboundDocumentRepository extends JpaRepository<InboundDocument, UUID> {

    @Query("SELECT d FROM InboundDocument d WHERE d.organizationId = :orgId AND d.id = :id AND d.deletedAt IS NULL")
    Optional<InboundDocument> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT d FROM InboundDocument d WHERE d.organizationId = :orgId AND d.deletedAt IS NULL"
            + " AND d.status IN :statuses AND d.kind IN :kinds ORDER BY d.receivedAt DESC")
    Page<InboundDocument> search(@Param("orgId") UUID orgId, @Param("statuses") Collection<InboundDocument.Status> statuses,
                                 @Param("kinds") Collection<InboundDocument.Kind> kinds, Pageable pageable);

    @Query("SELECT d.status, count(d) FROM InboundDocument d WHERE d.organizationId = :orgId AND d.deletedAt IS NULL GROUP BY d.status")
    java.util.List<Object[]> countByStatus(@Param("orgId") UUID orgId);
}
