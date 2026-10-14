package com.bento.crm.invoice.controller;

import com.bento.crm.invoice.dto.InvoiceReminderPolicyRequest;
import com.bento.crm.invoice.dto.InvoiceReminderPolicyResponse;
import com.bento.crm.invoice.model.InvoiceReminderLog;
import com.bento.crm.invoice.service.InvoiceReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/invoice-reminders")
@RequiredArgsConstructor
@Tag(name = "Invoice reminders", description = "Automatic payment reminders for unpaid customer invoices")
public class InvoiceReminderController {

    private final InvoiceReminderService invoiceReminderService;

    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Get reminder policy", description = "The default, switched off, when none has been saved")
    public ResponseEntity<InvoiceReminderPolicyResponse> getPolicy() {
        return ResponseEntity.ok(InvoiceReminderPolicyResponse.fromEntity(invoiceReminderService.getPolicy()));
    }

    @PutMapping("/policy")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Save reminder policy",
            description = "offsetsDays are days relative to the due date: -3 is three days before, 7 a week after")
    public ResponseEntity<InvoiceReminderPolicyResponse> updatePolicy(@Valid @RequestBody InvoiceReminderPolicyRequest request) {
        return ResponseEntity.ok(InvoiceReminderPolicyResponse.fromEntity(invoiceReminderService.updatePolicy(request)));
    }

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Run today's reminders now",
            description = "Marks late invoices overdue and sends the reminders due today; never sends one twice")
    public ResponseEntity<InvoiceReminderService.RunResult> runNow() {
        return ResponseEntity.ok(invoiceReminderService.runNow());
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "Automatic reminders sent for one invoice", description = "Newest first")
    public ResponseEntity<List<ReminderEntry>> history(@RequestParam UUID invoiceId) {
        return ResponseEntity.ok(invoiceReminderService.history(invoiceId).stream().map(ReminderEntry::of).toList());
    }

    public record ReminderEntry(int offsetDays, InvoiceReminderLog.Outcome outcome, String recipient, Instant sentAt) {
        static ReminderEntry of(InvoiceReminderLog log) {
            return new ReminderEntry(log.getOffsetDays(), log.getOutcome(), log.getRecipient(), log.getCreatedAt());
        }
    }
}
