package com.bento.crm.delivery.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.common.service.BusinessNumberService;
import com.bento.crm.deal.model.Deal;
import com.bento.crm.deal.model.DealOrderLine;
import com.bento.crm.deal.service.DealService;
import com.bento.crm.delivery.dto.DeliveryNoteDtos;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.service.InvoiceService;
import com.bento.crm.mission.service.MissionDealService;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivery notes: drafted from a deal's order lines, numbered BL-YYYY-NNNN when delivered,
 * then invoiced from the deal. A delivery note never posts; the cut-off assistant proposes an FAE
 * for a delivered note whose invoice is not out by the date.
 */
@Service
@RequiredArgsConstructor
public class DeliveryNoteService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "DELIVERED", "INVOICED", "CANCELLED");

    private final DealService dealService;
    private final InvoiceService invoiceService;
    private final BusinessNumberService numbers;
    private final PdfDocumentService pdf;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    // Reading

    private static final String SELECT = """
            SELECT n.*, CAST(n.lines AS text) AS lines_json, COALESCE(NULLIF(p.company_name, ''), p.name) AS partner_name,
                   d.title AS deal_title, CASE WHEN i.deleted_at IS NULL THEN i.invoice_number END AS invoice_number
            FROM delivery_note n JOIN partner p ON p.id = n.partner_id
            LEFT JOIN deal d ON d.id = n.deal_id LEFT JOIN invoice i ON i.id = n.invoice_id
            """;

    @Transactional(readOnly = true)
    public PageResponse<DeliveryNoteDtos.DeliveryNote> list(UUID partnerId, UUID dealId, UUID missionId, String status,
                                                            int page, int size) {
        UUID orgId = org();
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE n.organization_id = ? AND n.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (partnerId != null) { where.append(" AND n.partner_id = ?"); args.add(partnerId); }
        if (dealId != null) { where.append(" AND n.deal_id = ?"); args.add(dealId); }
        if (missionId != null) { where.append(" AND n.mission_id = ?"); args.add(missionId); }
        if (status != null && !status.isBlank()) { where.append(" AND n.status = ?"); args.add(status(status)); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_note n" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<DeliveryNoteDtos.DeliveryNote> content = jdbc.query(SELECT + where
                + " ORDER BY n.note_date DESC, n.created_at DESC LIMIT ? OFFSET ?", this::row, pageArgs.toArray());
        return PageResponse.fromPage(new PageImpl<>(content, PageRequest.of(p, s), total == null ? 0 : total));
    }

    @Transactional(readOnly = true)
    public DeliveryNoteDtos.DeliveryNote get(UUID id) {
        return find(org(), id);
    }

    // Writing

    @Transactional
    public DeliveryNoteDtos.DeliveryNote create(DeliveryNoteDtos.DeliveryNoteRequest r) {
        UUID orgId = org();
        if (r == null) throw new IllegalArgumentException("The delivery note is required");
        UUID partnerId = r.partnerId();
        if (r.dealId() != null) {
            Deal deal = dealService.getDeal(r.dealId());
            if (partnerId == null) partnerId = deal.getPartnerId();
        }
        if (partnerId == null) throw new IllegalArgumentException("Choose the customer");
        checkLinks(orgId, partnerId, r.missionId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_note (id, organization_id, partner_id, deal_id, mission_id, note_date, status, lines, notes,
                    created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', CAST(? AS jsonb), ?, ?, ?)
                """, id, orgId, partnerId, r.dealId(), r.missionId(), r.date() == null ? LocalDate.now() : r.date(),
                json(lines(r.lines())), blank(r.notes()), CurrentUser.idOrNull(), CurrentUser.idOrNull());
        return find(orgId, id);
    }

    @Transactional
    public DeliveryNoteDtos.DeliveryNote update(UUID id, DeliveryNoteDtos.DeliveryNoteRequest r) {
        UUID orgId = org();
        DeliveryNoteDtos.DeliveryNote n = draft(orgId, id);
        if (r == null) return n;
        UUID partnerId = r.partnerId() != null ? r.partnerId() : n.partnerId();
        UUID dealId = r.dealId() != null ? r.dealId() : n.dealId();
        if (r.dealId() != null) dealService.getDeal(r.dealId());
        UUID missionId = r.missionId() != null ? r.missionId() : n.missionId();
        checkLinks(orgId, partnerId, r.missionId());
        jdbc.update("""
                UPDATE delivery_note SET partner_id = ?, deal_id = ?, mission_id = ?, note_date = ?, lines = CAST(? AS jsonb),
                    notes = ?, updated_at = now(), updated_by = ? WHERE organization_id = ? AND id = ?
                """, partnerId, dealId, missionId, r.date() != null ? r.date() : n.date(),
                json(r.lines() != null ? lines(r.lines()) : n.lines()), r.notes() != null ? blank(r.notes()) : n.notes(),
                CurrentUser.idOrNull(), orgId, id);
        return find(orgId, id);
    }

    @Transactional
    public void delete(UUID id) {
        UUID orgId = org();
        draft(orgId, id);
        jdbc.update("UPDATE delivery_note SET deleted_at = now(), updated_at = now() WHERE organization_id = ? AND id = ?",
                orgId, id);
    }

    /** A DRAFT note from the deal's order lines, or one line of the deal title when it has none. */
    @Transactional
    public DeliveryNoteDtos.DeliveryNote createFromDeal(UUID dealId) {
        Deal deal = dealService.getDeal(dealId);
        List<DeliveryNoteDtos.Line> lines = new ArrayList<>();
        for (DealOrderLine l : deal.getOrderLines()) {
            String desc = firstNonBlank(l.getDescription(), l.getProduct());
            lines.add(new DeliveryNoteDtos.Line(desc == null ? deal.getTitle() : desc, l.getQty() == null ? BigDecimal.ONE : l.getQty(),
                    null, null));
        }
        if (lines.isEmpty()) lines.add(new DeliveryNoteDtos.Line(deal.getTitle(), BigDecimal.ONE, null, null));
        UUID missionId = jdbc.queryForList("SELECT id FROM mission WHERE organization_id = ? AND deal_id = ? AND deleted_at IS NULL"
                + " ORDER BY created_at LIMIT 1", UUID.class, deal.getOrganizationId(), dealId).stream().findFirst().orElse(null);
        return create(new DeliveryNoteDtos.DeliveryNoteRequest(deal.getPartnerId(), dealId, missionId, LocalDate.now(), lines,
                null));
    }

    /**
     * DELIVERED with its number. Unless the deal is already invoiced, the deal's delivery date is
     * set and it moves to AWAITING_INVOICING.
     */
    @Transactional
    public DeliveryNoteDtos.DeliveryNote deliver(UUID id, DeliveryNoteDtos.DeliverRequest r) {
        UUID orgId = org();
        DeliveryNoteDtos.DeliveryNote n = draft(orgId, id);
        LocalDate date = r != null && r.date() != null ? r.date() : n.date();
        String number = n.number() != null ? n.number() : numbers.next(orgId, "BL");
        String signedBy = r == null || r.signedBy() == null || r.signedBy().isBlank() ? null : cut(r.signedBy().trim(), 160);
        jdbc.update("""
                UPDATE delivery_note SET status = 'DELIVERED', number = ?, note_date = ?, delivered_at = now(), signed_by = ?,
                    updated_at = now(), updated_by = ? WHERE organization_id = ? AND id = ?
                """, number, date, signedBy, CurrentUser.idOrNull(), orgId, id);
        if (n.dealId() != null) dealService.markDelivered(n.dealId(), date);
        return find(orgId, id);
    }

    /**
     * A DRAFT customer invoice from the deal, with its order lines and prices (or the deal amount),
     * deducting the deal's open advances. The note becomes INVOICED.
     */
    @Transactional
    public Invoice invoice(UUID id) {
        UUID orgId = org();
        DeliveryNoteDtos.DeliveryNote n = find(orgId, id);
        if (!"DELIVERED".equals(n.status())) {
            throw new IllegalArgumentException("Only a delivered note can be invoiced");
        }
        if (n.dealId() == null) throw new IllegalArgumentException("Link the delivery note to a deal to invoice it");
        Deal deal = dealService.getDeal(n.dealId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (DealOrderLine l : deal.getOrderLines()) {
            BigDecimal qty = l.getQty() == null || l.getQty().signum() == 0 ? BigDecimal.ONE : l.getQty();
            BigDecimal price = l.getUnitPrice();
            BigDecimal total = l.getTotal();
            if (total != null && (price == null || qty.multiply(price).compareTo(total) != 0)) {
                price = total.divide(qty, 2, RoundingMode.HALF_UP);
            }
            if (price == null) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            String desc = firstNonBlank(l.getDescription(), l.getProduct());
            line.put("description", cut(desc == null ? deal.getTitle() : desc, 255));
            line.put("qty", qty);
            line.put("unitPrice", price);
            lines.add(line);
        }
        if (lines.isEmpty()) {
            BigDecimal base = jdbc.queryForObject("SELECT " + MissionDealService.DEAL_BASE + " FROM deal d WHERE d.id = ?",
                    BigDecimal.class, deal.getId());
            if (base == null || base.signum() <= 0) throw new IllegalArgumentException("The deal has no amount to invoice");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("description", cut(deal.getTitle(), 255));
            line.put("qty", BigDecimal.ONE);
            line.put("unitPrice", base.setScale(2, RoundingMode.HALF_UP));
            lines.add(line);
        }
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setType(Invoice.InvoiceType.CUSTOMER);
        req.setPartnerId(deal.getPartnerId());
        req.setDealId(deal.getId());
        req.setStatus(Invoice.Status.DRAFT);
        req.setInvoiceDate(LocalDate.now());
        req.setDueDate(LocalDate.now().plusDays(30));
        req.setDeliveryAddress(deal.getDeliveryAddress());
        req.setLines(lines);
        req.setDeductAdvances(true);
        Invoice inv = invoiceService.createInvoice(orgId, req);
        entityManager.flush(); // the invoice row must exist before the note points at it
        jdbc.update("UPDATE delivery_note SET status = 'INVOICED', invoice_id = ?, updated_at = now(), updated_by = ?"
                + " WHERE organization_id = ? AND id = ?", inv.getId(), CurrentUser.idOrNull(), orgId, id);
        return inv;
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID id) {
        UUID orgId = org();
        DeliveryNoteDtos.DeliveryNote n = find(orgId, id);
        String reference = null;
        String address = null;
        if (n.dealId() != null) {
            List<Map<String, Object>> d = jdbc.queryForList("SELECT order_number, delivery_address FROM deal WHERE id = ?", n.dealId());
            if (!d.isEmpty()) {
                reference = (String) d.get(0).get("order_number");
                address = (String) d.get(0).get("delivery_address");
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (DeliveryNoteDtos.Line l : n.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", l.description());
            m.put("qty", l.qty());
            m.put("unit", l.unit());
            lines.add(m);
        }
        return pdf.generateDeliveryNotePdf(new PdfDocumentService.DeliveryNotePdf(n.number(), n.date(), n.status(), reference,
                        address, lines, n.notes(), n.signedBy()),
                partnerRepository.findByOrganizationIdAndId(orgId, n.partnerId()).orElse(null),
                organizationRepository.findById(orgId).orElse(null));
    }

    // Helpers

    private DeliveryNoteDtos.DeliveryNote find(UUID orgId, UUID id) {
        List<DeliveryNoteDtos.DeliveryNote> rows = jdbc.query(SELECT + " WHERE n.organization_id = ? AND n.id = ?"
                + " AND n.deleted_at IS NULL", this::row, orgId, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Delivery note not found");
        return rows.get(0);
    }

    private DeliveryNoteDtos.DeliveryNote draft(UUID orgId, UUID id) {
        DeliveryNoteDtos.DeliveryNote n = find(orgId, id);
        if (!"DRAFT".equals(n.status())) throw new IllegalArgumentException("Only a draft delivery note can be changed");
        return n;
    }

    private void checkLinks(UUID orgId, UUID partnerId, UUID missionId) {
        partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        if (missionId != null) {
            Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM mission WHERE organization_id = ? AND id = ?"
                    + " AND deleted_at IS NULL)", Boolean.class, orgId, missionId);
            if (!Boolean.TRUE.equals(ok)) throw new ResourceNotFoundException("Mission not found");
        }
    }

    private static List<DeliveryNoteDtos.Line> lines(List<DeliveryNoteDtos.Line> in) {
        List<DeliveryNoteDtos.Line> out = new ArrayList<>();
        if (in == null) return out;
        int n = 0;
        for (DeliveryNoteDtos.Line l : in) {
            n++;
            if (l == null || l.description() == null || l.description().isBlank()) {
                throw new IllegalArgumentException("Line " + n + ": describe what is delivered");
            }
            BigDecimal qty = l.qty() == null ? BigDecimal.ONE : l.qty();
            if (qty.signum() <= 0) throw new IllegalArgumentException("Line " + n + ": the quantity must be positive");
            out.add(new DeliveryNoteDtos.Line(cut(l.description().trim(), 500), qty,
                    l.unit() == null || l.unit().isBlank() ? null : cut(l.unit().trim(), 20), l.productId()));
        }
        return out;
    }

    private String json(List<DeliveryNoteDtos.Line> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid lines");
        }
    }

    private static String status(String s) {
        String v = s.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(v)) throw new IllegalArgumentException("The status is DRAFT, DELIVERED, INVOICED or CANCELLED");
        return v;
    }

    private DeliveryNoteDtos.DeliveryNote row(ResultSet rs, int i) throws SQLException {
        List<DeliveryNoteDtos.Line> lines;
        try {
            String j = rs.getString("lines_json");
            lines = j == null ? List.of() : objectMapper.readValue(j, new TypeReference<List<DeliveryNoteDtos.Line>>() {});
        } catch (Exception e) {
            lines = List.of();
        }
        Timestamp delivered = rs.getTimestamp("delivered_at");
        Timestamp created = rs.getTimestamp("created_at");
        String invoiceNumber = rs.getString("invoice_number");
        return new DeliveryNoteDtos.DeliveryNote(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("partner_id", UUID.class), rs.getString("partner_name"), rs.getObject("deal_id", UUID.class),
                rs.getString("deal_title"), rs.getObject("mission_id", UUID.class), rs.getObject("note_date", LocalDate.class),
                rs.getString("status"), lines, rs.getString("notes"), rs.getObject("invoice_id", UUID.class), invoiceNumber,
                delivered == null ? null : delivered.toInstant(), rs.getString("signed_by"),
                created == null ? null : created.toInstant());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
