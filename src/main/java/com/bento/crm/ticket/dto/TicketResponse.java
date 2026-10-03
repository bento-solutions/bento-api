package com.bento.crm.ticket.dto;

import com.bento.crm.common.model.EntityLink;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.dto.TaskProgress;
import com.bento.crm.ticket.model.Ticket;
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
public class TicketResponse {

    private UUID id;
    private UUID organizationId;
    private String title;
    private String description;
    private String type;
    private UUID categoryId;
    private UUID partnerId;
    private RelatedEntityType relatedEntityType;
    private UUID relatedEntityId;
    private UUID assignedToUserId;
    private Ticket.Status status;
    private Ticket.Priority priority;
    private LocalDate deadline;
    private String resolution;
    /** Tasks raised for this ticket, and how many of them are done — "2/5" on the ticket row. */
    private long taskCount;
    private long taskDoneCount;
    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TicketResponse fromEntity(Ticket ticket) {
        return fromEntity(ticket, TaskProgress.none(ticket.getId()));
    }

    public static TicketResponse fromEntity(Ticket ticket, TaskProgress progress) {
        EntityLink link = ticket.getRelatedEntity() != null ? ticket.getRelatedEntity() : EntityLink.empty();
        return TicketResponse.builder()
                .id(ticket.getId())
                .organizationId(ticket.getOrganizationId())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .type(ticket.getType())
                .categoryId(ticket.getCategoryId())
                .partnerId(ticket.getPartnerId())
                .relatedEntityType(link.getRelatedEntityType())
                .relatedEntityId(link.getRelatedEntityId())
                .assignedToUserId(ticket.getAssignedToUserId())
                .status(ticket.getStatus())
                .priority(ticket.getPriority())
                .deadline(ticket.getDeadline())
                .resolution(ticket.getResolution())
                .taskCount(progress.total())
                .taskDoneCount(progress.done())
                .createdBy(ticket.getCreatedBy())
                .updatedBy(ticket.getUpdatedBy())
                .createdAt(ticket.getCreatedAt() != null ?
                    LocalDateTime.ofInstant(ticket.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(ticket.getUpdatedAt() != null ?
                    LocalDateTime.ofInstant(ticket.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
