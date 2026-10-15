package com.bento.crm.invoice.service;

import com.bento.crm.common.mail.EmailService;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The emails an invoice's customer receives: the invoice itself with its PDF, and payment
 * reminders. Takes the organization from the invoice rather than the request, so the
 * schedulers can use it outside any tenant context.
 */
@Component
@RequiredArgsConstructor
public class InvoiceMailer {

    static final String DEFAULT_REMINDER_TEXT =
            "Nous vous rappelons amicalement de régler votre facture arrivée à échéance.";

    private final EmailService emailService;
    private final PdfDocumentService pdfDocumentService;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceTemplateService invoiceTemplateService;
    private final com.bento.crm.payment.service.PaymentLinkService paymentLinkService;

    /**
     * Emails the invoice with its PDF attached.
     *
     * @return the address it went to, or empty when the customer has no usable email
     */
    public Optional<String> sendInvoice(Invoice invoice) {
        Partner partner = partnerOf(invoice).orElse(null);
        String to = emailOf(partner);
        if (to == null) {
            return Optional.empty();
        }
        Organization org = organizationRepository.findById(invoice.getOrganizationId()).orElse(null);
        String number = numberOf(invoice);

        Map<String, String> variables = new HashMap<>();
        variables.put("organizationName", org != null && org.getName() != null ? org.getName() : "Bento CRM");
        variables.put("invoiceNumber", number);
        variables.put("clientName", partner.getName() != null ? partner.getName() : "Client");
        variables.put("invoiceDate", invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().toString() : "");
        variables.put("dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");
        variables.put("totalAmount", money(invoice.getTotal()));

        variables.replaceAll((k, v) -> HtmlUtils.htmlEscape(v));
        variables.put("payButton", payButton(invoice));

        byte[] pdf = pdfDocumentService.generateInvoicePdf(invoice, partner, org,
                invoiceTemplateService.brandingFor(invoice, partner));
        emailService.sendHtmlWithAttachment(to, "Facture n° " + number, "invoice-email", variables,
                new EmailService.Attachment("facture-" + number + ".pdf", pdf, "application/pdf"));
        return Optional.of(to);
    }

    /**
     * Emails a payment reminder showing what is still owed.
     *
     * @param message wording to use instead of the default, or null
     * @return the address it went to, or empty when the customer has no usable email
     */
    public Optional<String> sendReminder(Invoice invoice, String message) {
        Partner partner = partnerOf(invoice).orElse(null);
        String to = emailOf(partner);
        if (to == null) {
            return Optional.empty();
        }
        String orgName = organizationRepository.findById(invoice.getOrganizationId())
                .map(Organization::getName)
                .orElse("Bento CRM");
        String number = numberOf(invoice);

        Map<String, String> variables = new HashMap<>();
        variables.put("organizationName", orgName);
        variables.put("invoiceNumber", number);
        variables.put("clientName", partner.getName() != null ? partner.getName() : "Client");
        variables.put("messageText", message != null && !message.isBlank() ? message : DEFAULT_REMINDER_TEXT);
        variables.put("dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "Échue");
        variables.put("remainingAmount", money(remaining(invoice)));

        variables.replaceAll((k, v) -> HtmlUtils.htmlEscape(v));
        variables.put("payButton", payButton(invoice));

        emailService.sendHtml(to, "Rappel d'échéance : Facture n° " + number, "reminder-email", variables);
        return Optional.of(to);
    }

    /**
     * A "Payer en ligne" button when the organization takes card payments, else nothing. Built
     * after the variables are escaped, so it is the one value that carries markup; the URL is
     * escaped on its own.
     */
    private String payButton(Invoice invoice) {
        return paymentLinkService.urlForEmail(invoice)
                .map(url -> "<p style=\"margin:0 0 24px 0;text-align:center;\"><a href=\"" + HtmlUtils.htmlEscape(url)
                        + "\" style=\"display:inline-block;background-color:#09090b;color:#ffffff;text-decoration:none;"
                        + "font-size:14px;font-weight:600;padding:12px 24px;border-radius:10px;\">Payer en ligne par carte</a></p>")
                .orElse("");
    }

    /** The address reminders and invoices for this invoice go to, if the customer has one. */
    public Optional<String> recipientFor(Invoice invoice) {
        return Optional.ofNullable(emailOf(partnerOf(invoice).orElse(null)));
    }

    /** The invoice total less the payments recorded against it, never below zero. */
    BigDecimal remaining(Invoice invoice) {
        if (invoice.getTotal() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = paymentRepository
                .findByOrganizationIdAndInvoiceId(invoice.getOrganizationId(), invoice.getId()).stream()
                .map(Payment::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return invoice.getTotal().subtract(paid).max(BigDecimal.ZERO);
    }

    private Optional<Partner> partnerOf(Invoice invoice) {
        if (invoice.getPartnerId() == null) {
            return Optional.empty();
        }
        return partnerRepository.findByOrganizationIdAndId(invoice.getOrganizationId(), invoice.getPartnerId());
    }

    private static String emailOf(Partner partner) {
        if (partner == null) {
            return null;
        }
        String email = partner.getEmail();
        return email != null && !email.isBlank() && email.contains("@") ? email.trim() : null;
    }

    private static String numberOf(Invoice invoice) {
        return invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId().toString();
    }

    private static String money(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toString() : "0.00";
    }
}
