package com.bento.crm.checklist.repository;

import com.bento.crm.checklist.model.DocumentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRequestRepository extends JpaRepository<DocumentRequest, UUID> {

    @Query("SELECT x FROM DocumentRequest x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<DocumentRequest> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    /** Open requests that may need a reminder, across organizations. */
    @Query("SELECT x FROM DocumentRequest x WHERE x.status = com.bento.crm.checklist.model.DocumentRequest.Status.OPEN"
            + " AND x.remindChannel <> com.bento.crm.checklist.model.DocumentRequest.Channel.NONE AND x.deletedAt IS NULL")
    List<DocumentRequest> openWithReminders();
}
