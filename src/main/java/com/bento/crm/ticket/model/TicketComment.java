package com.bento.crm.ticket.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "ticket_comment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketComment extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID ticketId;

    @Column(columnDefinition = "uuid")
    private UUID authorId;

    @Column(nullable = false, length = 128)
    private String authorName;

    @Column(length = 64)
    private String authorRole;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isInternal = false;
}
