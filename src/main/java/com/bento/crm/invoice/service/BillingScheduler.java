package com.bento.crm.invoice.service;

import com.bento.crm.invoice.repository.RecurringInvoiceRepository;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The billing jobs: recurring invoices hourly, and once a day the overdue sweep and payment
 * reminders. Both are safe to run on several instances at once (see the services).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingScheduler {

    private static final int BATCH = 200;

    private final RecurringInvoiceService recurringInvoiceService;
    private final InvoiceReminderService invoiceReminderService;
    private final InvoiceMailer invoiceMailer;
    private final OrganizationRepository organizationRepository;
    private final RecurringInvoiceRepository recurringInvoiceRepository;

    @Scheduled(cron = "${crm.billing.recurring-cron:0 5 * * * *}")
    public void generateRecurringInvoices() {
        // A day ahead of UTC covers every time zone; each template re-checks its own "today".
        LocalDate horizon = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        List<UUID> due = recurringInvoiceRepository.findDueIds(horizon, PageRequest.of(0, BATCH));
        int created = 0;
        for (UUID id : due) {
            try {
                for (RecurringInvoiceService.Generated generated : recurringInvoiceService.generateDue(id)) {
                    created++;
                    if (generated.send()) {
                        invoiceMailer.sendInvoice(generated.invoice());
                    }
                }
            } catch (Exception e) {
                // One broken template must not stop the others from billing.
                log.error("[billing] recurring invoice {} failed", id, e);
            }
        }
        if (created > 0) {
            log.info("[billing] generated {} invoice(s) from {} recurring template(s)", created, due.size());
        }
    }

    @Scheduled(cron = "${crm.billing.reminder-cron:0 0 8 * * *}", zone = "${crm.billing.default-zone:Africa/Casablanca}")
    public void runDailyReminders() {
        for (Organization org : organizationRepository.findAll()) {
            try {
                invoiceReminderService.runForOrganization(org.getId());
            } catch (Exception e) {
                log.error("[billing] reminders for organization {} failed", org.getId(), e);
            }
        }
    }
}
