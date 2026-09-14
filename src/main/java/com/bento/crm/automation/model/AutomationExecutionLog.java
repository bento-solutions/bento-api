package com.bento.crm.automation.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "automation_execution_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecutionLog extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID ruleId;

    private Integer ruleVersion;

    private String trigger;

    private String entityType;

    @Column(columnDefinition = "uuid")
    private UUID entityId;

    private Boolean dryRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditionsTrace;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actionsExecuted;

    private String status;
}
