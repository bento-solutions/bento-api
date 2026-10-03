package com.bento.crm.task.dto;

import com.bento.crm.common.model.EntityLink;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.model.Task;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskResponse {

    private UUID id;
    private UUID organizationId;
    private String title;
    private String description;
    private UUID categoryId;
    private UUID assignedTeamId;
    private UUID assignedToUserId;
    private UUID assignedByUserId;
    private Task.TaskStatus status;
    private Task.Priority priority;
    private LocalDate dueDate;
    private RelatedEntityType relatedEntityType;
    private UUID relatedEntityId;
    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TaskResponse fromEntity(Task task) {
        EntityLink link = task.getRelatedEntity() != null ? task.getRelatedEntity() : EntityLink.empty();
        return TaskResponse.builder()
                .id(task.getId())
                .organizationId(task.getOrganizationId())
                .title(task.getTitle())
                .description(task.getDescription())
                .categoryId(task.getCategoryId())
                .assignedTeamId(task.getAssignedTeamId())
                .assignedToUserId(task.getAssignedToUserId())
                .assignedByUserId(task.getAssignedByUserId())
                .status(task.getStatus())
                .priority(task.getPriority())
                .dueDate(task.getDueDate())
                .relatedEntityType(link.getRelatedEntityType())
                .relatedEntityId(link.getRelatedEntityId())
                .createdBy(task.getCreatedBy())
                .updatedBy(task.getUpdatedBy())
                .createdAt(task.getCreatedAt() != null ?
                    LocalDateTime.ofInstant(task.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(task.getUpdatedAt() != null ?
                    LocalDateTime.ofInstant(task.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
