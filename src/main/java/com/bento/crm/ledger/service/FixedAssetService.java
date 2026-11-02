package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Fixed assets (immobilisations): acquisition, linear or declining-balance depreciation (Moroccan
 * coefficients 1.5 / 2 / 3), the depreciation run, disposal and the fixed-asset register.
 * Nothing here runs on a schedule: every posting is an explicit action.
 */
@Service
@RequiredArgsConstructor
public class FixedAssetService {

    public static final String SOURCE = "ASSET";
    public static final String DEPRECIATION = "DEPRECIATION";
    static final String LINEAR = "LINEAR";
    static final String DECLINING = "DECLINING";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerPostingSetupService postingSetup;
    private final LedgerAutoPostingService autoPosting;
    private final LedgerEntryRepository entryRepository;
    private final InvoiceRepository invoiceRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    // Model

    record Category(String code, String name, UUID assetAccountId, UUID depreciationAccountId, UUID expenseAccountId,
                    int defaultLifeMonths) {}

    record AssetRow(UUID id, String code, String name, String category, LocalDate acquisitionDate, LocalDate startDate,
                    BigDecimal cost, BigDecimal residual, String method, int life, BigDecimal coefficient,
                    UUID assetAccountId, UUID depreciationAccountId, UUID expenseAccountId, String status,
                    LocalDate disposedAt, BigDecimal disposalPrice, UUID supplierInvoiceId, Integer supplierInvoiceLineIndex,
                    String paidFrom, UUID costCenterId) {}

    /** One month of a depreciation schedule. */
    record Month(YearMonth month, BigDecimal amount) {}

    private static final String SELECT = """
            SELECT id, code, name, category, acquisition_date, start_date, acquisition_cost, residual_value, method,
                   useful_life_months, coefficient, asset_account_id, depreciation_account_id, expense_account_id, status,
                   disposed_at, disposal_price, supplier_invoice_id, supplier_invoice_line_index, paid_from, cost_center_id
            FROM ledger_fixed_asset
            """;

