package com.bento.crm.ledger.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** An analytic axis an entry line can be tagged with, optionally tied to a mission. */
@Entity
@Table(name = "ledger_cost_center")
@Getter
@Setter
public class LedgerCostCenter extends BaseTenantEntity {

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "mission_id", columnDefinition = "uuid")
    private UUID missionId;
}
