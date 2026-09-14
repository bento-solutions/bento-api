package com.bento.crm.automation.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.automation.dto.CreateAutomationRuleRequest;
import com.bento.crm.automation.dto.AutomationRuleResponse;
import com.bento.crm.automation.model.AutomationRule;
import com.bento.crm.automation.service.AutomationRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/automation-rules")
@Tag(name = "Automation Rules", description = "Automation rule management endpoints")
public class AutomationRuleController {

    private final AutomationRuleService automationRuleService;

    public AutomationRuleController(AutomationRuleService automationRuleService) {
        this.automationRuleService = automationRuleService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_CREATE')")
    @Operation(summary = "Create automation rule", description = "Create a new automation rule")
    public ResponseEntity<AutomationRuleResponse> createAutomationRule(@Valid @RequestBody CreateAutomationRuleRequest request) {
        AutomationRule created = automationRuleService.createAutomationRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AutomationRuleResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_READ')")
    @Operation(summary = "Get automation rule by ID", description = "Retrieve automation rule details")
    public ResponseEntity<AutomationRule> getAutomationRule(@PathVariable UUID id) {
        AutomationRule rule = automationRuleService.getAutomationRule(id);
        return ResponseEntity.ok(rule);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_READ')")
    @Operation(summary = "List automation rules", description = "List all automation rules in the organization")
    public ResponseEntity<PageResponse<AutomationRule>> listAutomationRules(Pageable pageable) {
        Page<AutomationRule> page = automationRuleService.listAutomationRules(pageable);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_WRITE')")
    @Operation(summary = "Update automation rule", description = "Update automation rule information")
    public ResponseEntity<AutomationRule> updateAutomationRule(@PathVariable UUID id, @Valid @RequestBody CreateAutomationRuleRequest request) {
        AutomationRule rule = automationRuleService.updateAutomationRule(id, request);
        return ResponseEntity.ok(rule);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_DELETE')")
    @Operation(summary = "Delete automation rule", description = "Soft delete automation rule record")
    public ResponseEntity<Void> deleteAutomationRule(@PathVariable UUID id) {
        automationRuleService.deleteAutomationRule(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_DELETE')")
    @Operation(summary = "Restore automation rule", description = "Undo a soft delete on an automation rule")
    public ResponseEntity<AutomationRule> restoreAutomationRule(@PathVariable UUID id) {
        return ResponseEntity.ok(automationRuleService.restoreAutomationRule(id));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('AUTOMATION_RULES_DELETE')")
    @Operation(summary = "List deleted automation rules", description = "Soft-deleted automation rules still inside the retention window")
    public ResponseEntity<PageResponse<AutomationRule>> listDeleted(Pageable pageable) {
        Page<AutomationRule> page = automationRuleService.listDeleted(pageable);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
