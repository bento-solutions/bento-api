package com.bento.crm.automation.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.automation.dto.CreateAutomationRuleRequest;
import com.bento.crm.automation.model.AutomationRule;
import com.bento.crm.automation.repository.AutomationRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AutomationRuleService {

    private final AutomationRuleRepository automationRuleRepository;

    @Transactional
    public AutomationRule createAutomationRule(CreateAutomationRuleRequest request) {
        AutomationRule rule = new AutomationRule();
        applyRequest(rule, request);
        rule.setOrganizationId(TenantContext.getCurrentOrganizationId());
        return automationRuleRepository.save(rule);
    }

    public AutomationRule getAutomationRule(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return automationRuleRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Automation Rule not found"));
    }

    public Page<AutomationRule> listAutomationRules(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return automationRuleRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public AutomationRule updateAutomationRule(UUID id, CreateAutomationRuleRequest request) {
        AutomationRule rule = getAutomationRule(id);
        applyRequest(rule, request);
        return automationRuleRepository.save(rule);
    }

    private void applyRequest(AutomationRule rule, CreateAutomationRuleRequest request) {
        rule.setName(request.getName());
        rule.setDescription(request.getDescription());
        rule.setIsActive(request.getIsActive());
        rule.setTrigger(request.getTrigger());
        rule.setConditionGroups(request.getConditionGroups());
        rule.setActions(request.getActions());
        rule.setPriority(request.getPriority());
        rule.setStopOnMatch(request.getStopOnMatch());
        rule.setRuleVersion(request.getVersion());
    }

    @Transactional
    public void deleteAutomationRule(UUID id) {
        AutomationRule rule = getAutomationRule(id);
        rule.setDeletedAt(java.time.Instant.now());
        automationRuleRepository.save(rule);
    }

    @Transactional
    public AutomationRule restoreAutomationRule(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        AutomationRule rule = automationRuleRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Automation Rule not found"));
        rule.setDeletedAt(null);
        return automationRuleRepository.save(rule);
    }

    public Page<AutomationRule> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return automationRuleRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
