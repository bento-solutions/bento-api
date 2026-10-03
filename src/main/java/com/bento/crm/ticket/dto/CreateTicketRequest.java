package com.bento.crm.ticket.dto;

import com.bento.crm.common.dto.RelatableRequest;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.ticket.model.Ticket;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTicketRequest implements RelatableRequest {

    @NotBlank
    private String title;

    private String description;

    @Size(max = 50)
    private String type;

    /** Optional category; the ticket's tasks follow it. */
    private UUID categoryId;

    /**
     * Legacy shorthand for a partner link, kept so existing clients keep working: when no
     * explicit {@code relatedEntityType}/{@code relatedEntityId} is sent this is read as a
     * {@code PARTNER} link. Optional — a ticket no longer has to name a partner.
     */
    private UUID partnerId;

    /** Optional — the deal, proposal, customer or prospect the ticket concerns. Editable. */
    private RelatedEntityType relatedEntityType;

    private UUID relatedEntityId;

    private UUID assignedToUserId;

    @NotNull
    private Ticket.Status status;

    private Ticket.Priority priority;

    private LocalDate deadline;

    private String resolution;
}
