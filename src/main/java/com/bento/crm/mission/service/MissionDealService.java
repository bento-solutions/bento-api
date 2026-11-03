package com.bento.crm.mission.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.model.LedgerCostCenter;
import com.bento.crm.ledger.repository.LedgerCostCenterRepository;
import com.bento.crm.mission.dto.MissionDtos;
import com.bento.crm.mission.model.Mission;
import com.bento.crm.mission.repository.MissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Missions made from a deal (phase 3 part C) and the mission summary: what was quoted, invoiced
 * and spent on the mission's cost center, the margin, and the documents along the way.
 */
@Service
@RequiredArgsConstructor
public class MissionDealService {

    /** A deal's amount before VAT: its amount (else its lines, else the order total) less the discount. */
    public static final String DEAL_BASE = "(COALESCE(d.amount, (SELECT SUM(l.total) FROM deal_order_line l WHERE l.deal_id = d.id"
            + " AND l.deleted_at IS NULL), d.order_total_amount, 0) - COALESCE(d.discount, 0))";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final MissionService missionService;
    private final MissionRepository missionRepository;
    private final LedgerCostCenterRepository costCenterRepository;
    private final JdbcTemplate jdbc;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    /**
     * A PLANNED mission for the deal (its title, partner and amount before VAT as the fee), and a
     * cost center linked to it, coded PRJ-&lt;order number or short id&gt;.
     */
    @Transactional
    public MissionDtos.Detail createFromDeal(UUID dealId) {
        UUID orgId = org();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT d.partner_id, d.title, d.order_number, " + DEAL_BASE
                + " AS base FROM deal d WHERE d.organization_id = ? AND d.id = ? AND d.deleted_at IS NULL", orgId, dealId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Deal not found");
        Map<String, Object> d = rows.get(0);
        BigDecimal base = d.get("base") == null ? null : ((BigDecimal) d.get("base")).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        String title = (String) d.get("title");
        MissionDtos.Detail created = missionService.create(new MissionDtos.MissionRequest((UUID) d.get("partner_id"),
                title == null || title.isBlank() ? "Mission" : title, null, Mission.Status.PLANNED, null, null,
                base, null, dealId));
        UUID missionId = created.mission().id();

        String ref = (String) d.get("order_number");
        ref = ref == null || ref.isBlank() ? dealId.toString().substring(0, 8) : ref.trim().replaceAll("\\s+", "-");
        String code = cut("PRJ-" + ref, 20);
        for (int n = 2; costCenterRepository.existsCode(orgId, code); n++) {
            String suffix = "-" + n;
            code = cut("PRJ-" + ref, 20 - suffix.length()) + suffix;
        }
        LedgerCostCenter cc = new LedgerCostCenter();
        cc.setOrganizationId(orgId);
        cc.setCode(code);
        cc.setName(cut(created.mission().name(), 160));
        cc.setMissionId(missionId);
        costCenterRepository.saveAndFlush(cc);
        return missionService.get(missionId);
    }

