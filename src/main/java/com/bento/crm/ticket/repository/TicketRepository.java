package com.bento.crm.ticket.repository;

import com.bento.crm.ticket.model.Ticket;
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
public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    @Query("SELECT t FROM Ticket t WHERE t.organizationId = :orgId AND t.deletedAt IS NULL")
    Page<Ticket> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT t FROM Ticket t WHERE t.organizationId = :orgId AND t.id = :id AND t.deletedAt IS NULL")
    Optional<Ticket> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT t FROM Ticket t WHERE t.organizationId = :orgId AND t.id = :id")
    Optional<Ticket> findByOrganizationIdAndIdIncludingDeleted(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT t FROM Ticket t WHERE t.organizationId = :orgId AND t.deletedAt IS NOT NULL ORDER BY t.deletedAt DESC")
    Page<Ticket> findDeletedByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT t FROM Ticket t WHERE t.deletedAt IS NOT NULL AND t.deletedAt < :cutoff")
    List<Ticket> findPurgeable(@Param("cutoff") Instant cutoff);
}
