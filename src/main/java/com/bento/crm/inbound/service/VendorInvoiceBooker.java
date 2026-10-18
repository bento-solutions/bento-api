package com.bento.crm.inbound.service;

import com.bento.crm.ai.AiProperties;
import com.bento.crm.ai.DocumentExtraction;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.model.InboundMailbox;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceTemplateRepository;
import com.bento.crm.invoice.service.InvoiceService;
import com.bento.crm.partner.dto.CreatePartnerRequest;
import com.bento.crm.partner.model.CustomerCard;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.CustomerCardRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.partner.service.PartnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether an invoice read from a document can be booked without a person, and books it:
 * finds or creates the supplier, creates the VENDOR invoice and attaches the file to it.
 */
@Service
@RequiredArgsConstructor
public class VendorInvoiceBooker {

    static final int DEFAULT_TERMS_DAYS = 30;
    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    private final InvoiceService invoiceService;
    private final PartnerService partnerService;
    private final PartnerRepository partnerRepository;
    private final CustomerCardRepository customerCardRepository;
    private final InvoiceTemplateRepository templateRepository;
    private final StoredFileRepository fileRepository;
    private final AiProperties aiProperties;
    private final JdbcTemplate jdbc;

    public record Decision(boolean book, boolean duplicate, UUID partnerId, List<String> reasons) {}

    /** The fields needed to book, as read by the AI or corrected by a person. */
    public record Fields(UUID partnerId, String supplierName, String supplierIce, String supplierIf, String supplierEmail,
                         String supplierPhone, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate,
                         BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<DocumentExtraction.Line> lines) {

        public static Fields of(DocumentExtraction e, UUID partnerId) {
            return new Fields(partnerId, e.supplierName(), e.supplierIce(), e.supplierIf(), e.supplierEmail(),
                    e.supplierPhone(), e.invoiceNumber(), e.invoiceDate(), e.dueDate(), e.subtotal(), e.tax(), e.total(),
                    e.lines());
        }
    }

    public Decision decide(UUID orgId, InboundDocument doc, InboundMailbox mailbox, DocumentExtraction e) {
        List<String> reasons = new ArrayList<>();
        UUID partnerId = matchSupplier(orgId, e.supplierIce(), e.supplierName()).orElse(null);

        String type = e.documentType() == null ? "other" : e.documentType();
        if (!type.equals("vendor_invoice") && !type.equals("receipt")) {
            reasons.add(switch (type) {
                case "credit_note" -> "This is a credit note";
                case "quote" -> "This is a quote, not an invoice";
                default -> "This does not look like a supplier invoice";
            });
        }
        if (e.total() == null || e.total().signum() <= 0) {
            reasons.add("The total could not be read");
        }
        if (e.subtotal() != null && e.tax() != null && e.total() != null
                && e.subtotal().add(e.tax()).subtract(e.total()).abs().compareTo(TOLERANCE) > 0) {
            reasons.add("HT + TVA does not equal TTC");
        }
        if (e.invoiceNumber() == null) {
            reasons.add("No invoice number");
        }
        if (e.invoiceDate() == null) {
            reasons.add("No invoice date");
        }
        if (e.currency() != null && !e.currency().equalsIgnoreCase("MAD") && !e.currency().equalsIgnoreCase("DH")
                && !e.currency().equalsIgnoreCase("DHS")) {
            reasons.add("Amounts are in " + e.currency());
        }
        String ownIce = templateRepository.findByOrganizationId(orgId).map(t -> digits(t.getIce())).orElse(null);
        if (ownIce != null && !ownIce.isEmpty() && e.buyerIce() != null && !e.buyerIce().isEmpty() && !ownIce.equals(e.buyerIce())) {
            reasons.add("The invoice is addressed to another company (ICE " + e.buyerIce() + ")");
        }
        if (e.confidence() == null || e.confidence() < aiProperties.getAutoBookConfidence()) {
            reasons.add("The AI is not sure enough of what it read");
        }
        if (mailbox != null && !mailbox.isAutoBook()) {
            reasons.add("Automatic booking is off for this inbox");
        }
        if (!trustedSender(orgId, doc, mailbox, partnerId)) {
            String sender = (doc.getSender() == null ? "" : doc.getSender()).trim();
            // A known supplier's address is easy to forge: say why it was not trusted.
            reasons.add(partnerId != null ? "Sender not verified " + sender : "Unknown sender " + sender);
        }
        boolean duplicate = partnerId != null && e.invoiceNumber() != null && invoiceExists(orgId, partnerId, e.invoiceNumber());
        if (duplicate) {
            reasons.add("Invoice " + e.invoiceNumber() + " from this supplier is already booked");
        }
        return new Decision(reasons.isEmpty(), duplicate, partnerId, reasons);
    }

