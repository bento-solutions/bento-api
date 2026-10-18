package com.bento.crm.mission.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.invoice.dto.CreateInvoiceRequest;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.service.InvoiceService;
import com.bento.crm.mission.dto.MissionDtos;
import com.bento.crm.mission.model.Disbursement;
import com.bento.crm.mission.model.Mission;
import com.bento.crm.mission.model.MissionDeliverable;
import com.bento.crm.mission.repository.DisbursementRepository;
import com.bento.crm.mission.repository.MissionDeliverableRepository;
import com.bento.crm.mission.repository.MissionRepository;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client missions, what they deliver, and the costs paid for the client that are billed back. */
@Service
@RequiredArgsConstructor
public class MissionService {

    private static final List<BigDecimal> VAT_RATES = List.of(new BigDecimal("0"), new BigDecimal("0.07"),
            new BigDecimal("0.10"), new BigDecimal("0.14"), new BigDecimal("0.20"));

    private final MissionRepository missionRepository;
    private final MissionDeliverableRepository deliverableRepository;
    private final DisbursementRepository disbursementRepository;
    private final PartnerRepository partnerRepository;
    private final StoredFileRepository fileRepository;
    private final InvoiceService invoiceService;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;

    /** Published when a deliverable a client can see is delivered, so the portal can tell them. */
    public record DeliverableDelivered(UUID organizationId, UUID partnerId, UUID missionId, String missionName,
                                       String title) {}

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    // Missions

    private static final String SUMMARY = """
            SELECT m.*, p.name AS partner_name, u.display_name AS user_name,
                   (SELECT count(*) FROM mission_deliverable d WHERE d.mission_id = m.id AND d.deleted_at IS NULL) AS n_deliverables,
                   (SELECT count(*) FROM mission_deliverable d WHERE d.mission_id = m.id AND d.deleted_at IS NULL
                      AND d.status = 'DELIVERED') AS n_delivered,
                   (SELECT COALESCE(SUM(x.amount), 0) FROM disbursement x LEFT JOIN invoice i ON i.id = x.invoice_id
                     WHERE x.mission_id = m.id AND x.deleted_at IS NULL AND x.rebillable
                       AND (x.invoice_id IS NULL OR i.deleted_at IS NOT NULL)) AS unbilled
            FROM mission m JOIN partner p ON p.id = m.partner_id LEFT JOIN app_user u ON u.id = m.assigned_user_id
            """;

