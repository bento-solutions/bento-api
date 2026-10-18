package com.bento.crm.portal.service;

import com.bento.crm.checklist.dto.ChecklistDtos;
import com.bento.crm.checklist.service.ChecklistService;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.portal.PortalPrincipal;
import com.bento.crm.portal.dto.PortalDtos;
import com.bento.crm.tax.dto.TaxDtos;
import com.bento.crm.tax.service.TaxCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a client sees in the portal. Every query is scoped by the signed-in client's organization
 * and partner from {@link PortalPrincipal}; ids in the URL only pick within that.
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    private final PortalAccountService accounts;
    private final ChecklistService checklists;
    private final TaxCalendarService taxCalendar;
    private final InvoiceRepository invoiceRepository;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final InvoiceTemplateService invoiceTemplateService;
    private final PdfDocumentService pdfDocumentService;
    private final StoredFileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbc;

    @Value("${app.payments.public-url:http://localhost:3000}")
    private String payUrl;

    public PortalDtos.Me me(PortalPrincipal p) {
        return accounts.me(p.organizationId(), p.portalUserId());
    }

    public PortalDtos.Home home(PortalPrincipal p) {
        PortalDtos.Me me = me(p);
        List<PortalDtos.Invoice> open = invoices(p).stream().filter(i -> i.owed().signum() > 0).toList();
        BigDecimal due = open.stream().map(PortalDtos.Invoice::owed).reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdue = open.stream().filter(i -> i.dueDate() != null && i.dueDate().isBefore(LocalDate.now())).count();
        int missing = checklists.forPartner(p.organizationId(), p.partnerId()).stream()
                .filter(c -> c.status().name().equals("OPEN")).mapToInt(ChecklistDtos.Summary::missing).sum();
        TaxDtos.Deadline next = taxCalendar.forPartner(p.organizationId(), p.partnerId(), LocalDate.now(), LocalDate.now().plusDays(120))
                .stream().filter(d -> d.status().name().equals("TODO")).findFirst().orElse(null);
        Integer inProgress = jdbc.queryForObject("SELECT count(*) FROM mission WHERE organization_id = ? AND partner_id = ?"
                + " AND deleted_at IS NULL AND status IN ('PLANNED', 'IN_PROGRESS')", Integer.class, p.organizationId(), p.partnerId());
        return new PortalDtos.Home(me, missing, open.size(), due, (int) overdue,
                next == null ? null : next.dueDate(), next == null ? null : next.kind().name(), inProgress == null ? 0 : inProgress);
    }

    // Invoices

    public List<PortalDtos.Invoice> invoices(PortalPrincipal p) {
        return jdbc.query("""
                SELECT i.id, i.invoice_number, i.invoice_date, i.due_date, i.status, i.total, 'MAD' AS currency,
                       i.total - coalesce((SELECT sum(x.amount) FROM payment x WHERE x.invoice_id = i.id AND x.deleted_at IS NULL), 0) AS owed,
                       (SELECT l.token FROM payment_link l WHERE l.invoice_id = i.id AND l.status = 'OPEN'
                        ORDER BY l.created_at DESC LIMIT 1) AS pay_token
                FROM invoice i
                WHERE i.organization_id = ? AND i.partner_id = ? AND i.type = 'CUSTOMER' AND i.deleted_at IS NULL
                  AND i.status <> 'DRAFT'
                ORDER BY i.invoice_date DESC NULLS LAST, i.created_at DESC LIMIT 200
                """, this::invoice, p.organizationId(), p.partnerId());
    }

    public Map.Entry<String, byte[]> invoicePdf(PortalPrincipal p, UUID id) {
        Invoice inv = invoiceRepository.findByOrganizationIdAndId(p.organizationId(), id)
                .filter(i -> p.partnerId().equals(i.getPartnerId()) && i.getType() == Invoice.InvoiceType.CUSTOMER
                        && i.getStatus() != Invoice.Status.DRAFT && i.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        var partner = partnerRepository.findByOrganizationIdAndId(p.organizationId(), p.partnerId()).orElse(null);
        var org = organizationRepository.findById(p.organizationId()).orElse(null);
        byte[] pdf = pdfDocumentService.generateInvoicePdf(inv, partner, org, invoiceTemplateService.brandingFor(inv, partner));
        String name = "facture-" + (inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : inv.getId().toString().substring(0, 8)) + ".pdf";
        return Map.entry(name.replaceAll("[^A-Za-z0-9._-]", "_"), pdf);
    }

    // Checklists

    public List<ChecklistDtos.Summary> checklists(PortalPrincipal p) {
        return checklists.forPartner(p.organizationId(), p.partnerId());
    }

    public ChecklistDtos.Detail checklist(PortalPrincipal p, UUID id) {
        return checklists.forPartner(p.organizationId(), p.partnerId(), id);
    }

    public ChecklistDtos.Detail upload(PortalPrincipal p, UUID itemId, MultipartFile file) {
        return checklists.portalUpload(p.organizationId(), p.partnerId(), itemId, file);
    }

    /** A file the client sent for one of their checklist items. */
    public StoredFile checklistFile(PortalPrincipal p, UUID itemId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT i.file_id FROM document_request_item i JOIN document_request r ON r.id = i.request_id
                WHERE i.id = ? AND i.organization_id = ? AND r.partner_id = ? AND i.deleted_at IS NULL
                  AND r.deleted_at IS NULL AND i.file_id IS NOT NULL
                """, UUID.class, itemId, p.organizationId(), p.partnerId());
        return file(p, ids);
    }

    // Missions

    public List<PortalDtos.Mission> missions(PortalPrincipal p) {
        List<PortalDtos.Mission> out = new ArrayList<>();
        jdbc.query("""
                SELECT id, name, status, start_date, due_date FROM mission
                WHERE organization_id = ? AND partner_id = ? AND deleted_at IS NULL AND status <> 'CANCELLED'
                ORDER BY status = 'DONE', due_date NULLS LAST, created_at DESC
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            out.add(new PortalDtos.Mission(id, rs.getString("name"), rs.getString("status"),
                    rs.getObject("start_date", LocalDate.class), rs.getObject("due_date", LocalDate.class), deliverables(p, id)));
        }, p.organizationId(), p.partnerId());
        return out;
    }

    private List<PortalDtos.Deliverable> deliverables(PortalPrincipal p, UUID missionId) {
        return jdbc.query("""
                SELECT d.id, d.title, d.due_date, d.status, d.delivered_at, d.file_id, f.file_name
                FROM mission_deliverable d LEFT JOIN stored_file f ON f.id = d.file_id
                WHERE d.organization_id = ? AND d.mission_id = ? AND d.deleted_at IS NULL AND d.visible_to_client
                ORDER BY d.due_date NULLS LAST, d.created_at
                """, (rs, i) -> {
            java.sql.Timestamp t = rs.getTimestamp("delivered_at");
            boolean delivered = "DELIVERED".equals(rs.getString("status"));
            // A file is shown only once the work is delivered, never a draft in progress.
            boolean hasFile = delivered && rs.getObject("file_id") != null;
            return new PortalDtos.Deliverable(rs.getObject("id", UUID.class), rs.getString("title"),
                    rs.getObject("due_date", LocalDate.class), rs.getString("status"), t == null ? null : t.toInstant(),
                    hasFile, hasFile ? rs.getString("file_name") : null);
        }, p.organizationId(), missionId);
    }

    public StoredFile deliverableFile(PortalPrincipal p, UUID deliverableId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT d.file_id FROM mission_deliverable d JOIN mission m ON m.id = d.mission_id
                WHERE d.id = ? AND d.organization_id = ? AND m.partner_id = ? AND d.deleted_at IS NULL AND m.deleted_at IS NULL
                  AND d.visible_to_client AND d.status = 'DELIVERED' AND d.file_id IS NOT NULL
                """, UUID.class, deliverableId, p.organizationId(), p.partnerId());
        return file(p, ids);
    }

    public Resource open(StoredFile f) {
        return fileStorageService.open(f);
    }

    // Tax calendar

    public List<PortalDtos.TaxDeadline> taxDeadlines(PortalPrincipal p) {
        return taxCalendar.forPartner(p.organizationId(), p.partnerId(), LocalDate.now().minusDays(90), LocalDate.now().plusDays(365))
                .stream().map(d -> new PortalDtos.TaxDeadline(d.id(), d.kind().name(), d.periodLabel(), d.dueDate(),
                        d.status().name(), d.overdue())).toList();
    }

    private StoredFile file(PortalPrincipal p, List<UUID> ids) {
        if (ids.isEmpty()) throw new ResourceNotFoundException("File not found");
        return fileRepository.findByOrganizationIdAndId(p.organizationId(), ids.get(0))
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
    }

    private PortalDtos.Invoice invoice(ResultSet rs, int i) throws SQLException {
        String token = rs.getString("pay_token");
        BigDecimal owed = rs.getBigDecimal("owed");
        return new PortalDtos.Invoice(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("invoice_date", LocalDate.class), rs.getObject("due_date", LocalDate.class), rs.getString("status"),
                rs.getBigDecimal("total"), owed == null || owed.signum() < 0 ? BigDecimal.ZERO : owed,
                rs.getString("currency") == null ? "MAD" : rs.getString("currency"),
                token == null || owed == null || owed.signum() <= 0 ? null : payUrl + "/pay/" + token);
    }
}
