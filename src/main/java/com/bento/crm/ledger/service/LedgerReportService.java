package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.ledger.dto.LedgerDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** General ledger and trial balance, straight from the entry lines. */
@Service
@RequiredArgsConstructor
public class LedgerReportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final LedgerSetupService setup;
    private final JdbcTemplate jdbc;
    private final LedgerPostingService posting;

    /**
     * Year-end carry-forwards are not counted twice: entries before the last carry-forward on or
     * before the period start are left out (it holds them), and a carry-forward inside the period
     * is left out (the period already holds what it carries). Two parameters: floor, from.
     */
    private static final String CARRY_FORWARD = " AND e.entry_date >= ? AND NOT (COALESCE(e.source_type, '') = 'YEAR_END'"
            + " AND e.reversed_by_id IS NULL AND e.reversal_of_id IS NULL AND e.entry_date > ?"
            + " AND e.journal_id IN (SELECT oj.id FROM ledger_journal oj WHERE oj.organization_id = e.organization_id AND oj.kind = 'OPENING'))";

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    /** The period a report covers: given dates, else the current fiscal year to date. */
    record Period(LocalDate from, LocalDate to) {}

    Period period(UUID orgId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate end = to != null ? to : today;
        LocalDate start = from;
        if (start == null) {
            Integer month = jdbc.queryForObject("SELECT fiscal_year_start_month FROM organization WHERE id = ?",
                    Integer.class, orgId);
            int m = month == null || month < 1 || month > 12 ? 1 : month;
            LocalDate candidate = LocalDate.of(end.getYear(), m, 1);
            start = candidate.isAfter(end) ? candidate.minusYears(1) : candidate;
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        return new Period(start, end);
    }

    private record Acc(UUID id, String code, String name, int accountClass) {}

    @Transactional
    public LedgerDtos.GeneralLedger generalLedger(LocalDate from, LocalDate to, UUID accountId, String accountFrom,
                                                  String accountTo, UUID partnerId, UUID costCenterId) {
        UUID orgId = org();
        Period p = period(orgId, from, to);

        StringBuilder filter = new StringBuilder(
                " FROM ledger_entry_line l"
                        + " JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL"
                        + " JOIN ledger_account a ON a.id = l.account_id AND a.organization_id = l.organization_id"
                        + " JOIN ledger_journal j ON j.id = e.journal_id"
                        + " LEFT JOIN partner pt ON pt.id = l.partner_id"
                        + " LEFT JOIN ledger_cost_center cc ON cc.id = l.cost_center_id"
                        + " WHERE l.organization_id = ? AND l.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        if (accountId != null) {
            filter.append(" AND l.account_id = ?");
            params.add(accountId);
        }
        if (accountFrom != null && !accountFrom.isBlank()) {
            filter.append(" AND a.code >= ?");
            params.add(accountFrom.trim());
        }
        if (accountTo != null && !accountTo.isBlank()) {
            filter.append(" AND LEFT(a.code, ?) <= ?");
            params.add(accountTo.trim().length());
            params.add(accountTo.trim());
        }
        if (partnerId != null) {
            filter.append(" AND l.partner_id = ?");
            params.add(partnerId);
        }
        if (costCenterId != null) {
            filter.append(" AND l.cost_center_id = ?");
            params.add(costCenterId);
        }
        filter.append(CARRY_FORWARD);
        params.add(posting.carryForwardFloor(orgId, p.from()));
        params.add(p.from());

        Map<UUID, Acc> accounts = new HashMap<>();
        Map<UUID, BigDecimal> opening = new HashMap<>();
        List<Object> openingParams = new ArrayList<>(params);
        openingParams.add(p.from());
        jdbc.query("SELECT a.id, a.code, a.name, a.account_class, SUM(l.debit - l.credit)" + filter
                + " AND e.entry_date < ? GROUP BY a.id, a.code, a.name, a.account_class", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            accounts.put(id, new Acc(id, rs.getString(2), rs.getString(3), rs.getInt(4)));
            opening.put(id, rs.getBigDecimal(5));
        }, openingParams.toArray());

        record Row(UUID accountId, LedgerDtos.GeneralLedgerLine line) {}
        List<Row> rows = new ArrayList<>();
        List<Object> lineParams = new ArrayList<>(params);
        lineParams.add(p.from());
        lineParams.add(p.to());
        jdbc.query("SELECT a.id, a.code, a.name, a.account_class, e.id, e.number, j.code, e.entry_date,"
                + " COALESCE(l.label, e.description), pt.name, cc.code, l.debit, l.credit"
                + filter
                + " AND e.entry_date >= ? AND e.entry_date <= ?"
                + " ORDER BY a.code, e.entry_date, e.number, l.line_no", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            accounts.putIfAbsent(id, new Acc(id, rs.getString(2), rs.getString(3), rs.getInt(4)));
            rows.add(new Row(id, new LedgerDtos.GeneralLedgerLine(rs.getObject(5, UUID.class), rs.getString(6),
                    rs.getString(7), rs.getObject(8, LocalDate.class), rs.getString(9), rs.getString(10),
                    rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13), null)));
        }, lineParams.toArray());

        Map<UUID, List<LedgerDtos.GeneralLedgerLine>> linesByAccount = new LinkedHashMap<>();
        for (Row r : rows) {
            linesByAccount.computeIfAbsent(r.accountId(), k -> new ArrayList<>()).add(r.line());
        }

        List<LedgerDtos.GeneralLedgerAccount> result = new ArrayList<>();
        accounts.values().stream().sorted((x, y) -> x.code().compareTo(y.code())).forEach(a -> {
            BigDecimal open = opening.getOrDefault(a.id(), ZERO).setScale(2);
            List<LedgerDtos.GeneralLedgerLine> raw = linesByAccount.getOrDefault(a.id(), List.of());
            if (open.signum() == 0 && raw.isEmpty()) {
                return;
            }
            BigDecimal running = open;
            BigDecimal debit = ZERO;
            BigDecimal credit = ZERO;
            List<LedgerDtos.GeneralLedgerLine> lines = new ArrayList<>();
            for (LedgerDtos.GeneralLedgerLine l : raw) {
                running = running.add(l.debit()).subtract(l.credit());
                debit = debit.add(l.debit());
                credit = credit.add(l.credit());
                lines.add(new LedgerDtos.GeneralLedgerLine(l.entryId(), l.entryNumber(), l.journalCode(), l.entryDate(),
                        l.label(), l.partnerName(), l.costCenterCode(), l.debit(), l.credit(), running));
            }
            result.add(new LedgerDtos.GeneralLedgerAccount(a.id(), a.code(), a.name(), open, lines, debit, credit,
                    running));
        });
        return new LedgerDtos.GeneralLedger(p.from(), p.to(), result);
    }

    @Transactional
    public LedgerDtos.TrialBalance trialBalance(LocalDate from, LocalDate to, Integer accountClass) {
        UUID orgId = org();
        Period p = period(orgId, from, to);
        List<Object> params = new ArrayList<>(List.of(p.from(), p.from(), p.to(), p.from(), p.to(), orgId, p.to(),
                posting.carryForwardFloor(orgId, p.from()), p.from()));
        String classFilter = "";
        if (accountClass != null) {
            if (accountClass < 1 || accountClass > 8) {
                throw new IllegalArgumentException("accountClass must be between 1 and 8");
            }
            classFilter = " AND a.account_class = ?";
            params.add(accountClass);
        }
        List<LedgerDtos.TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal[] t = {ZERO, ZERO, ZERO, ZERO, ZERO, ZERO};
        jdbc.query("""
                SELECT a.id, a.code, a.name, a.account_class,
                       COALESCE(SUM(l.debit - l.credit) FILTER (WHERE e.entry_date < ?), 0) AS opening,
                       COALESCE(SUM(l.debit) FILTER (WHERE e.entry_date >= ? AND e.entry_date <= ?), 0) AS period_debit,
                       COALESCE(SUM(l.credit) FILTER (WHERE e.entry_date >= ? AND e.entry_date <= ?), 0) AS period_credit
                FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id AND a.organization_id = l.organization_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date <= ?""" + CARRY_FORWARD + classFilter + """

                GROUP BY a.id, a.code, a.name, a.account_class
                ORDER BY a.code
                """, rs -> {
            BigDecimal opening = rs.getBigDecimal(5).setScale(2);
            BigDecimal pd = rs.getBigDecimal(6).setScale(2);
            BigDecimal pc = rs.getBigDecimal(7).setScale(2);
            BigDecimal closing = opening.add(pd).subtract(pc);
            if (opening.signum() == 0 && pd.signum() == 0 && pc.signum() == 0) {
                return;
            }
            BigDecimal od = opening.signum() > 0 ? opening : ZERO;
            BigDecimal oc = opening.signum() < 0 ? opening.negate() : ZERO;
            BigDecimal cd = closing.signum() > 0 ? closing : ZERO;
            BigDecimal cc = closing.signum() < 0 ? closing.negate() : ZERO;
            rows.add(new LedgerDtos.TrialBalanceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getInt(4), od, oc, pd, pc, cd, cc));
            t[0] = t[0].add(od);
            t[1] = t[1].add(oc);
            t[2] = t[2].add(pd);
            t[3] = t[3].add(pc);
            t[4] = t[4].add(cd);
            t[5] = t[5].add(cc);
        }, params.toArray());
        return new LedgerDtos.TrialBalance(p.from(), p.to(), rows,
                new LedgerDtos.TrialBalanceTotals(t[0], t[1], t[2], t[3], t[4], t[5]));
    }
}
