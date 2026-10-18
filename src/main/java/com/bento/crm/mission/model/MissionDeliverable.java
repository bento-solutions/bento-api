package com.bento.crm.mission.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "mission_deliverable")
@Getter
@Setter
public class MissionDeliverable extends BaseTenantEntity {

    @Column(name = "mission_id", nullable = false, columnDefinition = "uuid")
    private UUID missionId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.TODO;

    @Column(name = "file_id", columnDefinition = "uuid")
    private UUID fileId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "visible_to_client", nullable = false)
    private boolean visibleToClient = true;

    public enum Status { TODO, IN_PROGRESS, DELIVERED }
}
