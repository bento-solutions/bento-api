package com.bento.crm.automation.event;

import com.bento.crm.automation.model.AutomationRule;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class EntityChangedEvent {
    private final UUID organizationId;
    private final AutomationRule.Trigger trigger;
    private final String entityType;
    private final UUID entityId;
    private final Map<String, Object> payload;
}
