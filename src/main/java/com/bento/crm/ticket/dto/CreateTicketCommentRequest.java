package com.bento.crm.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTicketCommentRequest {

    private UUID authorId;

    private String authorName;

    private String authorRole;

    @NotBlank
    private String content;

    private Boolean isInternal;
}
