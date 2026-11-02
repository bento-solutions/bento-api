package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock change under the periodic method: the stock valuation on a date against the value the
 * previous stock change recorded, posted in OD (3111 / 6114). Never automatic.
 */
@Service
@RequiredArgsConstructor
public class LedgerStockChangeService {

    public static final String SOURCE = "STOCK_CHANGE";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerEntryRepository entryRepository;
    private final StockService stockService;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    @Transactional
    public LedgerClosingDtos.StockChange get(LocalDate date) {
        UUID orgId = org();
        if (date == null) throw new IllegalArgumentException("The date is required");
        List<Map<String, Object>> posted = postedOn(orgId, date);
        if (!posted.isEmpty()) return dto(date, posted.get(0));
        BigDecimal opening = opening(orgId, date);
        BigDecimal closing = valuation(orgId, date);
        return new LedgerClosingDtos.StockChange(date, opening, closing, closing.subtract(opening), null);
    }

    /** Posts the stock change of a date; does nothing when one is already posted on that date. */
    @Transactional
    public LedgerClosingDtos.StockChange post(LedgerClosingDtos.StockChangeRequest r) {
        UUID orgId = org();
        LocalDate date = r.date();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        List<Map<String, Object>> posted = postedOn(orgId, date);
        if (!posted.isEmpty()) return dto(date, posted.get(0));
        posting.checkNotLocked(orgId, date);
        BigDecimal opening = opening(orgId, date);
        BigDecimal closing = valuation(orgId, date);
        BigDecimal change = closing.subtract(opening);
        UUID entryId = null;
        if (change.signum() != 0) {
            UUID stock = posting.accountId(orgId, "3111");
            UUID variation = posting.accountId(orgId, "6114");
            SignedLines lines = new SignedLines()
                    .add(stock, null, null, change, "Stock final " + date)
                    .add(variation, null, null, change.negate(), "Variation de stock");
            LedgerEntry e = posting.postManual(orgId, "OD", date, "Variation de stock au " + date, SOURCE,
                    SignedLines.sourceId(orgId, "STOCK|" + date), "Stock " + date, lines.lines());
            entryId = e.getId();
        }
        jdbc.update("INSERT INTO ledger_stock_change (organization_id, change_date, opening, closing, entry_id, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?)", orgId, date, opening, closing, entryId, CurrentUser.idOrNull());
        return new LedgerClosingDtos.StockChange(date, opening, closing, change, entryId);
    }

    @Transactional
    public LedgerClosingDtos.StockChange cancel(LocalDate date) {
        UUID orgId = org();
        List<Map<String, Object>> posted = postedOn(orgId, date);
        if (posted.isEmpty()) throw new ResourceNotFoundException("No stock change is posted on this date");
        UUID entryId = (UUID) posted.get(0).get("entry_id");
        if (entryId != null) {
            LedgerEntry e = entryRepository.findByOrganizationIdAndId(orgId, entryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Entry not found"));
            if (e.getReversedById() == null) {
                posting.reverse(orgId, e, e.getEntryDate(), LedgerPostingService.LockPolicy.REJECT);
            }
        } else {
            posting.checkNotLocked(orgId, date);
        }
        jdbc.update("UPDATE ledger_stock_change SET deleted_at = now() WHERE organization_id = ? AND id = ?",
                orgId, posted.get(0).get("id"));
        return get(date);
    }

    private List<Map<String, Object>> postedOn(UUID orgId, LocalDate date) {
        return jdbc.queryForList("SELECT id, opening, closing, entry_id FROM ledger_stock_change WHERE organization_id = ?"
                + " AND change_date = ? AND deleted_at IS NULL", orgId, date);
    }

    private static LedgerClosingDtos.StockChange dto(LocalDate date, Map<String, Object> row) {
        BigDecimal opening = (BigDecimal) row.get("opening");
        BigDecimal closing = (BigDecimal) row.get("closing");
        return new LedgerClosingDtos.StockChange(date, opening, closing, closing.subtract(opening), (UUID) row.get("entry_id"));
    }

    /** The closing value of the last stock change before the date, or 0. */
    private BigDecimal opening(UUID orgId, LocalDate date) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT closing FROM ledger_stock_change WHERE organization_id = ?"
                + " AND change_date < ? AND deleted_at IS NULL ORDER BY change_date DESC LIMIT 1", BigDecimal.class, orgId, date);
        return rows.isEmpty() ? BigDecimal.ZERO.setScale(2) : rows.get(0).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The stock valuation at the end of a date: today or later, the live valuation (cost x on hand);
     * earlier, each product's quantity after its last movement of that day, at today's cost.
     */
    private BigDecimal valuation(UUID orgId, LocalDate date) {
        if (!date.isBefore(LocalDate.now())) {
            return stockService.valuation().totalValue().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(m.balance_after, 0) *
                         CASE WHEN p.cost_price IS NOT NULL AND p.cost_price > 0 THEN p.cost_price ELSE COALESCE(p.unit_price, 0) END), 0)
                FROM (SELECT DISTINCT ON (product_id, warehouse_id) product_id, balance_after
                      FROM stock_movement WHERE organization_id = ? AND deleted_at IS NULL AND created_at < ?
                      ORDER BY product_id, warehouse_id, created_at DESC) m
                JOIN product p ON p.id = m.product_id AND p.organization_id = ?
                WHERE p.track_stock = TRUE AND p.deleted_at IS NULL
                """, BigDecimal.class, orgId, java.sql.Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)), orgId);
        return total == null ? BigDecimal.ZERO.setScale(2) : total.setScale(2, RoundingMode.HALF_UP);
    }
}
