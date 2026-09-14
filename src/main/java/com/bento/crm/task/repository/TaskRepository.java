package com.bento.crm.task.repository;

import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.dto.TaskProgress;
import com.bento.crm.task.model.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    @Query("SELECT t FROM Task t WHERE t.organizationId = :organizationId AND t.deletedAt IS NULL")
    Page<Task> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT t FROM Task t WHERE t.organizationId = :organizationId AND t.id = :id AND t.deletedAt IS NULL")
    Optional<Task> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    /**
     * Task totals per linked record, for the "2/5 tasks" progress shown on ticket rows and cards.
     * One grouped query for a whole page of records; records with no tasks produce no row.
     */
    @Query("""
            SELECT new com.bento.crm.task.dto.TaskProgress(
                t.relatedEntity.relatedEntityId,
                COUNT(t),
                SUM(CASE WHEN t.status = :doneStatus THEN 1L ELSE 0L END))
            FROM Task t
            WHERE t.organizationId = :organizationId
              AND t.deletedAt IS NULL
              AND t.relatedEntity.relatedEntityType = :type
              AND t.relatedEntity.relatedEntityId IN :ids
            GROUP BY t.relatedEntity.relatedEntityId
            """)
    List<TaskProgress> progressByRelatedEntity(@Param("organizationId") UUID organizationId,
                                               @Param("type") RelatedEntityType type,
                                               @Param("ids") Collection<UUID> ids,
                                               @Param("doneStatus") Task.TaskStatus doneStatus);

    @Query("SELECT t FROM Task t WHERE t.organizationId = :organizationId AND t.id = :id")
    Optional<Task> findByOrganizationIdAndIdIncludingDeleted(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT t FROM Task t WHERE t.organizationId = :organizationId AND t.deletedAt IS NOT NULL ORDER BY t.deletedAt DESC")
    Page<Task> findDeletedByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT t FROM Task t WHERE t.deletedAt IS NOT NULL AND t.deletedAt < :cutoff")
    List<Task> findPurgeable(@Param("cutoff") Instant cutoff);
}