    @Transactional(readOnly = true)
    public MissionDtos.MissionSummary summary(UUID missionId) {
        UUID orgId = org();
        Mission m = missionRepository.find(orgId, missionId).orElseThrow(() -> new ResourceNotFoundException("Mission not found"));
        UUID dealId = m.getDealId();
        List<UUID> costCenters = jdbc.queryForList("SELECT id FROM ledger_cost_center WHERE organization_id = ? AND mission_id = ?"
                + " AND deleted_at IS NULL ORDER BY created_at", UUID.class, orgId, missionId);
        UUID[] ccs = costCenters.toArray(new UUID[0]);
        List<MissionDtos.DocumentRow> documents = new ArrayList<>();

        UUID proposalId = null;
        BigDecimal dealBase = null;
        BigDecimal proposalValue = null;
        if (dealId != null) {
            List<Map<String, Object>> deal = jdbc.queryForList("SELECT d.id, d.proposal_id, d.order_number, d.stage, d.title,"
                    + " COALESCE(d.order_date, CAST(d.created_at AS date)) AS day, " + DEAL_BASE + " AS base FROM deal d"
                    + " WHERE d.organization_id = ? AND d.id = ?", orgId, dealId);
            if (!deal.isEmpty()) {
                Map<String, Object> d = deal.get(0);
                proposalId = (UUID) d.get("proposal_id");
                dealBase = money((BigDecimal) d.get("base"));
                documents.add(new MissionDtos.DocumentRow("DEAL", dealId, (String) d.get("order_number"), date(d.get("day")),
                        dealBase, (String) d.get("stage")));
            }
        }
        if (proposalId != null) {
            List<Map<String, Object>> p = jdbc.queryForList("SELECT id, title, status, opportunity_value,"
                    + " CAST(created_at AS date) AS day FROM proposal WHERE organization_id = ? AND id = ? AND deleted_at IS NULL",
                    orgId, proposalId);
            if (!p.isEmpty()) {
                proposalValue = (BigDecimal) p.get(0).get("opportunity_value");
                documents.add(new MissionDtos.DocumentRow("PROPOSAL", proposalId, (String) p.get(0).get("title"),
                        date(p.get(0).get("day")), proposalValue == null ? null : money(proposalValue),
                        (String) p.get(0).get("status")));
            }
        }
        BigDecimal quoted = proposalValue != null ? money(proposalValue)
                : dealBase != null ? dealBase : m.getFeeAmount() == null ? ZERO : money(m.getFeeAmount());

        // Customer invoices of the deal: invoices and advances count, credit notes come off.
        BigDecimal invoiced = ZERO;
        BigDecimal advances = ZERO;
        if (dealId != null) {
            for (Map<String, Object> i : jdbc.queryForList("""
                    SELECT id, invoice_number, invoice_date, kind, status, COALESCE(subtotal, 0) AS subtotal FROM invoice
                    WHERE organization_id = ? AND deal_id = ? AND type = 'CUSTOMER' AND deleted_at IS NULL AND status <> 'DRAFT'
                    ORDER BY invoice_date, created_at
                    """, orgId, dealId)) {
                String kind = (String) i.get("kind");
                BigDecimal sub = money((BigDecimal) i.get("subtotal")).abs();
                if ("CREDIT_NOTE".equals(kind)) {
                    invoiced = invoiced.subtract(sub);
                    continue;
                }
                invoiced = invoiced.add(sub);
                if ("ADVANCE".equals(kind)) advances = advances.add(sub);
                documents.add(new MissionDtos.DocumentRow("ADVANCE".equals(kind) ? "ADVANCE" : "INVOICE", (UUID) i.get("id"),
                        (String) i.get("invoice_number"), date(i.get("invoice_date")), sub, (String) i.get("status")));
            }
        }

        // Class 6 lines posted with the mission's cost centers.
        List<MissionDtos.CostRow> costs = ccs.length == 0 ? List.of() : jdbc.query("""
                SELECT a.code, a.name, SUM(l.debit - l.credit) AS amount FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND l.cost_center_id = ANY(?) AND a.account_class = 6
                GROUP BY a.code, a.name HAVING SUM(l.debit - l.credit) <> 0 ORDER BY a.code
                """, (rs, n) -> new MissionDtos.CostRow(rs.getString(1), rs.getString(2), money(rs.getBigDecimal(3))), orgId, ccs);
        BigDecimal costsTotal = costs.stream().map(MissionDtos.CostRow::amount).reduce(ZERO, BigDecimal::add);

        // Delivery notes of the deal or the mission.
        jdbc.query("""
                SELECT id, number, note_date, status FROM delivery_note WHERE organization_id = ? AND deleted_at IS NULL
                  AND (mission_id = ? OR (CAST(? AS uuid) IS NOT NULL AND deal_id = CAST(? AS uuid)))
                """, rs -> {
            documents.add(new MissionDtos.DocumentRow("DELIVERY_NOTE", rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getObject(3, LocalDate.class), null, rs.getString(4)));
        }, orgId, missionId, dealId, dealId);

        if (ccs.length > 0) {
            // Supplier bills with a line on the mission's cost centers.
            jdbc.query("""
                    SELECT i.id, i.invoice_number, i.invoice_date, i.status, COALESCE(i.subtotal, 0) FROM invoice i
                    WHERE i.organization_id = ? AND i.type = 'VENDOR' AND i.deleted_at IS NULL
                      AND EXISTS (SELECT 1 FROM jsonb_array_elements(i.lines) l WHERE l->>'costCenterId' = ANY(?))
                    """, rs -> {
                documents.add(new MissionDtos.DocumentRow("BILL", rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, LocalDate.class), money(rs.getBigDecimal(5)), rs.getString(4)));
            }, orgId, costCenters.stream().map(UUID::toString).toArray(String[]::new));
            jdbc.query("""
                    SELECT id, reference, expense_date, amount - COALESCE(vat_amount, 0) FROM expense
                    WHERE organization_id = ? AND deleted_at IS NULL AND cost_center_id = ANY(?)
                    """, rs -> {
                documents.add(new MissionDtos.DocumentRow("EXPENSE", rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, LocalDate.class), money(rs.getBigDecimal(4)), null));
            }, orgId, ccs);
        }
        documents.sort(Comparator.comparing(MissionDtos.DocumentRow::date, Comparator.nullsLast(Comparator.naturalOrder())));

        return new MissionDtos.MissionSummary(missionId, dealId, proposalId, costCenters.isEmpty() ? null : costCenters.get(0),
                quoted, invoiced, advances, costs, costsTotal, invoiced.subtract(costsTotal), documents);
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
