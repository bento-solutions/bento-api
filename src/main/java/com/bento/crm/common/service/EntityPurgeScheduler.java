package com.bento.crm.common.service;

import com.bento.crm.automation.repository.AutomationRuleRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.deal.repository.DealRepository;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.payment.repository.PaymentRepository;
import com.bento.crm.proposal.repository.ProposalRepository;
import com.bento.crm.purchaseorder.repository.PurchaseOrderRepository;
import com.bento.crm.task.repository.TaskRepository;
import com.bento.crm.ticket.repository.TicketRepository;
import com.bento.crm.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Hard-deletes soft-deleted entities whose 30-day grace period has expired.
 * Org-wide and outside any tenant request context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityPurgeScheduler {

    private final PartnerRepository partnerRepository;
    private final DealRepository dealRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProposalRepository proposalRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TicketRepository ticketRepository;
    private final TaskRepository taskRepository;
    private final CampaignRepository campaignRepository;
    private final AutomationRuleRepository automationRuleRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;

    @Value("${crm.purge.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${crm.purge.cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));

        var partners = partnerRepository.findPurgeable(cutoff);
        if (!partners.isEmpty()) {
            partnerRepository.deleteAll(partners);
            log.info("[purge] hard-deleted {} partner(s)", partners.size());
        }

        var deals = dealRepository.findPurgeable(cutoff);
        if (!deals.isEmpty()) {
            dealRepository.deleteAll(deals);
            log.info("[purge] hard-deleted {} deal(s)", deals.size());
        }

        var invoices = invoiceRepository.findPurgeable(cutoff);
        if (!invoices.isEmpty()) {
            invoiceRepository.deleteAll(invoices);
            log.info("[purge] hard-deleted {} invoice(s)", invoices.size());
        }

        var proposals = proposalRepository.findPurgeable(cutoff);
        if (!proposals.isEmpty()) {
            proposalRepository.deleteAll(proposals);
            log.info("[purge] hard-deleted {} proposal(s)", proposals.size());
        }

        var purchaseOrders = purchaseOrderRepository.findPurgeable(cutoff);
        if (!purchaseOrders.isEmpty()) {
            purchaseOrderRepository.deleteAll(purchaseOrders);
            log.info("[purge] hard-deleted {} purchase order(s)", purchaseOrders.size());
        }

        var tickets = ticketRepository.findPurgeable(cutoff);
        if (!tickets.isEmpty()) {
            ticketRepository.deleteAll(tickets);
            log.info("[purge] hard-deleted {} ticket(s)", tickets.size());
        }

        var tasks = taskRepository.findPurgeable(cutoff);
        if (!tasks.isEmpty()) {
            taskRepository.deleteAll(tasks);
            log.info("[purge] hard-deleted {} task(s)", tasks.size());
        }

        var campaigns = campaignRepository.findPurgeable(cutoff);
        if (!campaigns.isEmpty()) {
            campaignRepository.deleteAll(campaigns);
            log.info("[purge] hard-deleted {} campaign(s)", campaigns.size());
        }

        var rules = automationRuleRepository.findPurgeable(cutoff);
        if (!rules.isEmpty()) {
            automationRuleRepository.deleteAll(rules);
            log.info("[purge] hard-deleted {} automation rule(s)", rules.size());
        }

        var payments = paymentRepository.findPurgeable(cutoff);
        if (!payments.isEmpty()) {
            paymentRepository.deleteAll(payments);
            log.info("[purge] hard-deleted {} payment(s)", payments.size());
        }

        var products = productRepository.findPurgeable(cutoff);
        if (!products.isEmpty()) {
            productRepository.deleteAll(products);
            log.info("[purge] hard-deleted {} product(s)", products.size());
        }
    }
}