    /** Books the invoice and returns it. The caller has checked the fields. */
    public Invoice book(UUID orgId, InboundDocument doc, Fields f) {
        if (f.total() == null || f.total().signum() <= 0) {
            throw new IllegalArgumentException("The total is required");
        }
        UUID partnerId = f.partnerId() != null ? f.partnerId() : matchSupplier(orgId, f.supplierIce(), f.supplierName())
                .orElseGet(() -> createSupplier(orgId, f));
        Partner partner = partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown supplier"));
        if (f.invoiceNumber() != null && invoiceExists(orgId, partnerId, f.invoiceNumber())) {
            throw new IllegalStateException("Invoice " + f.invoiceNumber() + " from this supplier is already booked");
        }

        LocalDate invoiceDate = f.invoiceDate() != null ? f.invoiceDate() : LocalDate.now();
        LocalDate dueDate = f.dueDate() != null && !f.dueDate().isBefore(invoiceDate) ? f.dueDate()
                : invoiceDate.plusDays(DEFAULT_TERMS_DAYS);
        BigDecimal tax = f.tax() != null ? f.tax()
                : f.subtotal() != null ? f.total().subtract(f.subtotal()) : null;
        BigDecimal subtotal = f.subtotal() != null ? f.subtotal() : tax != null ? f.total().subtract(tax) : null;
        if (f.subtotal() != null && f.tax() != null
                && f.subtotal().add(f.tax()).subtract(f.total()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            // Never book a total other than the one the person typed: they correct HT, TVA or TTC.
            throw new IllegalArgumentException("HT + TVA (" + f.subtotal().add(f.tax()).setScale(2, RoundingMode.HALF_UP)
                    + ") is not the TTC entered (" + f.total().setScale(2, RoundingMode.HALF_UP) + "): correct one of them");
        }
        if (subtotal == null) {
            // Only a total: book it as the amount due with no VAT breakdown rather than invent one.
            subtotal = f.total();
            tax = BigDecimal.ZERO;
        }

        CreateInvoiceRequest r = new CreateInvoiceRequest();
        r.setType(Invoice.InvoiceType.VENDOR);
        r.setPartnerId(partnerId);
        r.setStatus(Invoice.Status.SENT);
        r.setInvoiceNumber(f.invoiceNumber() == null ? null : f.invoiceNumber().trim());
        r.setInvoiceDate(invoiceDate);
        r.setDueDate(dueDate);
        r.setCustomerName(partner.getCompanyName() != null && !partner.getCompanyName().isBlank()
                ? partner.getCompanyName() : partner.getName());
        r.setVatNumber(f.supplierIce());
        r.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        r.setTax(tax.setScale(2, RoundingMode.HALF_UP));
        r.setTotal(subtotal.add(tax).setScale(2, RoundingMode.HALF_UP));
        r.setLines(lines(f.lines()));
        Invoice invoice = invoiceService.createInvoice(orgId, r);

        fileRepository.findById(doc.getFileId()).ifPresent(file -> {
            file.setOwnerEntityType("INVOICE");
            file.setOwnerEntityId(invoice.getId());
            file.setPartnerId(partnerId);
            fileRepository.save(file);
        });
        return invoice;
    }

    Optional<UUID> matchSupplier(UUID orgId, String ice, String name) {
        String iceDigits = digits(ice);
        if (iceDigits != null && iceDigits.length() >= 9) {
            List<UUID> byIce = jdbc.queryForList("SELECT p.id FROM partner p JOIN customer_card c ON c.partner_id = p.id"
                    + " WHERE p.organization_id = ? AND p.deleted_at IS NULL AND regexp_replace(coalesce(c.ice, ''), '\\D', '', 'g') = ?"
                    + " ORDER BY (p.type = 'VENDOR') DESC, p.created_at LIMIT 1", UUID.class, orgId, iceDigits);
            if (!byIce.isEmpty()) {
                return Optional.of(byIce.get(0));
            }
        }
        String n = normalizeName(name);
        if (n != null && n.length() >= 3) {
            List<UUID> byName = jdbc.queryForList("SELECT id FROM partner WHERE organization_id = ? AND deleted_at IS NULL"
                    + " AND type = 'VENDOR' AND (? IN (lower(trim(name)), lower(trim(coalesce(company_name, '')))))"
                    + " ORDER BY created_at LIMIT 1", UUID.class, orgId, n);
            if (!byName.isEmpty()) {
                return Optional.of(byName.get(0));
            }
        }
        return Optional.empty();
    }

    private UUID createSupplier(UUID orgId, Fields f) {
        String name = f.supplierName() == null || f.supplierName().isBlank() ? "Fournisseur " + (f.supplierIce() == null ? "inconnu" : f.supplierIce())
                : f.supplierName().trim();
        CreatePartnerRequest req = new CreatePartnerRequest();
        req.setType("VENDOR");
        req.setName(name);
        req.setCompanyName(name);
        req.setEmail(f.supplierEmail());
        req.setPhone(f.supplierPhone());
        req.setSource("INBOUND");
        req.setCountry("Morocco");
        Partner p = partnerService.createPartner(req);
        if (f.supplierIce() != null || f.supplierIf() != null) {
            CustomerCard card = new CustomerCard();
            card.setOrganizationId(orgId);
            card.setPartnerId(p.getId());
            card.setName(name);
            card.setIce(f.supplierIce());
            card.setIfNumber(f.supplierIf());
            card.setCorporateEmail(f.supplierEmail());
            card.setMainPhone(f.supplierPhone());
            card.setRecordType(CustomerCard.RecordType.ORGANIZATION);
            customerCardRepository.save(card);
        }
        return p.getId();
    }

    private boolean trustedSender(UUID orgId, InboundDocument doc, InboundMailbox mailbox, UUID partnerId) {
        if (doc.getChannel() == InboundDocument.Channel.UPLOAD) {
            return true;
        }
        if (doc.getChannel() == InboundDocument.Channel.WHATSAPP) {
            // A known contact's WhatsApp number was matched when the document was filed.
            return doc.getPartnerId() != null;
        }
        String sender = doc.getSender() == null ? null : doc.getSender().trim().toLowerCase(Locale.ROOT);
        if (sender == null || !sender.contains("@") || !doc.isSenderVerified()) {
            // An unauthenticated From header can be forged: such an email always goes to review.
            return false;
        }
        String senderDomain = sender.substring(sender.indexOf('@'));
        if (mailbox != null) {
            for (String allowed : mailbox.getAllowedSenders()) {
                String a = allowed.trim().toLowerCase(Locale.ROOT);
                if (a.equals(sender) || (a.startsWith("@") && a.equals(senderDomain))) {
                    return true;
                }
            }
        }
        if (partnerId != null) {
            // The supplier's own address, or another address at its domain (not a free-mail one).
            Integer known = jdbc.queryForObject("SELECT count(*) FROM partner p LEFT JOIN customer_card c ON c.partner_id = p.id"
                    + " WHERE p.id = ? AND p.organization_id = ? AND (lower(p.email) = ? OR lower(c.corporate_email) = ?"
                    + " OR (? NOT IN ('@gmail.com', '@yahoo.fr', '@yahoo.com', '@hotmail.com', '@hotmail.fr', '@outlook.com', '@outlook.fr', '@live.fr', '@icloud.com')"
                    + " AND (lower(p.email) LIKE ? OR lower(c.corporate_email) LIKE ?)))",
                    Integer.class, partnerId, orgId, sender, sender, senderDomain, "%" + senderDomain, "%" + senderDomain);
            return known != null && known > 0;
        }
        return false;
    }

    private boolean invoiceExists(UUID orgId, UUID partnerId, String number) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM invoice WHERE organization_id = ? AND partner_id = ?"
                + " AND type = 'VENDOR' AND deleted_at IS NULL AND lower(trim(invoice_number)) = lower(trim(?))",
                Integer.class, orgId, partnerId, number);
        return n != null && n > 0;
    }

    private static List<Map<String, Object>> lines(List<DocumentExtraction.Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (DocumentExtraction.Line l : lines) {
            if (l.description() == null && l.unitPrice() == null && l.total() == null) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("description", l.description() == null ? "" : l.description());
            BigDecimal qty = l.quantity() != null && l.quantity().signum() > 0 ? l.quantity() : BigDecimal.ONE;
            BigDecimal unit = l.unitPrice() != null ? l.unitPrice()
                    : l.total() != null ? l.total().divide(qty, 2, RoundingMode.HALF_UP) : null;
            m.put("qty", qty);
            if (unit != null) {
                m.put("unitPrice", unit);
            }
            if (l.vatRate() != null) {
                BigDecimal rate = l.vatRate().compareTo(BigDecimal.ONE) > 0
                        ? l.vatRate().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP) : l.vatRate();
                m.put("taxRate", rate);
            }
            out.add(m);
        }
        return out;
    }

    static String digits(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    static String normalizeName(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return n.isEmpty() ? null : n;
    }
}
