package com.bento.crm.automation.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "automation_rule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRule extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private Boolean isActive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Trigger trigger;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditionGroups;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actions;

    private Integer priority;

    private Boolean stopOnMatch;

    @Column(name = "rule_version")
    private Integer ruleVersion;

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(Integer ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public enum Trigger {
        PARTNER_CREATED, PARTNER_UPDATED, DEAL_CREATED, DEAL_UPDATED,
        TICKET_CREATED, TICKET_UPDATED
    }
}
