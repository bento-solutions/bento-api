package com.bento.crm.task.model;

import com.bento.crm.common.model.BaseTenantEntity;
import com.bento.crm.common.model.EntityLink;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task extends BaseTenantEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /**
     * Optional category. A task raised for a ticket always carries the ticket's category (the
     * service enforces it); only tasks outside any ticket choose their own.
     */
    @Column(columnDefinition = "uuid")
    private UUID categoryId;

    @Column(columnDefinition = "uuid")
    private UUID assignedTeamId;

    @Column(columnDefinition = "uuid")
    private UUID assignedToUserId;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID assignedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    private LocalDate dueDate;

    /**
     * Optional record this task was raised for (deal, proposal, customer/prospect, ticket…).
     * Never {@code null} as an object — an unlinked task holds an empty link.
     */
    @Embedded
    @Builder.Default
    private EntityLink relatedEntity = EntityLink.empty();

    public enum TaskStatus {
        TODO, IN_PROGRESS, DONE, BLOCKED
    }

    public enum Priority {
        URGENT, MEDIUM, LOW
    }
}
