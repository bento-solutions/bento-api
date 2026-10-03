package com.bento.crm.task.dto;

import com.bento.crm.common.dto.RelatableRequest;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.model.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequest implements RelatableRequest {

    @NotBlank
    private String title;

    private String description;

    /**
     * Optional category. Ignored for a task on a ticket, which always takes the ticket's category.
     */
    private UUID categoryId;

    private UUID assignedTeamId;

    private UUID assignedToUserId;

    @NotNull
    private UUID assignedByUserId;

    @NotNull
    private Task.TaskStatus status;

    private Task.Priority priority;

    private LocalDate dueDate;

    /** Optional — a task does not have to be attached to anything. Editable after creation. */
    private RelatedEntityType relatedEntityType;

    private UUID relatedEntityId;
}
