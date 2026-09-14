package com.bento.crm.ticket.repository;

import com.bento.crm.ticket.model.TicketComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketCommentRepository extends JpaRepository<TicketComment, UUID> {

    @Query("SELECT c FROM TicketComment c WHERE c.organizationId = :orgId AND c.ticketId = :ticketId AND c.deletedAt IS NULL ORDER BY c.createdAt ASC")
    List<TicketComment> findByOrganizationIdAndTicketId(@Param("orgId") UUID orgId, @Param("ticketId") UUID ticketId);
}
