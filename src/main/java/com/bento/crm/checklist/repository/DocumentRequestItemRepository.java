package com.bento.crm.checklist.repository;

import com.bento.crm.checklist.model.DocumentRequestItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRequestItemRepository extends JpaRepository<DocumentRequestItem, UUID> {

    @Query("SELECT x FROM DocumentRequestItem x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<DocumentRequestItem> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM DocumentRequestItem x WHERE x.requestId = :requestId AND x.deletedAt IS NULL ORDER BY x.sortOrder, x.createdAt")
    List<DocumentRequestItem> forRequest(@Param("requestId") UUID requestId);
}
