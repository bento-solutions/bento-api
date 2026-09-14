package com.bento.crm.automation.repository;

import com.bento.crm.automation.model.AutomationRule;
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
public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID> {

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.organizationId = :organizationId AND ar.deletedAt IS NULL")
    Page<AutomationRule> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.organizationId = :organizationId AND ar.id = :id AND ar.deletedAt IS NULL")
    Optional<AutomationRule> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.organizationId = :orgId AND ar.trigger = :trigger AND ar.isActive = true AND ar.deletedAt IS NULL ORDER BY ar.priority ASC NULLS LAST")
    List<AutomationRule> findActiveRulesByTrigger(@Param("orgId") UUID orgId, @Param("trigger") AutomationRule.Trigger trigger);

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.organizationId = :organizationId AND ar.id = :id")
    Optional<AutomationRule> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.organizationId = :organizationId AND ar.deletedAt IS NOT NULL ORDER BY ar.deletedAt DESC")
    Page<AutomationRule> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT ar FROM AutomationRule ar WHERE ar.deletedAt IS NOT NULL AND ar.deletedAt < :cutoff")
    List<AutomationRule> findPurgeable(@Param("cutoff") Instant cutoff);
}
