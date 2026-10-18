package com.bento.crm.mission.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A piece of work done for a client (annual accounts, a company formation, an audit). */
@Entity
@Table(name = "mission")
@Getter
@Setter
public class Mission extends BaseTenantEntity {

    @Column(name = "partner_id", nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PLANNED;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "fee_amount", precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "assigned_user_id", columnDefinition = "uuid")
    private UUID assignedUserId;

    public enum Status { PLANNED, IN_PROGRESS, DONE, CANCELLED }
}
