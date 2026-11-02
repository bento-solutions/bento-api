package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Ledger settings (lock date, VAT regime and period, fiscal year start) and the year-end close.
 *
 * <p>Closing year Y posts, in OD on its last day, the entry that zeroes classes 6, 7 and 8 into
 * 1191 (profit) or 1199 (loss), then, in AN on the next day, the carry-forward of every
 * balance-sheet account (classes 1 to 5, per partner). The lock date moves to the year end.
 * Reopening reverses both, as long as no later year is closed.
 */
@Service
@RequiredArgsConstructor
public class LedgerClosingService {

    public static final String YEAR_END = "YEAR_END";
    static final String ENCAISSEMENT = "ENCAISSEMENT";
    static final String DEBIT = "DEBIT";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerEntryRepository entryRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    // Settings

    @Transactional
    public LedgerClosingDtos.Settings settings() {
        return settings(org());
    }

    LedgerClosingDtos.Settings settings(UUID orgId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT lock_date, vat_regime, vat_period FROM ledger_settings WHERE organization_id = ?", orgId);
        java.sql.Date lock = (java.sql.Date) row.get("lock_date");
        return new LedgerClosingDtos.Settings(lock == null ? null : lock.toLocalDate(), (String) row.get("vat_regime"),
                (String) row.get("vat_period"), fiscalStartMonth(orgId));
    }

    String vatRegime(UUID orgId) {
        return jdbc.queryForObject("SELECT vat_regime FROM ledger_settings WHERE organization_id = ?", String.class, orgId);
    }

