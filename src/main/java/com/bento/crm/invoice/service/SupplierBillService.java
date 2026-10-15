package com.bento.crm.invoice.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.invoice.dto.BillFromPurchaseOrderRequest;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.model.BillAlertDigest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.BillAlertDigestRepository;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.purchaseorder.model.PurchaseOrder;
import com.bento.crm.purchaseorder.repository.PurchaseOrderRepository;
import com.bento.crm.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Supplier bills: a purchase order becomes the supplier's invoice (a VENDOR invoice), a bill can
 * be scheduled for payment, and admins and managers get one alert a day about bills that fall
 * due within {@link #DUE_SOON_DAYS} days or are already late.
 */
@Service
@Slf4j
public class SupplierBillService {

    static final int DUE_SOON_DAYS = 3;
    static final int DEFAULT_TERMS_DAYS = 30;
    private static final Set<Invoice.Status> UNPAID =
            EnumSet.of(Invoice.Status.SENT, Invoice.Status.PARTIALLY_PAID, Invoice.Status.OVERDUE);

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PartnerRepository partnerRepository;
    private final BillAlertDigestRepository digestRepository;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public SupplierBillService(InvoiceService invoiceService,
                               InvoiceRepository invoiceRepository,
                               PurchaseOrderRepository purchaseOrderRepository,
                               PartnerRepository partnerRepository,
                               BillAlertDigestRepository digestRepository,
                               NotificationService notificationService,
                               JdbcTemplate jdbc,
                               PlatformTransactionManager transactionManager) {
        this.invoiceService = invoiceService;
        this.invoiceRepository = invoiceRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.partnerRepository = partnerRepository;
        this.digestRepository = digestRepository;
        this.notificationService = notificationService;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Books the supplier's bill for a purchase order and marks the order INVOICED. A purchase
     * order is billed once; delete the bill first to book it again.
     */
    @Transactional
    public Invoice billPurchaseOrder(UUID purchaseOrderId, BillFromPurchaseOrderRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        PurchaseOrder po = purchaseOrderRepository.findByOrganizationIdAndId(orgId, purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found"));
        if (!invoiceRepository.findBillsForPurchaseOrder(orgId, po.getId()).isEmpty()) {
            throw new IllegalStateException("This purchase order already has a supplier bill");
        }
        BillFromPurchaseOrderRequest r = request != null ? request : new BillFromPurchaseOrderRequest();
        LocalDate invoiceDate = r.getInvoiceDate() != null ? r.getInvoiceDate() : LocalDate.now();
        int terms = r.getPaymentTermsDays() != null ? r.getPaymentTermsDays() : DEFAULT_TERMS_DAYS;
        LocalDate dueDate = r.getDueDate() != null ? r.getDueDate() : invoiceDate.plusDays(terms);
        if (dueDate.isBefore(invoiceDate)) {
            throw new IllegalArgumentException("The due date cannot be before the invoice date");
        }

        CreateInvoiceRequest bill = new CreateInvoiceRequest();
        bill.setType(Invoice.InvoiceType.VENDOR);
        bill.setPartnerId(po.getVendorPartnerId());
        bill.setDealId(po.getDealId());
        bill.setPurchaseOrderId(po.getId());
        bill.setStatus(Invoice.Status.SENT);
        String number = r.getSupplierInvoiceNumber();
        bill.setInvoiceNumber(number != null && !number.isBlank() ? number.trim() : null);
        bill.setInvoiceDate(invoiceDate);
        bill.setDueDate(dueDate);
        partnerRepository.findByOrganizationIdAndId(orgId, po.getVendorPartnerId())
                .ifPresent(p -> bill.setCustomerName(p.getName()));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (po.getLines() != null) {
            po.getLines().forEach(l -> lines.add(new HashMap<>(l)));
        }
        bill.setLines(lines);
        if (lines.isEmpty()) {
            bill.setSubtotal(po.getSubtotal());
            bill.setTax(po.getTax());
            bill.setTotal(po.getTotal());
        }
        Invoice created = invoiceService.createInvoice(orgId, bill);

        po.setStatus(PurchaseOrder.Status.INVOICED);
        purchaseOrderRepository.save(po);
        return created;
    }

    /** Plans (or, with null, unplans) the day a supplier bill will be paid. */
    @Transactional
    public Invoice schedulePayment(UUID invoiceId, LocalDate date) {
        Invoice invoice = invoiceService.getInvoice(invoiceId);
        if (invoice.getType() != Invoice.InvoiceType.VENDOR) {
            throw new IllegalArgumentException("Only supplier bills can be scheduled for payment");
        }
        if (invoice.getStatus() == Invoice.Status.PAID && date != null) {
            throw new IllegalStateException("This bill is already paid");
        }
        invoice.setScheduledPaymentDate(date);
        return invoiceRepository.save(invoice);
    }

    /**
     * Sends the organization's admins and managers one notification about supplier bills due in
     * the next few days or already late, at most once per day.
     *
     * @return whether an alert went out
     */
    public boolean alertForOrganization(UUID orgId, LocalDate today) {
        List<Invoice> unpaid = invoiceRepository.findIssuedByType(orgId, Invoice.InvoiceType.VENDOR).stream()
                .filter(i -> UNPAID.contains(i.getStatus()) && i.getDueDate() != null)
                .toList();
        List<Invoice> overdue = unpaid.stream().filter(i -> i.getDueDate().isBefore(today)).toList();
        List<Invoice> dueSoon = unpaid.stream()
                .filter(i -> !i.getDueDate().isBefore(today) && !i.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS)))
                .toList();
        if (overdue.isEmpty() && dueSoon.isEmpty()) {
            return false;
        }
        Boolean claimed = transactionTemplate.execute(status -> {
            try {
                BillAlertDigest digest = BillAlertDigest.builder()
                        .alertDate(today)
                        .dueSoonCount(dueSoon.size())
                        .overdueCount(overdue.size())
                        .build();
                digest.setOrganizationId(orgId);
                digestRepository.saveAndFlush(digest);
                return true;
            } catch (DataIntegrityViolationException e) {
                status.setRollbackOnly();
                return false;
            }
        });
        if (!Boolean.TRUE.equals(claimed)) {
            return false;
        }

        String title = overdue.isEmpty()
                ? count(dueSoon.size(), "supplier bill") + " due soon"
                : count(overdue.size(), "supplier bill") + " overdue";
        List<String> parts = new ArrayList<>();
        if (!overdue.isEmpty()) {
            parts.add(count(overdue.size(), "bill") + " past due (" + money(total(overdue)) + " MAD)");
        }
        if (!dueSoon.isEmpty()) {
            parts.add(count(dueSoon.size(), "bill") + " due in the next " + DUE_SOON_DAYS + " days ("
                    + money(total(dueSoon)) + " MAD)");
        }
        String message = String.join(", ", parts) + ". See Finance → Aging.";

        List<UUID> recipients = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role IN ('ADMIN', 'MANAGER') AND is_active = true",
                UUID.class, orgId);
        for (UUID userId : recipients) {
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(userId);
            n.setType(Notification.NotificationType.SYSTEM);
            n.setTitle(title);
            n.setMessage(message);
            n.setRelatedEntityType("SUPPLIER_BILLS");
            n.setIsRead(false);
            notificationService.createForOrganization(orgId, n);
        }
        return true;
    }

    /** Organizations that may need an alert today: they have unpaid bills due by the horizon. */
    public List<UUID> organizationsToAlert(LocalDate horizon) {
        return invoiceRepository.findOrganizationsWithBillsDueBy(horizon);
    }

    private static BigDecimal total(List<Invoice> invoices) {
        return invoices.stream().map(i -> i.getTotal() != null ? i.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
