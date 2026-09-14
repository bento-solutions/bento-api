package com.bento.crm.automation.service;

import com.bento.crm.automation.event.EntityChangedEvent;
import com.bento.crm.automation.model.AutomationExecutionLog;
import com.bento.crm.automation.model.AutomationRule;
import com.bento.crm.automation.repository.AutomationExecutionLogRepository;
import com.bento.crm.automation.repository.AutomationRuleRepository;
import com.bento.crm.common.model.EntityLink;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.dto.CreateTaskRequest;
import com.bento.crm.task.model.Task;
import com.bento.crm.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationEngineService {

    private final AutomationRuleRepository automationRuleRepository;
    private final AutomationExecutionLogRepository executionLogRepository;
    private final TaskService taskService;

    @EventListener
    @Async
    @Transactional
    public void handleEntityChanged(EntityChangedEvent event) {
        if (event == null || event.getOrganizationId() == null || event.getTrigger() == null) {
            return;
        }

        List<AutomationRule> rules = automationRuleRepository.findActiveRulesByTrigger(
                event.getOrganizationId(), event.getTrigger());

        if (rules.isEmpty()) {
            return;
        }

        log.info("[automation-engine] processing trigger {} for entity {} ({} rules found)",
                event.getTrigger(), event.getEntityId(), rules.size());

        for (AutomationRule rule : rules) {
            try {
                boolean matches = evaluateConditions(rule.getConditionGroups(), event.getPayload());
                if (!matches) {
                    continue;
                }

                executeActions(rule, event);

                recordLog(rule, event, "SUCCESS", Map.of("matched", true), rule.getActions());

                if (Boolean.TRUE.equals(rule.getStopOnMatch())) {
                    log.info("[automation-engine] rule {} matched with stopOnMatch=true, halting evaluation", rule.getId());
                    break;
                }
            } catch (Exception e) {
                log.error("[automation-engine] error executing rule {}: {}", rule.getId(), e.getMessage(), e);
                recordLog(rule, event, "FAILED", Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), null);
            }
        }
    }

    private boolean evaluateConditions(Map<String, Object> conditionGroups, Map<String, Object> payload) {
        if (conditionGroups == null || conditionGroups.isEmpty()) {
            return true; // No conditions = match all
        }

        Object conditionsObj = conditionGroups.get("conditions");
        if (!(conditionsObj instanceof List<?> conditions) || conditions.isEmpty()) {
            return true;
        }

        String groupOp = (String) conditionGroups.getOrDefault("operator", "AND");
        boolean isAnd = !"OR".equalsIgnoreCase(groupOp);

        for (Object condObj : conditions) {
            if (!(condObj instanceof Map<?, ?> condMap)) {
                continue;
            }
            String field = condMap.get("field") != null ? condMap.get("field").toString() : null;
            String operator = condMap.get("operator") != null ? condMap.get("operator").toString() : "equals";
            Object expectedValue = condMap.get("value");

            Object actualValue = payload != null && field != null ? payload.get(field) : null;
            boolean condMatches = checkMatch(actualValue, operator, expectedValue);

            if (isAnd && !condMatches) {
                return false;
            }
            if (!isAnd && condMatches) {
                return true;
            }
        }

        return isAnd;
    }

    private boolean checkMatch(Object actual, String operator, Object expected) {
        if (operator == null) operator = "equals";
        String actualStr = actual != null ? actual.toString().trim() : "";
        String expectedStr = expected != null ? expected.toString().trim() : "";

        return switch (operator.toLowerCase()) {
            case "equals", "eq" -> actualStr.equalsIgnoreCase(expectedStr);
            case "not_equals", "neq" -> !actualStr.equalsIgnoreCase(expectedStr);
            case "contains" -> actualStr.toLowerCase().contains(expectedStr.toLowerCase());
            case "starts_with" -> actualStr.toLowerCase().startsWith(expectedStr.toLowerCase());
            case "is_null" -> actual == null;
            case "is_not_null" -> actual != null;
            default -> actualStr.equalsIgnoreCase(expectedStr);
        };
    }

    private void executeActions(AutomationRule rule, EntityChangedEvent event) {
        Map<String, Object> actions = rule.getActions();
        if (actions == null || actions.isEmpty()) {
            return;
        }

        Object taskAction = actions.get("createTask");
        if (taskAction == null) taskAction = actions.get("create_task");

        if (taskAction instanceof Map<?, ?> taskMap) {
            CreateTaskRequest req = new CreateTaskRequest();
            String title = taskMap.get("title") != null ? taskMap.get("title").toString() : "Follow up: " + event.getEntityType();
            String description = taskMap.get("description") != null ? taskMap.get("description").toString() : "Automated task from rule: " + rule.getName();
            req.setTitle(title);
            req.setDescription(description);
            req.setStatus(Task.TaskStatus.TODO);
            req.setPriority(Task.Priority.MEDIUM);

            RelatedEntityType type = parseRelatedType(event.getEntityType());
            if (type != null && event.getEntityId() != null) {
                req.setRelatedEntityType(type);
                req.setRelatedEntityId(event.getEntityId());
            }

            taskService.createTask(req);
            log.info("[automation-engine] created automated task for entity {}", event.getEntityId());
        }
    }

    private RelatedEntityType parseRelatedType(String typeStr) {
        if (typeStr == null) return null;
        try {
            return RelatedEntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void recordLog(AutomationRule rule, EntityChangedEvent event, String status,
                           Map<String, Object> conditionsTrace, Map<String, Object> actionsExecuted) {
        try {
            AutomationExecutionLog execLog = AutomationExecutionLog.builder()
                    .ruleId(rule.getId())
                    .ruleVersion(rule.getRuleVersion())
                    .trigger(event.getTrigger().name())
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId())
                    .dryRun(false)
                    .conditionsTrace(conditionsTrace)
                    .actionsExecuted(actionsExecuted)
                    .status(status)
                    .build();
            execLog.setOrganizationId(event.getOrganizationId());
            executionLogRepository.save(execLog);
        } catch (Exception e) {
            log.warn("[automation-engine] failed to persist execution log: {}", e.getMessage());
        }
    }
}
