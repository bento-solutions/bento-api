package com.bento.crm.invoice.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.invoice.dto.InvoiceReminderPolicyRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.model.InvoiceReminderLog;
import com.bento.crm.invoice.model.InvoiceReminderPolicy;
import com.bento.crm.invoice.repository.InvoiceReminderLogRepository;
import com.bento.crm.invoice.repository.InvoiceReminderPolicyRepository;
import com.bento.crm.invoice.repository.InvoiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Chases unpaid customer invoices on the days an organization's reminder policy names, and
 * moves sent invoices past their due date to OVERDUE.
 *
 * <p>Each invoice gets at most one reminder per run: the latest offset it has reached. An
 * invoice that is already 20 days late when the policy is switched on gets the "15 days late"
 * reminder once, not the four earlier ones in a burst.
 */
@Service
@Slf4j
public class InvoiceReminderService {

    static final String UPCOMING_TEXT =
            "Nous vous rappelons que votre facture arrive bientôt à échéance. Merci de prévoir son règlement.";

    private final InvoiceReminderPolicyRepository policyRepository;
    private final InvoiceReminderLogRepository logRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceMailer invoiceMailer;
    private final BillingCalendar billingCalendar;
    private final TransactionTemplate transactionTemplate;

    public InvoiceReminderService(InvoiceReminderPolicyRepository policyRepository,
                                  InvoiceReminderLogRepository logRepository,
                                  InvoiceRepository invoiceRepository,
                                  InvoiceMailer invoiceMailer,
                                  BillingCalendar billingCalendar,
                                  PlatformTransactionManager transactionManager) {
        this.policyRepository = policyRepository;
        this.logRepository = logRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceMailer = invoiceMailer;
        this.billingCalendar = billingCalendar;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** The caller's policy, or the default (switched off) when none has been saved. */
    public InvoiceReminderPolicy getPolicy() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return policyRepository.findByOrganizationId(orgId).orElseGet(() -> {
            InvoiceReminderPolicy policy = new InvoiceReminderPolicy();
            policy.setOrganizationId(orgId);
            return policy;
        });
    }

    @Transactional
    public InvoiceReminderPolicy updatePolicy(InvoiceReminderPolicyRequest request) {
        InvoiceReminderPolicy policy = getPolicy();
        policy.setEnabled(request.getEnabled());
        policy.setOffsetsDays(new ArrayList<>(new TreeSet<>(request.getOffsetsDays())));
        String message = request.getMessage();
        policy.setMessage(message != null && !message.isBlank() ? message.trim() : null);
        return policyRepository.save(policy);
    }

    public List<InvoiceReminderLog> history(UUID invoiceId) {
        return logRepository.findByOrganizationIdAndInvoiceId(TenantContext.getCurrentOrganizationId(), invoiceId);
    }

    /** Runs the daily pass for the caller's organization now. */
    public RunResult runNow() {
        return runForOrganization(TenantContext.getCurrentOrganizationId());
    }

    /**
     * The daily pass for one organization: mark late invoices OVERDUE, then send the reminders
     * that are due today if the organization has switched reminders on.
     */
    public RunResult runForOrganization(UUID orgId) {
        LocalDate today = billingCalendar.today(orgId);
        Integer overdue = transactionTemplate.execute(status ->
                invoiceRepository.markOverdue(orgId, today, Instant.now()));

        Optional<InvoiceReminderPolicy> policy = policyRepository.findByOrganizationId(orgId)
                .filter(p -> Boolean.TRUE.equals(p.getEnabled()));
        if (policy.isEmpty() || policy.get().getOffsetsDays() == null || policy.get().getOffsetsDays().isEmpty()) {
            return new RunResult(overdue != null ? overdue : 0, 0, 0);
        }

        int sent = 0;
        int skipped = 0;
        for (Invoice invoice : invoiceRepository.findReminderCandidates(orgId)) {
            Integer offset = offsetReached(policy.get().getOffsetsDays(), invoice.getDueDate(), today);
            if (offset == null) {
                continue;
            }
            Optional<InvoiceReminderLog> logged = record(invoice, offset);
            if (logged.isEmpty()) {
                continue;
            }
            if (logged.get().getOutcome() == InvoiceReminderLog.Outcome.SENT) {
                // Sent only once the log row has committed, so a run that lost the race for
                // this reminder never emails it.
                invoiceMailer.sendReminder(invoice, messageFor(policy.get(), offset));
                sent++;
            } else {
                skipped++;
            }
        }
        if (sent > 0 || skipped > 0) {
            log.info("[reminders] org {}: {} sent, {} skipped without an email address", orgId, sent, skipped);
        }
        return new RunResult(overdue != null ? overdue : 0, sent, skipped);
    }

    /** The latest offset whose day has come for this due date, or null when none has. */
    static Integer offsetReached(List<Integer> offsets, LocalDate dueDate, LocalDate today) {
        Integer reached = null;
        for (Integer offset : offsets) {
            if (offset != null && !dueDate.plusDays(offset).isAfter(today) && (reached == null || offset > reached)) {
                reached = offset;
            }
        }
        return reached;
    }

    /**
     * Claims the reminder for {@code (invoice, offset)} by writing its log row, and counts it on
     * the invoice. Empty when it was already sent, by an earlier run or a concurrent one.
     */
    private Optional<InvoiceReminderLog> record(Invoice invoice, int offset) {
        if (logRepository.existsByInvoiceIdAndOffsetDays(invoice.getId(), offset)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(transactionTemplate.execute(status -> {
                Optional<String> recipient = invoiceMailer.recipientFor(invoice);
                InvoiceReminderLog entry = InvoiceReminderLog.builder()
                        .invoiceId(invoice.getId())
                        .offsetDays(offset)
                        .channel(InvoiceReminderPolicy.Channel.EMAIL)
                        .outcome(recipient.isPresent() ? InvoiceReminderLog.Outcome.SENT : InvoiceReminderLog.Outcome.SKIPPED_NO_EMAIL)
                        .recipient(recipient.orElse(null))
                        .build();
                entry.setOrganizationId(invoice.getOrganizationId());
                entry = logRepository.saveAndFlush(entry);
                if (recipient.isPresent()) {
                    invoiceRepository.findById(invoice.getId()).ifPresent(fresh -> {
                        fresh.setReminderCount(fresh.getReminderCount() + 1);
                        fresh.setLastReminderAt(Instant.now());
                        invoiceRepository.save(fresh);
                    });
                }
                return entry;
            }));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    private static String messageFor(InvoiceReminderPolicy policy, int offset) {
        if (policy.getMessage() != null && !policy.getMessage().isBlank()) {
            return policy.getMessage();
        }
        return offset < 0 ? UPCOMING_TEXT : null;
    }

    public record RunResult(int markedOverdue, int remindersSent, int skippedNoEmail) {
    }
}
