package com.bento.crm.ticket.dto;

import com.bento.crm.ticket.model.TicketComment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketCommentResponse {

    private UUID id;
    private UUID organizationId;
    private UUID ticketId;
    private UUID authorId;
    private String authorName;
    private String authorRole;
    private String content;
    private Boolean isInternal;
    private Instant createdAt;
    private Instant updatedAt;

    public static TicketCommentResponse fromEntity(TicketComment comment) {
        return TicketCommentResponse.builder()
                .id(comment.getId())
                .organizationId(comment.getOrganizationId())
                .ticketId(comment.getTicketId())
                .authorId(comment.getAuthorId())
                .authorName(comment.getAuthorName())
                .authorRole(comment.getAuthorRole())
                .content(comment.getContent())
                .isInternal(comment.getIsInternal())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }
}