    @Transactional
    public LedgerClosingDtos.Settings updateSettings(LedgerClosingDtos.SettingsUpdate r) {
        UUID orgId = org();
        // Serializes settings changes with closes and reopens.
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        LedgerClosingDtos.Settings before = settings(orgId);
        if (r.getVatRegime() != null) {
            String regime = r.getVatRegime().trim().toUpperCase(Locale.ROOT);
            if (!regime.equals(ENCAISSEMENT) && !regime.equals(DEBIT)) {
                throw new IllegalArgumentException("The VAT regime is ENCAISSEMENT or DEBIT");
            }
            jdbc.update("UPDATE ledger_settings SET vat_regime = ?, updated_at = now() WHERE organization_id = ?", regime, orgId);
        }
        if (r.getVatPeriod() != null) {
            String period = r.getVatPeriod().trim().toUpperCase(Locale.ROOT);
            if (!period.equals("MONTHLY") && !period.equals("QUARTERLY")) {
                throw new IllegalArgumentException("The VAT period is MONTHLY or QUARTERLY");
            }
            jdbc.update("UPDATE ledger_settings SET vat_period = ?, updated_at = now() WHERE organization_id = ?", period, orgId);
        }
        if (r.getFiscalYearStartMonth() != null && r.getFiscalYearStartMonth() != before.fiscalYearStartMonth()) {
            int m = r.getFiscalYearStartMonth();
            if (m < 1 || m > 12) {
                throw new IllegalArgumentException("The fiscal year starts in a month from 1 to 12");
            }
            Boolean closed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_fiscal_year WHERE organization_id = ?"
                    + " AND status = 'CLOSED')", Boolean.class, orgId);
            if (Boolean.TRUE.equals(closed)) {
                throw new IllegalArgumentException("Reopen the closed years to change the fiscal year start");
            }
            jdbc.update("UPDATE organization SET fiscal_year_start_month = ? WHERE id = ?", m, orgId);
        }
        if (r.isLockDatePresent() && !Objects.equals(r.getLockDate(), before.lockDate())) {
            setLockDate(orgId, r.getLockDate(), "LOCK_DATE", null);
        }
        return settings(orgId);
    }

    private void setLockDate(UUID orgId, LocalDate lockDate, String action, String why) {
        LocalDate before = posting.lockDate(orgId);
        jdbc.update("UPDATE ledger_settings SET lock_date = ?, updated_at = now() WHERE organization_id = ?", lockDate, orgId);
        audit(orgId, action, "Lock date " + (before == null ? "none" : before) + " -> " + (lockDate == null ? "none" : lockDate)
                + (why == null ? "" : " (" + why + ")"));
    }

    void audit(UUID orgId, String action, String details) {
        jdbc.update("INSERT INTO ledger_audit_log (organization_id, user_id, action, details) VALUES (?, ?, ?, ?)",
                orgId, CurrentUser.idOrNull(), action, details);
    }

    int fiscalStartMonth(UUID orgId) {
        List<Integer> rows = jdbc.queryForList("SELECT fiscal_year_start_month FROM organization WHERE id = ?", Integer.class, orgId);
        Integer m = rows.isEmpty() ? null : rows.get(0);
        return m == null || m < 1 || m > 12 ? 1 : m;
    }

    // Years

    /** Year Y starts on the first day of the fiscal start month in calendar year Y. */
    LocalDate yearStart(UUID orgId, int year) {
        return LocalDate.of(year, fiscalStartMonth(orgId), 1);
    }

    int yearOf(UUID orgId, LocalDate date) {
        int m = fiscalStartMonth(orgId);
        return date.getMonthValue() >= m ? date.getYear() : date.getYear() - 1;
    }

    @Transactional
    public List<LedgerClosingDtos.FiscalYear> years() {
        UUID orgId = org();
        LocalDate start = jdbc.queryForObject("SELECT start_date FROM ledger_settings WHERE organization_id = ?",
                LocalDate.class, orgId);
        LocalDate first = jdbc.queryForObject("SELECT MIN(entry_date) FROM ledger_entry WHERE organization_id = ?"
                + " AND deleted_at IS NULL", LocalDate.class, orgId);
        int from = yearOf(orgId, first != null && first.isBefore(start) ? first : start);
        int to = yearOf(orgId, LocalDate.now());
        Map<Integer, LedgerClosingDtos.FiscalYear> years = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            LocalDate s = yearStart(orgId, y);
            years.put(y, new LedgerClosingDtos.FiscalYear(y, "OPEN", s, s.plusYears(1).minusDays(1), null, null, null));
        }
        jdbc.query("SELECT year, status, start_date, end_date, closed_at, result_entry_id, opening_entry_id"
                + " FROM ledger_fiscal_year WHERE organization_id = ?", rs -> {
            java.sql.Timestamp closedAt = rs.getTimestamp(5);
            boolean closed = "CLOSED".equals(rs.getString(2));
            years.put(rs.getInt(1), new LedgerClosingDtos.FiscalYear(rs.getInt(1), rs.getString(2),
                    rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                    closed && closedAt != null ? closedAt.toInstant() : null,
                    closed ? rs.getObject(6, UUID.class) : null, closed ? rs.getObject(7, UUID.class) : null));
        }, orgId);
        return new ArrayList<>(years.values());
    }

    @Transactional
    public LedgerClosingDtos.FiscalYear close(int year) {
        UUID orgId = org();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        LocalDate start = yearStart(orgId, year);
        LocalDate end = start.plusYears(1).minusDays(1);
        if (end.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The year " + year + " has not ended yet");
        }
        if ("CLOSED".equals(status(orgId, year))) {
            throw new IllegalArgumentException("The year " + year + " is already closed");
        }
        Boolean earlier = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_entry WHERE organization_id = ?"
                + " AND entry_date < ? AND deleted_at IS NULL)", Boolean.class, orgId, start);
        if (Boolean.TRUE.equals(earlier) && !"CLOSED".equals(status(orgId, year - 1))) {
            throw new IllegalArgumentException("Close " + (year - 1) + " first");
        }
        LocalDate lock = posting.lockDate(orgId);
        if (lock != null && !lock.isBefore(end)) {
            throw new IllegalArgumentException(LedgerPostingService.LOCKED);
        }

        // Result: classes 6, 7 and 8 back to zero, the net to 1191 (profit) or 1199 (loss).
        SignedLines result = new SignedLines();
        BigDecimal[] net = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT l.account_id, SUM(l.debit - l.credit) FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                  AND a.account_class IN (6, 7, 8)
                GROUP BY l.account_id, a.code ORDER BY a.code
                """, rs -> {
            BigDecimal b = rs.getBigDecimal(2);
            result.add(rs.getObject(1, UUID.class), null, null, b.negate(), "Solde " + year);
            net[0] = net[0].add(b);
        }, orgId, start, end);
        // net = charges - produits: positive is a loss.
        BigDecimal r = SignedLines.money(net[0]);
        if (r.signum() > 0) {
            result.add(posting.accountId(orgId, "1199"), null, null, r, "Résultat net (perte)");
        } else if (r.signum() < 0) {
            result.add(posting.accountId(orgId, "1191"), null, null, r, "Résultat net (bénéfice)");
        }
        UUID resultId = null;
        if (result.postable()) {
            LedgerEntry e = posting.postManual(orgId, "OD", end, "Résultat de l'exercice " + year, YEAR_END,
                    SignedLines.sourceId(orgId, "YEAR_END|" + year + "|RESULT"), "Exercice " + year, result.lines());
            resultId = e.getId();
        }

        // Carry-forward: classes 1 to 5, per account and partner, on the first day of the next year.
        SignedLines opening = new SignedLines();
        jdbc.query("""
                SELECT l.account_id, l.partner_id, SUM(l.debit - l.credit) FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                  AND a.account_class BETWEEN 1 AND 5
                GROUP BY l.account_id, l.partner_id, a.code ORDER BY a.code
                """, rs -> {
            opening.add(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), null, rs.getBigDecimal(3),
                    "Report à nouveau");
        }, orgId, start, end);
        UUID openingId = null;
        if (opening.postable()) {
            LedgerEntry e = posting.postManual(orgId, "AN", end.plusDays(1), "Reports à nouveau, exercice " + (year + 1),
                    YEAR_END, SignedLines.sourceId(orgId, "YEAR_END|" + year + "|OPENING"), "Exercice " + year,
                    opening.lines());
            openingId = e.getId();
        }

        jdbc.update("""
                INSERT INTO ledger_fiscal_year (organization_id, year, status, start_date, end_date, closed_at, closed_by,
                                                result_entry_id, opening_entry_id, previous_lock_date, updated_at)
                VALUES (?, ?, 'CLOSED', ?, ?, now(), ?, ?, ?, ?, now())
                ON CONFLICT (organization_id, year) DO UPDATE SET status = 'CLOSED', start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date, closed_at = now(), closed_by = EXCLUDED.closed_by,
                    result_entry_id = EXCLUDED.result_entry_id, opening_entry_id = EXCLUDED.opening_entry_id,
                    previous_lock_date = EXCLUDED.previous_lock_date, updated_at = now()
                """, orgId, year, start, end, CurrentUser.idOrNull(), resultId, openingId, lock);
        audit(orgId, "YEAR_CLOSE", "Closed " + year);
        if (lock == null || lock.isBefore(end)) {
            setLockDate(orgId, end, "LOCK_DATE", "year " + year + " closed");
        }
        return year(orgId, year);
    }

    @Transactional
    public LedgerClosingDtos.FiscalYear reopen(int year) {
        UUID orgId = org();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ledger_fiscal_year WHERE organization_id = ?"
                + " AND year = ? AND status = 'CLOSED'", orgId, year);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The year " + year + " is not closed");
        }
        Boolean later = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_fiscal_year WHERE organization_id = ?"
                + " AND year > ? AND status = 'CLOSED')", Boolean.class, orgId, year);
        if (Boolean.TRUE.equals(later)) {
            throw new IllegalArgumentException("Reopen the later years first");
        }
        Map<String, Object> row = rows.get(0);
        LocalDate start = ((java.sql.Date) row.get("start_date")).toLocalDate();
        LocalDate end = ((java.sql.Date) row.get("end_date")).toLocalDate();
        LocalDate lock = posting.lockDate(orgId);
        if (lock != null && lock.isAfter(end)) {
            throw new IllegalArgumentException("Move the lock date back to " + end + " or earlier to reopen " + year);
        }
        // The lock date goes back to what it was before the close (always before the year end).
        java.sql.Date prev = (java.sql.Date) row.get("previous_lock_date");
        LocalDate restored = prev == null ? null : prev.toLocalDate();
        if (restored != null && !restored.isBefore(end)) restored = start.minusDays(1);
        if (!Objects.equals(restored, lock)) {
            setLockDate(orgId, restored, "LOCK_DATE", "year " + year + " reopened");
        }
        for (String key : new String[]{"opening_entry_id", "result_entry_id"}) {
            UUID id = (UUID) row.get(key);
            if (id == null) continue;
            LedgerEntry e = entryRepository.findByOrganizationIdAndId(orgId, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Entry not found"));
            if (e.getReversedById() == null) {
                posting.reverse(orgId, e, e.getEntryDate(), LedgerPostingService.LockPolicy.REJECT);
            }
        }
        jdbc.update("UPDATE ledger_fiscal_year SET status = 'OPEN', closed_at = NULL, closed_by = NULL, result_entry_id = NULL,"
                + " opening_entry_id = NULL, updated_at = now() WHERE organization_id = ? AND year = ?", orgId, year);
        audit(orgId, "YEAR_REOPEN", "Reopened " + year);
        return year(orgId, year);
    }

    private String status(UUID orgId, int year) {
        List<String> s = jdbc.queryForList("SELECT status FROM ledger_fiscal_year WHERE organization_id = ? AND year = ?",
                String.class, orgId, year);
        return s.isEmpty() ? "OPEN" : s.get(0);
    }

    private LedgerClosingDtos.FiscalYear year(UUID orgId, int year) {
        List<LedgerClosingDtos.FiscalYear> rows = jdbc.query("SELECT year, status, start_date, end_date, closed_at,"
                + " result_entry_id, opening_entry_id FROM ledger_fiscal_year WHERE organization_id = ? AND year = ?",
                (rs, i) -> new LedgerClosingDtos.FiscalYear(rs.getInt(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)), orgId, year);
        return rows.get(0);
    }
}