    private static AssetRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AssetRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getBigDecimal(7), rs.getBigDecimal(8),
                rs.getString(9), rs.getInt(10), rs.getBigDecimal(11), rs.getObject(12, UUID.class), rs.getObject(13, UUID.class),
                rs.getObject(14, UUID.class), rs.getString(15), rs.getObject(16, LocalDate.class), rs.getBigDecimal(17),
                rs.getObject(18, UUID.class), (Integer) rs.getObject(19), rs.getString(20), rs.getObject(21, UUID.class));
    }

    private AssetRow asset(UUID orgId, UUID id) {
        List<AssetRow> rows = jdbc.query(SELECT + " WHERE organization_id = ? AND id = ? AND deleted_at IS NULL",
                FixedAssetService::row, orgId, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Fixed asset not found");
        return rows.get(0);
    }

    private List<Category> categories(UUID orgId) {
        return jdbc.query("SELECT code, name, asset_account_id, depreciation_account_id, expense_account_id, default_life_months"
                        + " FROM ledger_asset_category WHERE organization_id = ? ORDER BY sort_order, code",
                (rs, i) -> new Category(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getInt(6)), orgId);
    }

    private Map<UUID, String> accountCodes(UUID orgId) {
        Map<UUID, String> codes = new HashMap<>();
        jdbc.query("SELECT id, code FROM ledger_account WHERE organization_id = ?", rs -> {
            codes.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, orgId);
        return codes;
    }

    // Read

    @Transactional
    public List<LedgerClosingDtos.AssetCategory> listCategories() {
        UUID orgId = org();
        Map<UUID, String> codes = accountCodes(orgId);
        return categories(orgId).stream().map(c -> new LedgerClosingDtos.AssetCategory(c.code(), c.name(),
                codes.get(c.assetAccountId()), codes.get(c.depreciationAccountId()), codes.get(c.expenseAccountId()),
                c.defaultLifeMonths())).toList();
    }

    @Transactional
    public List<LedgerClosingDtos.Asset> list() {
        UUID orgId = org();
        Map<UUID, String> codes = accountCodes(orgId);
        Map<UUID, BigDecimal> accumulated = accumulated(orgId);
        return jdbc.query(SELECT + " WHERE organization_id = ? AND deleted_at IS NULL ORDER BY code", FixedAssetService::row, orgId)
                .stream().map(a -> dto(orgId, a, codes, accumulated)).toList();
    }

    @Transactional
    public LedgerClosingDtos.Asset get(UUID id) {
        UUID orgId = org();
        return dto(orgId, asset(orgId, id), accountCodes(orgId), accumulated(orgId));
    }

    private Map<UUID, BigDecimal> accumulated(UUID orgId) {
        Map<UUID, BigDecimal> m = new HashMap<>();
        jdbc.query("SELECT asset_id, SUM(amount) FROM ledger_depreciation WHERE organization_id = ? GROUP BY asset_id", rs -> {
            m.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
        }, orgId);
        return m;
    }

    private LedgerClosingDtos.Asset dto(UUID orgId, AssetRow a, Map<UUID, String> codes, Map<UUID, BigDecimal> accumulated) {
        BigDecimal acc = SignedLines.money(accumulated.getOrDefault(a.id(), BigDecimal.ZERO));
        UUID acquisition = entryRepository.findLive(orgId, SOURCE, a.id()).map(LedgerEntry::getId).orElse(null);
        UUID disposal = entryRepository.findLive(orgId, SOURCE, disposalId(orgId, a.id())).map(LedgerEntry::getId).orElse(null);
        return new LedgerClosingDtos.Asset(a.id(), a.code(), a.name(), a.category(), a.acquisitionDate(), a.startDate(),
                a.cost(), a.residual(), a.method(), a.life(), a.coefficient(), codes.get(a.assetAccountId()),
                codes.get(a.depreciationAccountId()), codes.get(a.expenseAccountId()), acc, a.cost().subtract(acc),
                a.status(), a.disposedAt(), a.disposalPrice(), a.supplierInvoiceId(), a.supplierInvoiceLineIndex(),
                a.paidFrom(), a.costCenterId(), acquisition, disposal);
    }

    // Create, edit, delete

    @Transactional
    public LedgerClosingDtos.Asset create(LedgerClosingDtos.AssetRequest r) {
        UUID orgId = org();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        UUID id = UUID.randomUUID();
        String code = r.code() == null || r.code().isBlank() ? nextCode(orgId) : r.code().trim();
        save(orgId, id, code, r, true);
        return get(id);
    }

    @Transactional
    public LedgerClosingDtos.Asset update(UUID id, LedgerClosingDtos.AssetRequest r) {
        UUID orgId = org();
        AssetRow a = asset(orgId, id);
        requireNoDepreciation(orgId, a);
        undoAcquisition(orgId, a);
        String code = r.code() == null || r.code().isBlank() ? a.code() : r.code().trim();
        save(orgId, id, code, r, false);
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        UUID orgId = org();
        AssetRow a = asset(orgId, id);
        requireNoDepreciation(orgId, a);
        undoAcquisition(orgId, a);
        jdbc.update("UPDATE ledger_fixed_asset SET deleted_at = now(), updated_at = now() WHERE organization_id = ? AND id = ?",
                orgId, id);
    }

    private void requireNoDepreciation(UUID orgId, AssetRow a) {
        Boolean posted = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_depreciation WHERE organization_id = ?"
                + " AND asset_id = ?)", Boolean.class, orgId, a.id());
        if (Boolean.TRUE.equals(posted) || "DISPOSED".equals(a.status())) {
            throw new IllegalArgumentException("This asset has depreciation posted: it can no longer be changed or deleted");
        }
    }

    /** Reverses the acquisition entry, or takes the bill line back off the asset account. */
    private void undoAcquisition(UUID orgId, AssetRow a) {
        entryRepository.lockLive(orgId, SOURCE, a.id()).ifPresent(e ->
                posting.reverse(orgId, e, e.getEntryDate(), LedgerPostingService.LockPolicy.REJECT));
        if (a.supplierInvoiceId() != null) {
            linkBillLine(orgId, a.supplierInvoiceId(), a.supplierInvoiceLineIndex(), null);
        }
    }

    private void save(UUID orgId, UUID id, String code, LedgerClosingDtos.AssetRequest r, boolean insert) {
        String categoryCode = r.category().trim().toUpperCase(Locale.ROOT);
        Category c = categories(orgId).stream().filter(x -> x.code().equals(categoryCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown fixed-asset category: " + r.category()));
        String method = r.method() == null || r.method().isBlank() ? LINEAR : r.method().trim().toUpperCase(Locale.ROOT);
        if (!method.equals(LINEAR) && !method.equals(DECLINING)) {
            throw new IllegalArgumentException("The method is LINEAR or DECLINING");
        }
        int life = r.usefulLifeMonths() == null ? c.defaultLifeMonths() : r.usefulLifeMonths();
        if (life <= 0 || life > 600) throw new IllegalArgumentException("The useful life is 1 to 600 months");
        BigDecimal coefficient = method.equals(DECLINING) ? coefficient(life) : null;
        BigDecimal cost = SignedLines.money(r.acquisitionCost());
        BigDecimal residual = SignedLines.money(r.residualValue());
        if (residual.compareTo(cost) >= 0) throw new IllegalArgumentException("The residual value must be below the cost");
        LocalDate start = r.startDate() == null ? r.acquisitionDate() : r.startDate();
        if (start.isBefore(r.acquisitionDate())) {
            throw new IllegalArgumentException("Depreciation cannot start before the acquisition date");
        }
        String paidFrom = r.supplierInvoiceId() != null ? "SUPPLIER_BILL"
                : r.paidFrom() == null || r.paidFrom().isBlank() ? "BANK" : r.paidFrom().trim().toUpperCase(Locale.ROOT);
        if (!paidFrom.equals("BANK") && !paidFrom.equals("SUSPENSE") && !paidFrom.equals("SUPPLIER_BILL")) {
            throw new IllegalArgumentException("paidFrom is BANK, SUSPENSE or SUPPLIER_BILL");
        }
        if (paidFrom.equals("SUPPLIER_BILL") && r.supplierInvoiceId() == null) {
            throw new IllegalArgumentException("Give the supplier bill (supplierInvoiceId)");
        }
        if (r.costCenterId() != null) {
            Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_cost_center WHERE organization_id = ? AND id = ?"
                    + " AND deleted_at IS NULL)", Boolean.class, orgId, r.costCenterId());
            if (!Boolean.TRUE.equals(ok)) throw new ResourceNotFoundException("Cost center not found");
        }
        Integer lineIndex = null;
        Invoice bill = null;
        if (r.supplierInvoiceId() != null) {
            bill = invoiceRepository.findByOrganizationIdAndId(orgId, r.supplierInvoiceId())
                    .filter(i -> i.getType() == Invoice.InvoiceType.VENDOR)
                    .orElseThrow(() -> new ResourceNotFoundException("Supplier bill not found"));
            int lines = bill.getLines() == null ? 0 : bill.getLines().size();
            lineIndex = r.supplierInvoiceLineIndex() == null ? 0 : r.supplierInvoiceLineIndex();
            if (lineIndex < 0 || lineIndex >= lines) throw new IllegalArgumentException("No such line on the supplier bill");
            Object linked = bill.getLines().get(lineIndex).get("fixedAssetId");
            if (linked != null && !linked.toString().equals(id.toString())) {
                throw new IllegalArgumentException("This bill line is already a fixed asset");
            }
        }
        try {
            if (insert) {
                jdbc.update("""
                        INSERT INTO ledger_fixed_asset (id, organization_id, code, name, category, acquisition_date, start_date,
                            acquisition_cost, residual_value, method, useful_life_months, coefficient, asset_account_id,
                            depreciation_account_id, expense_account_id, supplier_invoice_id, supplier_invoice_line_index,
                            paid_from, cost_center_id, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, orgId, code, r.name().trim(), c.code(), r.acquisitionDate(), start, cost, residual, method,
                        life, coefficient, c.assetAccountId(), c.depreciationAccountId(), c.expenseAccountId(),
                        r.supplierInvoiceId(), lineIndex, paidFrom, r.costCenterId(), CurrentUser.idOrNull());
            } else {
                jdbc.update("""
                        UPDATE ledger_fixed_asset SET code = ?, name = ?, category = ?, acquisition_date = ?, start_date = ?,
                            acquisition_cost = ?, residual_value = ?, method = ?, useful_life_months = ?, coefficient = ?,
                            asset_account_id = ?, depreciation_account_id = ?, expense_account_id = ?, supplier_invoice_id = ?,
                            supplier_invoice_line_index = ?, paid_from = ?, cost_center_id = ?, updated_at = now(),
                            updated_by = ?, version = version + 1
                        WHERE organization_id = ? AND id = ?
                        """, code, r.name().trim(), c.code(), r.acquisitionDate(), start, cost, residual, method, life,
                        coefficient, c.assetAccountId(), c.depreciationAccountId(), c.expenseAccountId(),
                        r.supplierInvoiceId(), lineIndex, paidFrom, r.costCenterId(), CurrentUser.idOrNull(), orgId, id);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("A fixed asset with code " + code + " already exists");
        }
        if (bill != null) {
            // No acquisition entry: the bill re-posts with that line on the asset account.
            linkBillLine(orgId, bill.getId(), lineIndex, id);
        } else {
            UUID money = paidFrom.equals("SUSPENSE") ? posting.accountId(orgId, "4497")
                    : postingSetup.bankLedgerAccount(orgId, postingSetup.defaultBankAccountId(orgId));
            SignedLines lines = new SignedLines()
                    .add(c.assetAccountId(), null, r.costCenterId(), cost, r.name().trim())
                    .add(money, null, null, cost.negate(), r.name().trim());
            posting.postManual(orgId, "OD", r.acquisitionDate(), "Acquisition " + code + " " + r.name().trim(), SOURCE, id,
                    code, lines.lines());
        }
    }

    /** Puts {@code fixedAssetId} on (or takes it off) a bill line and re-posts the bill. */
    private void linkBillLine(UUID orgId, UUID billId, Integer lineIndex, UUID assetId) {
        Invoice bill = invoiceRepository.findByOrganizationIdAndId(orgId, billId).orElse(null);
        if (bill == null || bill.getLines() == null || lineIndex == null || lineIndex >= bill.getLines().size()) return;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : bill.getLines()) lines.add(l == null ? null : new LinkedHashMap<>(l));
        if (assetId == null) lines.get(lineIndex).remove("fixedAssetId");
        else lines.get(lineIndex).put("fixedAssetId", assetId.toString());
        bill.setLines(lines);
        bill = invoiceRepository.saveAndFlush(bill);
        autoPosting.invoice(bill);
    }

    private String nextCode(UUID orgId) {
        Integer max = jdbc.queryForObject("SELECT MAX(CAST(SUBSTRING(code FROM 5) AS INT)) FROM ledger_fixed_asset"
                + " WHERE organization_id = ? AND code ~ '^IMM-[0-9]{1,9}$'", Integer.class, orgId);
        return String.format("IMM-%04d", max == null ? 1 : max + 1);
    }

    // Depreciation

    /** Moroccan declining-balance coefficient: 1.5 for 3-4 years, 2 for 5-6 years, 3 above 6 years. */
    static BigDecimal coefficient(int lifeMonths) {
        int years = lifeMonths / 12;
        if (years < 3) throw new IllegalArgumentException("Declining balance needs a useful life of at least 3 years");
        if (years <= 4) return new BigDecimal("1.50");
        if (years <= 6) return new BigDecimal("2.00");
        return new BigDecimal("3.00");
    }

    /**
     * The full schedule, month by month from the start month. LINEAR: (cost - residual) / life per
     * month. DECLINING: per calendar year, the net book value x the linear rate x the coefficient
     * (pro rata of the months in the year), switching to linear on the remaining value when that is
     * higher; each year's amount spread evenly over its months. The last month absorbs rounding.
     */
    static List<Month> schedule(BigDecimal cost, BigDecimal residual, String method, int life, BigDecimal coefficient,
                                LocalDate start) {
        List<Month> out = new ArrayList<>();
        BigDecimal base = cost.subtract(residual);
        YearMonth ym = YearMonth.from(start);
        if (!DECLINING.equals(method)) {
            BigDecimal monthly = base.divide(BigDecimal.valueOf(life), 2, RoundingMode.HALF_UP);
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < life; i++) {
                BigDecimal amount = i == life - 1 ? base.subtract(sum) : monthly;
                sum = sum.add(amount);
                out.add(new Month(ym.plusMonths(i), amount));
            }
            return out;
        }
        BigDecimal yearlyRate = coefficient.multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(life), 10, RoundingMode.HALF_UP);
        BigDecimal nbv = cost;
        int remaining = life;
        while (remaining > 0) {
            int k = Math.min(remaining, 13 - ym.getMonthValue());
            BigDecimal declining = nbv.multiply(yearlyRate).multiply(BigDecimal.valueOf(k)).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            BigDecimal linear = nbv.subtract(residual).multiply(BigDecimal.valueOf(k)).divide(BigDecimal.valueOf(remaining), 10, RoundingMode.HALF_UP);
            BigDecimal amount = k == remaining ? nbv.subtract(residual)
                    : declining.max(linear).min(nbv.subtract(residual)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal monthly = amount.divide(BigDecimal.valueOf(k), 2, RoundingMode.HALF_UP);
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < k; i++) {
                BigDecimal m = i == k - 1 ? amount.subtract(sum) : monthly;
                sum = sum.add(m);
                out.add(new Month(ym.plusMonths(i), m));
            }
            nbv = nbv.subtract(amount);
            remaining -= k;
            ym = ym.plusMonths(k);
        }
        return out;
    }

    private static List<Month> schedule(AssetRow a) {
        return schedule(a.cost(), a.residual(), a.method(), a.life(), a.coefficient(), a.startDate());
    }

    @Transactional
    public List<LedgerClosingDtos.ScheduleRow> scheduleOf(UUID id) {
        UUID orgId = org();
        AssetRow a = asset(orgId, id);
        Map<YearMonth, UUID> posted = new HashMap<>();
        jdbc.query("SELECT year, month, entry_id FROM ledger_depreciation WHERE organization_id = ? AND asset_id = ?", rs -> {
            posted.put(YearMonth.of(rs.getInt(1), rs.getInt(2)), rs.getObject(3, UUID.class));
        }, orgId, id);
        List<LedgerClosingDtos.ScheduleRow> out = new ArrayList<>();
        BigDecimal cumulated = BigDecimal.ZERO;
        for (Month m : schedule(a)) {
            if (a.disposedAt() != null && m.month().isAfter(YearMonth.from(a.disposedAt()))) break;
            cumulated = cumulated.add(m.amount());
            out.add(new LedgerClosingDtos.ScheduleRow(m.month().getYear(), m.month().getMonthValue(), m.amount(), cumulated,
                    a.cost().subtract(cumulated), posted.get(m.month())));
        }
        return out;
    }

    /** Posts every month up to {@code upTo} not yet posted, for every active asset. Idempotent. */
    @Transactional
    public LedgerClosingDtos.DepreciationResult depreciate(LedgerClosingDtos.DepreciateRequest r) {
        UUID orgId = org();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        List<AssetRow> assets = jdbc.query(SELECT + " WHERE organization_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'"
                + " ORDER BY code", FixedAssetService::row, orgId);
        return run(orgId, assets, r.upTo(), false);
    }

    /**
     * Posts the unposted months of these assets whose last day is on or before {@code upTo} (and,
     * with {@code partialLastMonth}, the month of {@code upTo} itself, dated {@code upTo}): one OD
     * entry per month, one line pair per asset.
     */
    private LedgerClosingDtos.DepreciationResult run(UUID orgId, List<AssetRow> assets, LocalDate upTo, boolean partialLastMonth) {
        Map<YearMonth, List<Object[]>> byMonth = new TreeMap<>();
        for (AssetRow a : assets) {
            java.util.Set<YearMonth> done = new java.util.HashSet<>();
            jdbc.query("SELECT year, month FROM ledger_depreciation WHERE organization_id = ? AND asset_id = ?", rs -> {
                done.add(YearMonth.of(rs.getInt(1), rs.getInt(2)));
            }, orgId, a.id());
            for (Month m : schedule(a)) {
                boolean due = !m.month().atEndOfMonth().isAfter(upTo)
                        || (partialLastMonth && m.month().equals(YearMonth.from(upTo)));
                if (!due) break;
                if (done.contains(m.month()) || m.amount().signum() == 0) continue;
                byMonth.computeIfAbsent(m.month(), k -> new ArrayList<>()).add(new Object[]{a, m.amount()});
            }
        }
        int months = 0, entries = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<YearMonth, List<Object[]>> e : byMonth.entrySet()) {
            YearMonth month = e.getKey();
            LocalDate date = month.atEndOfMonth().isAfter(upTo) ? upTo : month.atEndOfMonth();
            SignedLines lines = new SignedLines();
            StringBuilder ids = new StringBuilder();
            for (Object[] o : e.getValue()) {
                AssetRow a = (AssetRow) o[0];
                BigDecimal amount = (BigDecimal) o[1];
                lines.add(a.expenseAccountId(), null, a.costCenterId(), amount, "Dotation " + a.code() + " " + month);
                lines.add(a.depreciationAccountId(), null, null, amount.negate(), "Amortissement " + a.code() + " " + month);
                ids.append(a.id()).append(',');
            }
            LedgerEntry entry = posting.post(orgId, "OD", date, "Dotations aux amortissements " + month, DEPRECIATION,
                    SignedLines.sourceId(orgId, "DEPRECIATION|" + month + "|" + ids), "Amortissements " + month, lines.lines());
            entries++;
            for (Object[] o : e.getValue()) {
                AssetRow a = (AssetRow) o[0];
                BigDecimal amount = (BigDecimal) o[1];
                jdbc.update("INSERT INTO ledger_depreciation (organization_id, asset_id, year, month, amount, entry_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", orgId, a.id(), month.getYear(), month.getMonthValue(), amount, entry.getId());
                months++;
                total = total.add(amount);
            }
        }
        for (AssetRow a : assets) {
            BigDecimal acc = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_depreciation WHERE organization_id = ?"
                    + " AND asset_id = ?", BigDecimal.class, orgId, a.id());
            if (acc != null && acc.compareTo(a.cost().subtract(a.residual())) >= 0 && "ACTIVE".equals(a.status())) {
                jdbc.update("UPDATE ledger_fixed_asset SET status = 'FULLY_DEPRECIATED', updated_at = now()"
                        + " WHERE organization_id = ? AND id = ?", orgId, a.id());
            }
        }
        return new LedgerClosingDtos.DepreciationResult(months, entries, SignedLines.money(total));
    }

    // Disposal

    @Transactional
    public LedgerClosingDtos.Asset dispose(UUID id, LedgerClosingDtos.DisposeRequest r) {
        UUID orgId = org();
        jdbc.queryForList("SELECT organization_id FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        AssetRow a = asset(orgId, id);
        if ("DISPOSED".equals(a.status())) throw new IllegalArgumentException("This asset is already disposed of");
        if (r.date().isBefore(a.acquisitionDate())) {
            throw new IllegalArgumentException("The disposal date is before the acquisition date");
        }
        posting.checkNotLocked(orgId, r.date());
        String paidTo = r.paidTo() == null || r.paidTo().isBlank() ? "BANK" : r.paidTo().trim().toUpperCase(Locale.ROOT);
        if (!paidTo.equals("BANK") && !paidTo.equals("SUSPENSE")) throw new IllegalArgumentException("paidTo is BANK or SUSPENSE");
        // Depreciation up to the disposal month, that month dated on the disposal day.
        run(orgId, List.of(a), r.date(), true);
        BigDecimal acc = SignedLines.money(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_depreciation"
                + " WHERE organization_id = ? AND asset_id = ?", BigDecimal.class, orgId, id));
        BigDecimal nbv = a.cost().subtract(acc);
        BigDecimal price = SignedLines.money(r.price());
        UUID money = paidTo.equals("SUSPENSE") ? posting.accountId(orgId, "4497")
                : postingSetup.bankLedgerAccount(orgId, postingSetup.defaultBankAccountId(orgId));
        SignedLines lines = new SignedLines()
                .add(a.depreciationAccountId(), null, null, acc, "Amortissements cumulés " + a.code())
                .add(posting.accountId(orgId, "6513"), null, null, nbv, "VNA " + a.code())
                .add(money, null, null, price, "Prix de cession " + a.code())
                .add(a.assetAccountId(), null, null, a.cost().negate(), "Sortie " + a.code())
                .add(posting.accountId(orgId, "7513"), null, null, price.negate(), "Produit de cession " + a.code());
        posting.postManual(orgId, "OD", r.date(), "Cession " + a.code() + " " + a.name(), SOURCE, disposalId(orgId, id),
                a.code(), lines.lines());
        jdbc.update("UPDATE ledger_fixed_asset SET status = 'DISPOSED', disposed_at = ?, disposal_price = ?, updated_at = now()"
                + " WHERE organization_id = ? AND id = ?", r.date(), price, orgId, id);
        return get(id);
    }

    static UUID disposalId(UUID orgId, UUID assetId) {
        return SignedLines.sourceId(orgId, "DISPOSAL|" + assetId);
    }

    // Register

    @Transactional
    public LedgerClosingDtos.Register register(int year) {
        UUID orgId = org();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<AssetRow> assets = jdbc.query(SELECT + " WHERE organization_id = ? AND deleted_at IS NULL AND acquisition_date <= ?"
                + " AND (disposed_at IS NULL OR disposed_at >= ?) ORDER BY category, code", FixedAssetService::row, orgId, end, start);
        List<LedgerClosingDtos.RegisterRow> rows = new ArrayList<>();
        Map<String, BigDecimal[]> byCategory = new LinkedHashMap<>();
        BigDecimal[] all = zeros();
        for (AssetRow a : assets) {
            BigDecimal before = BigDecimal.ZERO, during = BigDecimal.ZERO;
            YearMonth lastMonth = a.disposedAt() == null ? null : YearMonth.from(a.disposedAt());
            for (Month m : schedule(a)) {
                if (lastMonth != null && m.month().isAfter(lastMonth)) break;
                if (m.month().getYear() < year) before = before.add(m.amount());
                else if (m.month().getYear() == year) during = during.add(m.amount());
            }
            BigDecimal accEnd = before.add(during);
            BigDecimal nbv = a.cost().subtract(accEnd);
            BigDecimal rate = BigDecimal.valueOf(1200).divide(BigDecimal.valueOf(a.life()), 4, RoundingMode.HALF_UP);
            if (DECLINING.equals(a.method()) && a.coefficient() != null) rate = rate.multiply(a.coefficient());
            LedgerClosingDtos.RegisterDisposal disposal = a.disposedAt() == null || a.disposedAt().isAfter(end) ? null
                    : new LedgerClosingDtos.RegisterDisposal(a.disposedAt(), a.disposalPrice(), nbv,
                    SignedLines.money(a.disposalPrice()).subtract(nbv));
            rows.add(new LedgerClosingDtos.RegisterRow(a.id(), a.code(), a.name(), a.category(), a.acquisitionDate(), a.cost(),
                    a.method(), rate.setScale(2, RoundingMode.HALF_UP), SignedLines.money(before), SignedLines.money(during),
                    SignedLines.money(accEnd), SignedLines.money(nbv), disposal));
            BigDecimal[] t = byCategory.computeIfAbsent(a.category(), k -> zeros());
            for (BigDecimal[] x : List.of(t, all)) {
                x[0] = x[0].add(a.cost());
                x[1] = x[1].add(before);
                x[2] = x[2].add(during);
                x[3] = x[3].add(accEnd);
                x[4] = x[4].add(nbv);
            }
        }
        List<LedgerClosingDtos.RegisterTotals> categories = new ArrayList<>();
        byCategory.forEach((k, t) -> categories.add(totals(k, t)));
        return new LedgerClosingDtos.Register(year, rows, categories, totals(null, all));
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static LedgerClosingDtos.RegisterTotals totals(String category, BigDecimal[] t) {
        return new LedgerClosingDtos.RegisterTotals(category, SignedLines.money(t[0]), SignedLines.money(t[1]),
                SignedLines.money(t[2]), SignedLines.money(t[3]), SignedLines.money(t[4]));
    }
}
