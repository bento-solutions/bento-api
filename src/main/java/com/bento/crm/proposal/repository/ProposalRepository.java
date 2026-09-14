package com.bento.crm.proposal.repository;

import com.bento.crm.proposal.model.Proposal;
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
public interface ProposalRepository extends JpaRepository<Proposal, UUID> {

    @Query("SELECT p FROM Proposal p WHERE p.organizationId = :organizationId AND p.deletedAt IS NULL")
    Page<Proposal> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT p FROM Proposal p WHERE p.organizationId = :organizationId AND p.id = :id AND p.deletedAt IS NULL")
    Optional<Proposal> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT p FROM Proposal p WHERE p.organizationId = :organizationId AND p.id = :id")
    Optional<Proposal> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT p FROM Proposal p WHERE p.organizationId = :organizationId AND p.deletedAt IS NOT NULL ORDER BY p.deletedAt DESC")
    Page<Proposal> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT p FROM Proposal p WHERE p.deletedAt IS NOT NULL AND p.deletedAt < :cutoff")
    List<Proposal> findPurgeable(@Param("cutoff") Instant cutoff);
}
