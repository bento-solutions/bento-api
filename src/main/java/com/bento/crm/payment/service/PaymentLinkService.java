package com.bento.crm.payment.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.payment.dto.CreatePaymentRequest;
import com.bento.crm.payment.dto.PaymentSettingsRequest;
import com.bento.crm.payment.dto.PaymentSettingsResponse;
import com.bento.crm.payment.dto.PublicInvoiceView;
import com.bento.crm.payment.gateway.PaymentGateway;
import com.bento.crm.payment.gateway.PaymentGatewayRegistry;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.model.PaymentSettings;
import com.bento.crm.payment.repository.PaymentLinkRepository;
import com.bento.crm.payment.repository.PaymentRepository;
import com.bento.crm.payment.repository.PaymentSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pay-by-Link: one public link per customer invoice, a hosted checkout through the organization's
 * gateway, and settlement (a CARD payment that marks the invoice paid) when the gateway confirms.
 */
@Slf4j
@Service
public class PaymentLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    private final PaymentSettingsRepository settingsRepository;
    private final PaymentLinkRepository linkRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PaymentGatewayRegistry gateways;
    private final InvoiceRepository invoiceRepository;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final InvoiceTemplateService invoiceTemplateService;
    private final NotificationService notificationService;
    private final com.bento.crm.common.pdf.PdfDocumentService pdfDocumentService;
    private final JdbcTemplate jdbc;
    private final com.bento.crm.notification.service.TeamNotifier teamNotifier;
    private final String publicUrl;

    public PaymentLinkService(PaymentSettingsRepository settingsRepository, PaymentLinkRepository linkRepository,
                              PaymentRepository paymentRepository, PaymentService paymentService,
                              PaymentGatewayRegistry gateways, InvoiceRepository invoiceRepository,
                              PartnerRepository partnerRepository, OrganizationRepository organizationRepository,
                              InvoiceTemplateService invoiceTemplateService, NotificationService notificationService,
                              com.bento.crm.common.pdf.PdfDocumentService pdfDocumentService, JdbcTemplate jdbc,
                              com.bento.crm.notification.service.TeamNotifier teamNotifier,
                              @Value("${app.payments.public-url:http://localhost:3000}") String publicUrl) {
        this.settingsRepository = settingsRepository;
        this.linkRepository = linkRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.teamNotifier = teamNotifier;
        this.gateways = gateways;
        this.invoiceRepository = invoiceRepository;
        this.partnerRepository = partnerRepository;
        this.organizationRepository = organizationRepository;
        this.invoiceTemplateService = invoiceTemplateService;
        this.notificationService = notificationService;
        this.pdfDocumentService = pdfDocumentService;
        this.jdbc = jdbc;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    // ── Settings ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PaymentSettingsResponse settings() {
        return toResponse(settingsFor(TenantContext.getCurrentOrganizationId()));
    }

    @Transactional
    public PaymentSettingsResponse updateSettings(PaymentSettingsRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if (request.getProvider() != PaymentSettings.Provider.NONE && !gateways.available(request.getProvider())) {
            throw new IllegalArgumentException(request.getProvider() == PaymentSettings.Provider.STRIPE
                    ? "Stripe is not set up on this server yet (missing STRIPE_SECRET_KEY)"
                    : "Test payments are switched off on this server");
        }
        PaymentSettings s = settingsFor(orgId);
        s.setOrganizationId(orgId);
        s.setProvider(request.getProvider());
        if (request.getIncludeInEmails() != null) {
            s.setIncludeInEmails(request.getIncludeInEmails());
        }
        return toResponse(settingsRepository.save(s));
    }

    private PaymentSettings settingsFor(UUID orgId) {
        return settingsRepository.findByOrganizationIdAndDeletedAtIsNull(orgId).orElseGet(() -> {
            PaymentSettings s = new PaymentSettings();
            s.setOrganizationId(orgId);
            return s;
        });
    }

    private PaymentSettingsResponse toResponse(PaymentSettings s) {
        List<PaymentSettings.Provider> available = Arrays.stream(PaymentSettings.Provider.values())
                .filter(p -> p == PaymentSettings.Provider.NONE || gateways.available(p))
                .toList();
        return new PaymentSettingsResponse(s.getProvider(), Boolean.TRUE.equals(s.getIncludeInEmails()), available);
    }

    // ── Links (staff) ───────────────────────────────────────────────────────────

    /** The invoice's open link, created on first ask. */
    @Transactional
    public PaymentLink linkFor(UUID invoiceId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Invoice invoice = invoiceRepository.findByOrganizationIdAndId(orgId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        return openLink(invoice, true);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentLink> currentLink(UUID invoiceId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return linkRepository.findForInvoice(orgId, invoiceId).stream().findFirst();
    }

    /** Stops the open link working; a new one can be made afterwards. */
    @Transactional
    public void cancel(UUID invoiceId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        linkRepository.findForInvoice(orgId, invoiceId).stream()
                .filter(l -> l.getStatus() == PaymentLink.Status.OPEN)
                .forEach(l -> {
                    l.setStatus(PaymentLink.Status.CANCELLED);
                    linkRepository.save(l);
                });
    }

    public String urlFor(PaymentLink link) {
        return publicUrl + "/pay/" + link.getToken();
    }

    /**
     * The "Pay online" URL for an invoice or reminder email, creating the link if needed. Empty when
     * the organization has Pay-by-Link off, has asked to keep it out of emails, or nothing is owed.
     * Never throws: an email goes out without the button rather than not at all.
     */
    @Transactional
    public Optional<String> urlForEmail(Invoice invoice) {
        try {
            PaymentSettings s = settingsFor(invoice.getOrganizationId());
            if (s.getProvider() == PaymentSettings.Provider.NONE || !Boolean.TRUE.equals(s.getIncludeInEmails())
                    || !gateways.available(s.getProvider())) {
                return Optional.empty();
            }
            return Optional.of(urlFor(openLink(invoice, false)));
        } catch (RuntimeException e) {
            log.warn("No payment link for invoice {}: {}", invoice.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private PaymentLink openLink(Invoice invoice, boolean explain) {
        if (invoice.getType() != Invoice.InvoiceType.CUSTOMER) {
            throw new IllegalArgumentException("Payment links are for customer invoices");
        }
        if (invoice.getStatus() == Invoice.Status.DRAFT) {
            throw new IllegalStateException("Send the invoice before sharing a payment link");
        }
        if (invoice.getStatus() == Invoice.Status.PAID) {
            throw new IllegalStateException("This invoice is already paid");
        }
        PaymentSettings s = settingsFor(invoice.getOrganizationId());
        if (s.getProvider() == PaymentSettings.Provider.NONE) {
            throw new IllegalStateException("Turn on online payment in Settings → Payments first");
        }
        return linkRepository.findForInvoice(invoice.getOrganizationId(), invoice.getId()).stream()
                .filter(l -> l.getStatus() == PaymentLink.Status.OPEN && l.getProvider() == s.getProvider())
                .findFirst()
                .orElseGet(() -> {
                    PaymentLink link = new PaymentLink();
                    link.setOrganizationId(invoice.getOrganizationId());
                    link.setInvoiceId(invoice.getId());
                    link.setProvider(s.getProvider());
                    link.setToken(newToken());
                    return linkRepository.save(link);
                });
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Public page ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PublicInvoiceView publicView(String token) {
        PaymentLink link = byToken(token);
        Invoice invoice = invoiceOf(link);
        Partner partner = partnerOf(invoice);
        InvoiceTemplateService.PublicLook look = invoiceTemplateService.publicLookFor(invoice, partner);
        String orgName = look.displayName() != null ? look.displayName()
                : organizationRepository.findById(invoice.getOrganizationId()).map(Organization::getName).orElse("");
        BigDecimal total = nz(invoice.getTotal());
        BigDecimal paid = paidOn(invoice);
        BigDecimal remaining = invoice.getStatus() == Invoice.Status.PAID ? BigDecimal.ZERO : total.subtract(paid).max(BigDecimal.ZERO);
        String status = link.getStatus() == PaymentLink.Status.CANCELLED ? "CANCELLED"
                : remaining.signum() == 0 ? "PAID" : "OPEN";
        return new PublicInvoiceView(orgName, look.logoFileId(), look.accentColor(),
                partner != null ? partner.getName() : invoice.getCustomerName(),
                numberOf(invoice), invoice.getInvoiceDate(), invoice.getDueDate(), link.getCurrency(),
                total, paid, remaining, status, link.getProvider() == PaymentSettings.Provider.MOCK,
                linesOf(invoice));
    }

    /** The invoice PDF, for the "Download" button on the pay page. */
    @Transactional(readOnly = true)
    public PublicPdf publicPdf(String token) {
        PaymentLink link = byToken(token);
        Invoice invoice = invoiceOf(link);
        Partner partner = partnerOf(invoice);
        Organization org = organizationRepository.findById(invoice.getOrganizationId()).orElse(null);
        byte[] pdf = pdfDocumentService.generateInvoicePdf(invoice, partner, org,
                invoiceTemplateService.brandingFor(invoice, partner));
        return new PublicPdf("facture-" + numberOf(invoice) + ".pdf", pdf);
    }

    public record PublicPdf(String filename, byte[] content) {}

    /** Opens the gateway's checkout for what is still owed and returns where to send the customer. */
    @Transactional
    public String startCheckout(String token) {
        PaymentLink link = byToken(token);
        if (link.getStatus() != PaymentLink.Status.OPEN) {
            throw new IllegalStateException(link.getStatus() == PaymentLink.Status.PAID
                    ? "This invoice is already paid" : "This payment link is no longer active");
        }
        Invoice invoice = invoiceOf(link);
        BigDecimal remaining = nz(invoice.getTotal()).subtract(paidOn(invoice));
        if (invoice.getStatus() == Invoice.Status.PAID || remaining.signum() <= 0) {
            throw new IllegalStateException("This invoice is already paid");
        }
        PaymentGateway gateway = gateways.get(link.getProvider());
        if (!gateway.configured()) {
            throw new IllegalStateException("Online payment is not available right now");
        }
        link.setAmount(remaining.setScale(2, RoundingMode.HALF_UP));
        Partner partner = partnerOf(invoice);
        String pageUrl = urlFor(link);
        PaymentGateway.Checkout checkout = gateway.startCheckout(link, "Facture " + numberOf(invoice),
                partner != null ? partner.getEmail() : null, pageUrl + "?status=success", pageUrl);
        link.setProviderSessionId(checkout.sessionId());
        linkRepository.save(link);
        return checkout.url();
    }

    /** The test gateway's "pay" button: settles the session the mock checkout opened. */
    @Transactional
    public void completeMock(String token, String sessionId) {
        PaymentLink link = byToken(token);
        if (link.getProvider() != PaymentSettings.Provider.MOCK || !gateways.available(PaymentSettings.Provider.MOCK)) {
            throw new IllegalStateException("Test payments are not enabled for this link");
        }
        if (sessionId == null || !sessionId.equals(link.getProviderSessionId())) {
            throw new IllegalArgumentException("Unknown test checkout");
        }
        settle(link, link.getAmount(), sessionId);
    }

    /** A provider webhook. Unknown sessions are ignored (they may belong to another system). */
    @Transactional
    public void handleWebhook(PaymentSettings.Provider provider, String payload, String signature) {
        gateways.get(provider).parseWebhook(payload, signature).ifPresent(done ->
                linkRepository.findByProviderSessionId(done.sessionId()).ifPresentOrElse(
                        link -> settle(link, done.amount(), done.reference()),
                        () -> log.info("Ignoring {} payment for unknown session {}", provider, done.sessionId())));
    }

    /**
     * Records the card payment and marks the link paid. Idempotent: a second confirmation for the
     * same session (a webhook retried, or a double click) finds the link already settled. Concurrent
     * confirmations race on the link's version, so only one payment is kept.
     */
    private void settle(PaymentLink link, BigDecimal amount, String reference) {
        if (link.getStatus() == PaymentLink.Status.PAID) {
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalStateException("Nothing to settle");
        }
        Invoice invoice = invoiceOf(link);
        UUID previous = TenantContext.currentOrganizationIdOrNull();
        TenantContext.setCurrentOrganizationId(link.getOrganizationId());
        try {
            // The amount was fixed when checkout opened; staff may have recorded a payment since.
            BigDecimal owed = invoice.getStatus() == Invoice.Status.PAID ? BigDecimal.ZERO
                    : nz(invoice.getTotal()).subtract(paidOn(invoice)).max(BigDecimal.ZERO);
            // A real card charge is money received: it is recorded whatever is owed, and any excess
            // must be refunded. The test gateway charges nothing, so it records only what is owed.
            boolean realCharge = link.getProvider() != PaymentSettings.Provider.MOCK;
            BigDecimal recorded = realCharge ? amount : amount.min(owed);
            if (recorded.signum() <= 0) {
                link.setStatus(PaymentLink.Status.CANCELLED);
                linkRepository.saveAndFlush(link);
                log.info("Test payment for already-settled invoice {} ignored", invoice.getId());
                return;
            }
            CreatePaymentRequest request = new CreatePaymentRequest();
            request.setPartnerId(invoice.getPartnerId());
            request.setInvoiceId(invoice.getId());
            request.setPaymentDate(LocalDate.now(CASABLANCA));
            request.setAmount(recorded);
            request.setMethod(Payment.Method.CARD);
            request.setReference(reference);
            request.setNotes("Paid online (" + link.getProvider().name().toLowerCase() + ")");
            Payment payment = paymentService.createPayment(request);

            link.setStatus(PaymentLink.Status.PAID);
            link.setPaidAt(Instant.now());
            link.setPaymentId(payment.getId());
            linkRepository.saveAndFlush(link);
            notifyPaid(invoice, recorded);
            if (realCharge && amount.compareTo(owed) > 0) {
                teamNotifier.notifyManagers(invoice.getOrganizationId(), "Refund needed: invoice " + numberOf(invoice),
                        amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " MAD was paid online but only "
                                + owed.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                + " MAD was still owed. Refund the difference to the customer.",
                        "INVOICE", invoice.getId());
            }
        } finally {
            if (previous != null) {
                TenantContext.setCurrentOrganizationId(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void notifyPaid(Invoice invoice, BigDecimal amount) {
        UUID orgId = invoice.getOrganizationId();
        List<UUID> recipients = jdbc.queryForList(
                "SELECT id FROM app_user WHERE organization_id = ? AND role IN ('ADMIN', 'MANAGER') AND is_active = true",
                UUID.class, orgId);
        for (UUID userId : recipients) {
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(userId);
            n.setType(Notification.NotificationType.SYSTEM);
            n.setTitle("Invoice " + numberOf(invoice) + " paid online");
            n.setMessage(amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " MAD received by card.");
            n.setRelatedEntityType("INVOICE");
            n.setRelatedEntityId(invoice.getId());
            n.setIsRead(false);
            notificationService.createForOrganization(orgId, n);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private PaymentLink byToken(String token) {
        if (token == null || token.length() < 16 || token.length() > 64) {
            throw new ResourceNotFoundException("Payment link not found");
        }
        return linkRepository.findByTokenAndDeletedAtIsNull(token)
                .orElseThrow(() -> new ResourceNotFoundException("Payment link not found"));
    }

    private Invoice invoiceOf(PaymentLink link) {
        return invoiceRepository.findByOrganizationIdAndId(link.getOrganizationId(), link.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment link not found"));
    }

    private Partner partnerOf(Invoice invoice) {
        if (invoice.getPartnerId() == null) return null;
        return partnerRepository.findByOrganizationIdAndId(invoice.getOrganizationId(), invoice.getPartnerId()).orElse(null);
    }

    private BigDecimal paidOn(Invoice invoice) {
        return paymentRepository.findByOrganizationIdAndInvoiceId(invoice.getOrganizationId(), invoice.getId()).stream()
                .map(Payment::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String numberOf(Invoice invoice) {
        return invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber()
                : invoice.getId().toString().substring(0, 8).toUpperCase();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static List<PublicInvoiceView.Line> linesOf(Invoice invoice) {
        List<PublicInvoiceView.Line> lines = new ArrayList<>();
        if (invoice.getLines() == null) return lines;
        for (Map<String, Object> l : invoice.getLines()) {
            Object description = l.getOrDefault("description", l.get("product"));
            BigDecimal qty = decimal(l.get("qty"));
            BigDecimal price = decimal(l.getOrDefault("unitPrice", l.get("unit_price")));
            BigDecimal amount = qty != null && price != null ? qty.multiply(price).setScale(2, RoundingMode.HALF_UP) : null;
            lines.add(new PublicInvoiceView.Line(description != null ? description.toString() : "", qty, price, amount));
        }
        return lines;
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