    public List<MissionDtos.Summary> list(UUID partnerId, Mission.Status status) {
        StringBuilder sql = new StringBuilder(SUMMARY + " WHERE m.organization_id = ? AND m.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(org()));
        if (partnerId != null) { sql.append(" AND m.partner_id = ?"); args.add(partnerId); }
        if (status != null) { sql.append(" AND m.status = ?"); args.add(status.name()); }
        sql.append(" ORDER BY m.due_date NULLS LAST, m.created_at DESC");
        return jdbc.query(sql.toString(), MissionService::summary, args.toArray());
    }

    public List<MissionDtos.Summary> forPartner(UUID orgId, UUID partnerId) {
        return jdbc.query(SUMMARY + " WHERE m.organization_id = ? AND m.partner_id = ? AND m.deleted_at IS NULL"
                + " AND m.status <> 'CANCELLED' ORDER BY m.due_date NULLS LAST", MissionService::summary, orgId, partnerId);
    }

    public MissionDtos.Detail get(UUID id) {
        Mission m = mission(id);
        MissionDtos.Summary s = jdbc.query(SUMMARY + " WHERE m.id = ?", MissionService::summary, id).get(0);
        return new MissionDtos.Detail(s, m.getDescription(), deliverables(m.getOrganizationId(), id, false),
                disbursements(null, id, false));
    }

    public List<MissionDtos.Deliverable> deliverables(UUID orgId, UUID missionId, boolean clientOnly) {
        return jdbc.query("""
                SELECT d.*, f.file_name FROM mission_deliverable d LEFT JOIN stored_file f ON f.id = d.file_id
                WHERE d.organization_id = ? AND d.mission_id = ? AND d.deleted_at IS NULL
                """ + (clientOnly ? " AND d.visible_to_client" : "") + " ORDER BY d.due_date NULLS LAST, d.created_at",
                MissionService::deliverable, orgId, missionId);
    }

    @Transactional
    public MissionDtos.Detail create(MissionDtos.MissionRequest r) {
        Mission m = new Mission();
        m.setOrganizationId(org());
        apply(m, r, true);
        return get(missionRepository.saveAndFlush(m).getId());
    }

    @Transactional
    public MissionDtos.Detail update(UUID id, MissionDtos.MissionRequest r) {
        Mission m = mission(id);
        apply(m, r, false);
        missionRepository.saveAndFlush(m);
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        Mission m = mission(id);
        Long billed = jdbc.queryForObject("SELECT count(*) FROM disbursement WHERE mission_id = ? AND invoice_id IS NOT NULL"
                + " AND deleted_at IS NULL", Long.class, id);
        if (billed != null && billed > 0) {
            throw new IllegalStateException("Costs of this mission were invoiced: cancel it instead");
        }
        m.setDeletedAt(Instant.now());
        missionRepository.save(m);
        jdbc.update("UPDATE mission_deliverable SET deleted_at = now() WHERE mission_id = ? AND deleted_at IS NULL", id);
        jdbc.update("UPDATE disbursement SET mission_id = NULL WHERE mission_id = ?", id);
    }

    private void apply(Mission m, MissionDtos.MissionRequest r, boolean create) {
        if (create || r.partnerId() != null) {
            if (r.partnerId() == null) throw new IllegalArgumentException("Choose the client");
            partner(r.partnerId());
            m.setPartnerId(r.partnerId());
        }
        if (create || r.name() != null) {
            if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Name the mission");
            m.setName(cut(r.name().trim(), 160));
        }
        if (r.description() != null) m.setDescription(r.description().isBlank() ? null : cut(r.description(), 2000));
        if (r.status() != null) m.setStatus(r.status());
        if (r.startDate() != null) m.setStartDate(r.startDate());
        if (r.dueDate() != null) m.setDueDate(r.dueDate());
        if (m.getStartDate() != null && m.getDueDate() != null && m.getDueDate().isBefore(m.getStartDate())) {
            throw new IllegalArgumentException("The due date is before the start date");
        }
        if (r.feeAmount() != null) {
            if (r.feeAmount().signum() < 0) throw new IllegalArgumentException("The fee cannot be negative");
            m.setFeeAmount(r.feeAmount());
        }
        if (r.assignedUserId() != null) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ? AND organization_id = ?", Long.class,
                    r.assignedUserId(), org());
            if (n == null || n == 0) throw new IllegalArgumentException("Unknown team member");
            m.setAssignedUserId(r.assignedUserId());
        }
    }

    // Deliverables

    @Transactional
    public MissionDtos.Deliverable addDeliverable(UUID missionId, MissionDtos.DeliverableRequest r) {
        Mission m = mission(missionId);
        MissionDeliverable d = new MissionDeliverable();
        d.setOrganizationId(m.getOrganizationId());
        d.setMissionId(missionId);
        if (r.title() == null || r.title().isBlank()) throw new IllegalArgumentException("Name the deliverable");
        applyDeliverable(m, d, r);
        d = deliverableRepository.saveAndFlush(d);
        return deliverable(d.getId());
    }

    @Transactional
    public MissionDtos.Deliverable updateDeliverable(UUID id, MissionDtos.DeliverableRequest r) {
        MissionDeliverable d = deliverableRepository.find(org(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found"));
        applyDeliverable(mission(d.getMissionId()), d, r);
        deliverableRepository.saveAndFlush(d);
        return deliverable(id);
    }

    @Transactional
    public void deleteDeliverable(UUID id) {
        MissionDeliverable d = deliverableRepository.find(org(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found"));
        d.setDeletedAt(Instant.now());
        deliverableRepository.save(d);
    }

    private void applyDeliverable(Mission m, MissionDeliverable d, MissionDtos.DeliverableRequest r) {
        if (r.title() != null && !r.title().isBlank()) d.setTitle(cut(r.title().trim(), 160));
        if (r.dueDate() != null) d.setDueDate(r.dueDate());
        if (r.visibleToClient() != null) d.setVisibleToClient(r.visibleToClient());
        if (Boolean.TRUE.equals(r.removeFile())) d.setFileId(null);
        if (r.fileId() != null) {
            fileRepository.findById(r.fileId()).filter(f -> f.getOrganizationId().equals(d.getOrganizationId())
                    && f.getDeletedAt() == null).orElseThrow(() -> new ResourceNotFoundException("File not found"));
            d.setFileId(r.fileId());
        }
        if (r.status() != null && r.status() != d.getStatus()) {
            d.setStatus(r.status());
            d.setDeliveredAt(r.status() == MissionDeliverable.Status.DELIVERED ? Instant.now() : null);
            if (r.status() == MissionDeliverable.Status.DELIVERED && d.isVisibleToClient()) {
                events.publishEvent(new DeliverableDelivered(m.getOrganizationId(), m.getPartnerId(), m.getId(),
                        m.getName(), d.getTitle()));
            }
        }
    }

    private MissionDtos.Deliverable deliverable(UUID id) {
        return jdbc.query("SELECT d.*, f.file_name FROM mission_deliverable d LEFT JOIN stored_file f ON f.id = d.file_id"
                + " WHERE d.id = ?", MissionService::deliverable, id).get(0);
    }

    // Disbursements

    private static final String DISB = """
            SELECT x.*, p.name AS partner_name, m.name AS mission_name,
                   CASE WHEN i.deleted_at IS NULL THEN i.invoice_number END AS invoice_number,
                   CASE WHEN i.deleted_at IS NULL THEN x.invoice_id END AS live_invoice_id
            FROM disbursement x JOIN partner p ON p.id = x.partner_id LEFT JOIN mission m ON m.id = x.mission_id
            LEFT JOIN invoice i ON i.id = x.invoice_id
            """;

    public List<MissionDtos.DisbursementRow> disbursements(UUID partnerId, UUID missionId, boolean unbilledOnly) {
        StringBuilder sql = new StringBuilder(DISB + " WHERE x.organization_id = ? AND x.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(org()));
        if (partnerId != null) { sql.append(" AND x.partner_id = ?"); args.add(partnerId); }
        if (missionId != null) { sql.append(" AND x.mission_id = ?"); args.add(missionId); }
        if (unbilledOnly) sql.append(" AND x.rebillable AND (x.invoice_id IS NULL OR i.deleted_at IS NOT NULL)");
        sql.append(" ORDER BY x.spent_on DESC, x.created_at DESC");
        return jdbc.query(sql.toString(), MissionService::disbursement, args.toArray());
    }

    @Transactional
    public MissionDtos.DisbursementRow addDisbursement(MissionDtos.DisbursementRequest r) {
        Disbursement x = new Disbursement();
        x.setOrganizationId(org());
        applyDisbursement(x, r, true);
        return disbursement(disbursementRepository.saveAndFlush(x).getId());
    }

    @Transactional
    public MissionDtos.DisbursementRow updateDisbursement(UUID id, MissionDtos.DisbursementRequest r) {
        Disbursement x = billable(id);
        applyDisbursement(x, r, false);
        disbursementRepository.saveAndFlush(x);
        return disbursement(id);
    }

    @Transactional
    public void deleteDisbursement(UUID id) {
        Disbursement x = billable(id);
        x.setDeletedAt(Instant.now());
        disbursementRepository.save(x);
    }

    /** A disbursement on a live invoice is frozen: change the invoice instead. */
    private Disbursement billable(UUID id) {
        Disbursement x = disbursementRepository.find(org(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Disbursement not found"));
        if (x.getInvoiceId() != null && invoiceLive(x.getInvoiceId())) {
            throw new IllegalStateException("This cost is already invoiced");
        }
        return x;
    }

    private boolean invoiceLive(UUID invoiceId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM invoice WHERE id = ? AND deleted_at IS NULL", Long.class, invoiceId);
        return n != null && n > 0;
    }

    private void applyDisbursement(Disbursement x, MissionDtos.DisbursementRequest r, boolean create) {
        if (r.missionId() != null) {
            Mission m = mission(r.missionId());
            if (r.partnerId() != null && !r.partnerId().equals(m.getPartnerId())) {
                throw new IllegalArgumentException("The mission belongs to another client");
            }
            x.setMissionId(m.getId());
            x.setPartnerId(m.getPartnerId());
        } else if (create || r.partnerId() != null) {
            if (r.partnerId() == null) throw new IllegalArgumentException("Choose the client");
            partner(r.partnerId());
            x.setPartnerId(r.partnerId());
        }
        if (create || r.spentOn() != null) x.setSpentOn(r.spentOn() == null ? LocalDate.now() : r.spentOn());
        if (create || r.description() != null) {
            if (r.description() == null || r.description().isBlank()) throw new IllegalArgumentException("Describe the cost");
            x.setDescription(cut(r.description().trim(), 255));
        }
        if (create || r.amount() != null) {
            if (r.amount() == null || r.amount().signum() <= 0) throw new IllegalArgumentException("Enter the amount");
            x.setAmount(r.amount().setScale(2, RoundingMode.HALF_UP));
        }
        if (r.vatRate() != null) {
            BigDecimal rate = r.vatRate().compareTo(BigDecimal.ONE) > 0 ? r.vatRate().movePointLeft(2) : r.vatRate();
            if (VAT_RATES.stream().noneMatch(v -> v.compareTo(rate) == 0)) {
                throw new IllegalArgumentException("VAT is 0, 7, 10, 14 or 20%");
            }
            x.setVatRate(rate);
        }
        if (r.rebillable() != null) x.setRebillable(r.rebillable());
        if (r.receiptFileId() != null) {
            fileRepository.findById(r.receiptFileId()).filter(f -> f.getOrganizationId().equals(x.getOrganizationId()))
                    .orElseThrow(() -> new ResourceNotFoundException("File not found"));
            x.setReceiptFileId(r.receiptFileId());
        }
    }

    private MissionDtos.DisbursementRow disbursement(UUID id) {
        return jdbc.query(DISB + " WHERE x.id = ?", MissionService::disbursement, id).get(0);
    }

    /** Puts a client's unbilled costs on one draft invoice, at cost, one line each. */
    @Transactional
    public MissionDtos.BillResult bill(MissionDtos.BillRequest r) {
        UUID orgId = org();
        if (r.partnerId() == null) throw new IllegalArgumentException("Choose the client");
        partner(r.partnerId());
        List<MissionDtos.DisbursementRow> open = disbursements(r.partnerId(), null, true);
        if (r.disbursementIds() != null && !r.disbursementIds().isEmpty()) {
            open = open.stream().filter(d -> r.disbursementIds().contains(d.id())).toList();
            if (open.size() != r.disbursementIds().size()) {
                throw new IllegalArgumentException("Some of these costs are not this client's, not rebillable or already invoiced");
            }
        }
        if (open.isEmpty()) throw new IllegalStateException("Nothing to invoice for this client");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (MissionDtos.DisbursementRow d : open) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("description", cut("Débours : " + d.description() + " (" + d.spentOn().format(f) + ")", 255));
            line.put("qty", 1);
            line.put("unitPrice", d.amount());
            line.put("vatRate", d.vatRate());
            lines.add(line);
        }
        CreateInvoiceRequest req = new CreateInvoiceRequest();
        req.setType(Invoice.InvoiceType.CUSTOMER);
        req.setPartnerId(r.partnerId());
        req.setStatus(Invoice.Status.DRAFT);
        req.setInvoiceDate(LocalDate.now());
        req.setDueDate(LocalDate.now().plusDays(30));
        req.setLines(lines);
        Invoice inv = invoiceService.createInvoice(orgId, req);
        missionRepository.flush(); // the invoice row must exist before the JDBC update points at it
        for (MissionDtos.DisbursementRow d : open) {
            jdbc.update("UPDATE disbursement SET invoice_id = ?, updated_at = now() WHERE id = ?", inv.getId(), d.id());
        }
        return new MissionDtos.BillResult(inv.getId(), inv.getInvoiceNumber(), lines.size(), inv.getTotal());
    }

    private Mission mission(UUID id) {
        return missionRepository.find(org(), id).orElseThrow(() -> new ResourceNotFoundException("Mission not found"));
    }

    private void partner(UUID id) {
        partnerRepository.findByOrganizationIdAndId(org(), id).orElseThrow(() -> new ResourceNotFoundException("Client not found"));
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static MissionDtos.Summary summary(ResultSet rs, int i) throws SQLException {
        LocalDate due = rs.getObject("due_date", LocalDate.class);
        Mission.Status status = Mission.Status.valueOf(rs.getString("status"));
        return new MissionDtos.Summary(rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class),
                rs.getString("partner_name"), rs.getString("name"), status, rs.getObject("start_date", LocalDate.class), due,
                rs.getBigDecimal("fee_amount"), rs.getObject("assigned_user_id", UUID.class), rs.getString("user_name"),
                rs.getInt("n_deliverables"), rs.getInt("n_delivered"), rs.getBigDecimal("unbilled"),
                due != null && due.isBefore(LocalDate.now()) && (status == Mission.Status.PLANNED || status == Mission.Status.IN_PROGRESS));
    }

    private static MissionDtos.Deliverable deliverable(ResultSet rs, int i) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp("delivered_at");
        return new MissionDtos.Deliverable(rs.getObject("id", UUID.class), rs.getObject("mission_id", UUID.class),
                rs.getString("title"), rs.getObject("due_date", LocalDate.class),
                MissionDeliverable.Status.valueOf(rs.getString("status")), rs.getObject("file_id", UUID.class),
                rs.getString("file_name"), t == null ? null : t.toInstant(), rs.getBoolean("visible_to_client"));
    }

    private static MissionDtos.DisbursementRow disbursement(ResultSet rs, int i) throws SQLException {
        return new MissionDtos.DisbursementRow(rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class),
                rs.getString("partner_name"), rs.getObject("mission_id", UUID.class), rs.getString("mission_name"),
                rs.getObject("spent_on", LocalDate.class), rs.getString("description"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("vat_rate"), rs.getBoolean("rebillable"), rs.getObject("live_invoice_id", UUID.class),
                rs.getString("invoice_number"), rs.getObject("receipt_file_id", UUID.class));
    }
}
