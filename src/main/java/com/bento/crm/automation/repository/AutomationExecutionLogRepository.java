package com.bento.crm.automation.repository;

import com.bento.crm.automation.model.AutomationExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AutomationExecutionLogRepository extends JpaRepository<AutomationExecutionLog, UUID> {

    @Query("SELECT l FROM AutomationExecutionLog l WHERE l.organizationId = :orgId ORDER BY l.createdAt DESC")
    Page<AutomationExecutionLog> findByOrganizationId(@Param("orgId") UUID orgId, Pageable pageable);
}
