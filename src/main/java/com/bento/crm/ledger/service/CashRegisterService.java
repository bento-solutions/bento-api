package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.ledger.service.LedgerPostingSetupService.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cash register: its balance and movements are read from the ledger lines on the cash account
 * (5161 by default); movements entered here post in the CA journal against their category's
 * account. TRANSFER is cash deposited at the bank and CASH cash withdrawn from it (both 5115).
 */
@Service
@RequiredArgsConstructor
public class CashRegisterService {

    public static final String SOURCE = "CASH";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerPostingSetupService postingSetup;
    private final LedgerEntryRepository entryRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    @Transactional
    public LedgerClosingDtos.CashRegister register() {
        UUID orgId = org();
        UUID account = postingSetup.snapshot(orgId).cashAccountId();
        String code = jdbc.queryForObject("SELECT code FROM ledger_account WHERE id = ?", String.class, account);
        return new LedgerClosingDtos.CashRegister(posting.balance(orgId, account, null), account, code);
    }

    /** The lines on the cash account, newest first; reversed entries and their reversals are left out. */
    @Transactional
    public PageResponse<LedgerClosingDtos.CashMovement> movements(LocalDate from, LocalDate to, int page, int size) {
        UUID orgId = org();
        UUID account = postingSetup.snapshot(orgId).cashAccountId();
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        String where = """
                 FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                LEFT JOIN partner pt ON pt.id = l.partner_id
                LEFT JOIN cash_movement cm ON cm.organization_id = e.organization_id AND cm.id = e.source_id AND e.source_type = 'CASH'
                WHERE l.organization_id = ? AND l.account_id = ? AND l.deleted_at IS NULL
                  AND e.reversed_by_id IS NULL AND e.reversal_of_id IS NULL
                  AND (CAST(? AS date) IS NULL OR e.entry_date >= CAST(? AS date))
                  AND (CAST(? AS date) IS NULL OR e.entry_date <= CAST(? AS date))
                """;
        Object[] params = {orgId, account, from, from, to, to};
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, params);
        List<Object> pageParams = new ArrayList<>(java.util.Arrays.asList(params));
        pageParams.add(s);
        pageParams.add((long) p * s);
        List<LedgerClosingDtos.CashMovement> content = jdbc.query("""
                SELECT l.id, e.entry_date, l.debit, l.credit, COALESCE(cm.label, l.label, e.description), cm.category,
                       COALESCE(cm.partner_id, l.partner_id), pt.name, e.source_type, e.source_id, e.id, cm.id,
                       (SELECT cpt.name FROM partner cpt WHERE cpt.id = cm.partner_id)
                """ + where + " ORDER BY e.entry_date DESC, e.number DESC, l.line_no LIMIT ? OFFSET ?", (rs, i) -> {
            BigDecimal debit = rs.getBigDecimal(3);
            boolean in = debit.signum() > 0;
            UUID movementId = rs.getObject(12, UUID.class);
            String partnerName = rs.getString(13) != null ? rs.getString(13) : rs.getString(8);
            return new LedgerClosingDtos.CashMovement(movementId != null ? movementId : rs.getObject(1, UUID.class),
                    rs.getObject(2, LocalDate.class), in ? "IN" : "OUT", in ? debit : rs.getBigDecimal(4), rs.getString(5),
                    rs.getString(6), rs.getObject(7, UUID.class), partnerName, rs.getString(9), rs.getObject(10, UUID.class),
                    rs.getObject(11, UUID.class));
        }, pageParams.toArray());
        return PageResponse.fromPage(new PageImpl<>(content, PageRequest.of(p, s), total == null ? 0 : total));
    }

    @Transactional
    public LedgerClosingDtos.CashMovement create(LedgerClosingDtos.CashMovementRequest r) {
        UUID orgId = org();
        String kind = r.kind().trim().toUpperCase(Locale.ROOT);
        if (!kind.equals("IN") && !kind.equals("OUT")) throw new IllegalArgumentException("The kind is IN or OUT");
        String category = r.category() == null || r.category().isBlank() ? null : r.category().trim().toUpperCase(Locale.ROOT);
        Snapshot s = postingSetup.snapshot(orgId);
        UUID counter = counterAccount(orgId, s, category, r.partnerId(), kind);
        if (r.partnerId() != null) {
            Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM partner WHERE organization_id = ? AND id = ?"
                    + " AND deleted_at IS NULL)", Boolean.class, orgId, r.partnerId());
            if (!Boolean.TRUE.equals(ok)) throw new ResourceNotFoundException("Partner not found");
        }
        BigDecimal amount = SignedLines.money(r.amount());
        UUID id = UUID.randomUUID();
        String label = r.label().trim();
        BigDecimal sign = kind.equals("IN") ? BigDecimal.ONE : BigDecimal.ONE.negate();
        UUID partnerOnCounter = r.partnerId() != null && ("SALES".equals(category) || "SUPPLIERS".equals(category))
                ? r.partnerId() : null;
        SignedLines lines = new SignedLines()
                .add(s.cashAccountId(), null, null, amount.multiply(sign), label)
                .add(counter, partnerOnCounter, r.costCenterId(), amount.multiply(sign).negate(), label);
        jdbc.update("INSERT INTO cash_movement (id, organization_id, movement_date, kind, amount, label, category, partner_id,"
                + " cost_center_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, orgId, r.date(), kind, amount,
                label, category, r.partnerId(), r.costCenterId(), CurrentUser.idOrNull());
        LedgerEntry e = posting.postManual(orgId, "CA", r.date(), (kind.equals("IN") ? "Cash in: " : "Cash out: ") + label,
                SOURCE, id, label, lines.lines());
        jdbc.update("UPDATE cash_movement SET entry_id = ? WHERE id = ?", e.getId(), id);
        return movement(orgId, id);
    }

    @Transactional
    public void delete(UUID id) {
        UUID orgId = org();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM cash_movement WHERE organization_id = ? AND id = ?"
                + " AND deleted_at IS NULL", orgId, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Cash movement not found");
        entryRepository.lockLive(orgId, SOURCE, id).ifPresent(e ->
                posting.reverse(orgId, e, e.getEntryDate(), LedgerPostingService.LockPolicy.REJECT));
        jdbc.update("UPDATE cash_movement SET deleted_at = now() WHERE organization_id = ? AND id = ?", orgId, id);
    }

    private LedgerClosingDtos.CashMovement movement(UUID orgId, UUID id) {
        return jdbc.query("""
                SELECT cm.id, cm.movement_date, cm.kind, cm.amount, cm.label, cm.category, cm.partner_id, p.name, cm.entry_id
                FROM cash_movement cm LEFT JOIN partner p ON p.id = cm.partner_id
                WHERE cm.organization_id = ? AND cm.id = ?
                """, (rs, i) -> new LedgerClosingDtos.CashMovement(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                rs.getString(8), SOURCE, rs.getObject(1, UUID.class), rs.getObject(9, UUID.class)), orgId, id).get(0);
    }

    /**
     * The account a movement goes against: TRANSFER and CASH 5115 (to or from the bank), SALES and
     * SUPPLIERS with a partner the partner's account, otherwise the bank category's account (4497
     * when unknown or none).
     */
    private UUID counterAccount(UUID orgId, Snapshot s, String category, UUID partnerId, String kind) {
        if (category == null) return posting.accountId(orgId, "4497");
        if (category.equals("TRANSFER") || category.equals("CASH")) return posting.accountId(orgId, "5115");
        if (partnerId != null && (category.equals("SALES") || category.equals("SUPPLIERS"))) {
            List<Map<String, Object>> p = jdbc.queryForList("SELECT customer_group_id, supplier_group_id FROM partner"
                    + " WHERE organization_id = ? AND id = ?", orgId, partnerId);
            if (!p.isEmpty()) {
                return category.equals("SALES")
                        ? s.partnerAccount(LedgerPostingSetupService.CUSTOMER, (UUID) p.get(0).get("customer_group_id"))
                        : s.partnerAccount(LedgerPostingSetupService.SUPPLIER, (UUID) p.get(0).get("supplier_group_id"));
            }
        }
        UUID account = s.bankCategories().get(category);
        if (account == null) throw new IllegalArgumentException("Unknown category: " + category);
        return account;
    }
}
