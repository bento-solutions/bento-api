package com.bento.crm.ticket.model;

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
@Table(name = "ticket")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket extends BaseTenantEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** Free-text category ("Software issue", "Billing issue", …) chosen in the ticket form. */
    @Column(length = 50)
    private String type;

    /**
     * Denormalised mirror of {@link #relatedEntity} when it points at a partner, kept so
     * partner-scoped reads and the {@code idx_ticket_partner} index still work. Maintained by
     * the service layer only — never set it independently of the link.
     */
    @Column(columnDefinition = "uuid")
    private UUID partnerId;

    /**
     * Optional record this ticket concerns (deal, proposal, customer/prospect…).
     * Never {@code null} as an object — an unlinked ticket holds an empty link.
     */
    @Embedded
    @Builder.Default
    private EntityLink relatedEntity = EntityLink.empty();

    /** Optional category (which product/project this concerns); its tasks inherit it. */
    @Column(columnDefinition = "uuid")
    private UUID categoryId;

    @Column(columnDefinition = "uuid")
    private UUID assignedToUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    private LocalDate deadline;

    @Column(columnDefinition = "text")
    private String resolution;

    public enum Status {
        OPEN, IN_PROGRESS, RESOLVED, CLOSED
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, URGENT
    }
}
