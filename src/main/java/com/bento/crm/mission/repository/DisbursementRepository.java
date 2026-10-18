package com.bento.crm.mission.repository;

import com.bento.crm.mission.model.Disbursement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisbursementRepository extends JpaRepository<Disbursement, UUID> {

    @Query("SELECT x FROM Disbursement x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<Disbursement> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM Disbursement x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<Disbursement> all(@Param("orgId") UUID orgId);
}
