package com.bento.crm.mission.repository;

import com.bento.crm.mission.model.MissionDeliverable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MissionDeliverableRepository extends JpaRepository<MissionDeliverable, UUID> {

    @Query("SELECT x FROM MissionDeliverable x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<MissionDeliverable> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM MissionDeliverable x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<MissionDeliverable> all(@Param("orgId") UUID orgId);
}
