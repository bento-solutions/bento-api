package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.ledger.service.LedgerPostingSetupService.Snapshot;
import com.bento.crm.ledger.service.LedgerPostingSetupService.VatRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VAT summary of a period and its settlement entry.
 *
 * <p>Collected VAT: under DEBIT, the VAT customer invoices, advance invoices and credit notes
 * posted on the collected accounts in the period (read from the ledger); under ENCAISSEMENT, the
 * VAT share of the customer payments received in the period, pro rata of each invoice's VAT per
 * code (an advance payment with no invoice at the partner's default rate). Recoverable VAT, both
 * regimes: the VAT share of the supplier bills paid in the period plus the VAT of the expenses of
 * the period. The settlement (OD, on the last day) clears the declared amounts against 4456 (VAT
 * due) or 34558 (VAT credit), using the credit carried in first.
 */
@Service
@RequiredArgsConstructor
public class LedgerVatService {

    public static final String SOURCE = "VAT_SETTLEMENT";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerPostingSetupService postingSetup;
    private final LedgerClosingService closing;
    private final VatDeterminationService vatDetermination;
    private final InvoiceRepository invoiceRepository;
    private final LedgerEntryRepository entryRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    /** Base and VAT per code, unrounded until the end. */
    private static final class Buckets {
        final Map<String, BigDecimal[]> byCode = new LinkedHashMap<>();

        void add(String code, BigDecimal base, BigDecimal vat) {
            if (code == null) return;
            BigDecimal[] b = byCode.computeIfAbsent(code, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            b[0] = b[0].add(base);
            b[1] = b[1].add(vat);
        }

        List<LedgerClosingDtos.VatLine> lines(Snapshot s) {
            List<LedgerClosingDtos.VatLine> out = new ArrayList<>();
            byCode.forEach((code, b) -> {
                BigDecimal base = SignedLines.money(b[0]);
                BigDecimal vat = SignedLines.money(b[1]);
                if (base.signum() == 0 && vat.signum() == 0) return;
                VatRow r = s.vatByCode(code);
                out.add(new LedgerClosingDtos.VatLine(code, r == null ? null : r.rate().stripTrailingZeros(), base, vat));
            });
            out.sort(Comparator.comparing((LedgerClosingDtos.VatLine l) -> l.rate() == null ? BigDecimal.ZERO : l.rate())
                    .reversed());
            return out;
        }
    }

    private record Computed(LedgerClosingDtos.VatSummary summary, List<LedgerClosingDtos.VatLine> collected,
                            List<LedgerClosingDtos.VatLine> recoverable, BigDecimal creditUsed, BigDecimal due,
                            BigDecimal credit) {}

    // API

    @Transactional
    public LedgerClosingDtos.VatSummary summary(LocalDate from, LocalDate to) {
        UUID orgId = org();
        return compute(orgId, from, to).summary();
    }

    /** Posts the period's settlement; does nothing when one is already posted. */
    @Transactional
    public LedgerClosingDtos.VatSummary settle(LedgerClosingDtos.VatSettleRequest r) {
        UUID orgId = org();
        settle(orgId, r.from(), r.to(), true);
        return compute(orgId, r.from(), r.to()).summary();
    }

    @Transactional
    public LedgerClosingDtos.VatSummary cancel(LocalDate from, LocalDate to) {
        UUID orgId = org();
        check(from, to);
        LedgerEntry live = entryRepository.lockLive(orgId, SOURCE, sourceId(orgId, from, to))
                .orElseThrow(() -> new ResourceNotFoundException("No VAT settlement is posted for this period"));
        posting.reverse(orgId, live, live.getEntryDate(), LedgerPostingService.LockPolicy.REJECT);
        return compute(orgId, from, to).summary();
    }

    /**
     * The settlement a TVA deadline marked done posts, when the period has none yet. Skipped when
     * the period is locked or there is nothing to settle.
     */
    @Transactional
    public void settleForDeadline(UUID orgId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) return;
        setup.ensure(orgId);
        if (posting.isLocked(orgId, to)) return;
        settle(orgId, from, to, false);
    }

    private void settle(UUID orgId, LocalDate from, LocalDate to, boolean strict) {
        check(from, to);
        UUID sourceId = sourceId(orgId, from, to);
        if (entryRepository.lockLive(orgId, SOURCE, sourceId).isPresent()) {
            return;
        }
        if (strict) posting.checkNotLocked(orgId, to);
        Computed c = compute(orgId, from, to);
        Snapshot s = postingSetup.snapshot(orgId);
        SignedLines lines = new SignedLines();
        for (LedgerClosingDtos.VatLine l : c.collected()) {
            VatRow row = s.vatByCode(l.vatCode());
            UUID account = row != null ? row.collectedAccountId() : postingSetup.accountByCode(orgId, "4455");
            lines.add(account, null, null, l.vat(), "TVA collectée " + l.vatCode());
        }
        for (LedgerClosingDtos.VatLine l : c.recoverable()) {
            VatRow row = s.vatByCode(l.vatCode());
            UUID account = row != null ? row.recoverableAccountId() : postingSetup.accountByCode(orgId, "34552");
            lines.add(account, null, null, l.vat().negate(), "TVA récupérable " + l.vatCode());
        }
        UUID creditAccount = postingSetup.accountByCode(orgId, "34558");
        BigDecimal net = c.summary().totalCollected().subtract(c.summary().totalRecoverable());
        if (net.signum() > 0) {
            lines.add(creditAccount, null, null, c.creditUsed().negate(), "Crédit de TVA imputé");
        } else {
            lines.add(creditAccount, null, null, net.negate(), "Crédit de TVA à reporter");
        }
        lines.add(postingSetup.accountByCode(orgId, "4456"), null, null, c.due().negate(), "TVA due");
        if (!lines.postable()) {
            if (strict) throw new IllegalArgumentException("There is no VAT to settle for this period");
            return;
        }
        posting.postManual(orgId, "OD", to, "Déclaration de TVA du " + from + " au " + to, SOURCE, sourceId,
                "TVA " + from + " - " + to, lines.lines());
    }

    private static void check(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("'from' and 'to' are required");
        if (from.isAfter(to)) throw new IllegalArgumentException("'from' must be on or before 'to'");
    }

    static UUID sourceId(UUID orgId, LocalDate from, LocalDate to) {
        return SignedLines.sourceId(orgId, "VAT|" + from + "|" + to);
    }

    // Computation

    private Computed compute(UUID orgId, LocalDate from, LocalDate to) {
        check(from, to);
        Snapshot s = postingSetup.snapshot(orgId);
        String regime = closing.vatRegime(orgId);
        Buckets collected = new Buckets();
        Buckets recoverable = new Buckets();
        if (LedgerClosingService.DEBIT.equals(regime)) {
            debitCollected(orgId, s, from, to, collected);
        }
        payments(orgId, s, from, to, LedgerClosingService.ENCAISSEMENT.equals(regime) ? collected : null, recoverable);
        expenses(orgId, s, from, to, recoverable);

        List<LedgerClosingDtos.VatLine> col = collected.lines(s);
        List<LedgerClosingDtos.VatLine> rec = recoverable.lines(s);
        BigDecimal totalCollected = col.stream().map(LedgerClosingDtos.VatLine::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRecoverable = rec.stream().map(LedgerClosingDtos.VatLine::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID sourceId = sourceId(orgId, from, to);
        BigDecimal carriedIn = creditCarriedIn(orgId, to, sourceId);
        BigDecimal net = totalCollected.subtract(totalRecoverable);
        BigDecimal creditUsed = net.signum() > 0 ? net.min(carriedIn) : BigDecimal.ZERO;
        BigDecimal due = net.subtract(carriedIn).max(BigDecimal.ZERO);
        BigDecimal credit = carriedIn.subtract(net).max(BigDecimal.ZERO);
        UUID settlementId = entryRepository.findLive(orgId, SOURCE, sourceId).map(LedgerEntry::getId).orElse(null);
        List<UUID> deadlines = jdbc.queryForList("SELECT id FROM tax_deadline WHERE organization_id = ? AND partner_id IS NULL"
                + " AND kind = 'TVA' AND period_start = ? AND period_end = ? AND deleted_at IS NULL LIMIT 1",
                UUID.class, orgId, from, to);
        LedgerClosingDtos.VatSummary summary = new LedgerClosingDtos.VatSummary(from, to, regime, col, rec,
                SignedLines.money(totalCollected), SignedLines.money(totalRecoverable), SignedLines.money(carriedIn),
                SignedLines.money(due), SignedLines.money(credit), settlementId, deadlines.isEmpty() ? null : deadlines.get(0));
        return new Computed(summary, col, rec, SignedLines.money(creditUsed), SignedLines.money(due), SignedLines.money(credit));
    }

    /** DEBIT: the VAT on the collected accounts posted by customer invoices, advances and credit notes of the period. */
    private void debitCollected(UUID orgId, Snapshot s, LocalDate from, LocalDate to, Buckets out) {
        // Each collected account belongs to its highest-rate code (TVA20 -> 44551...).
        Map<UUID, VatRow> byAccount = new LinkedHashMap<>();
        for (VatRow r : s.vat()) {
            if (r.exempt() || r.rate().signum() == 0) continue;
            byAccount.merge(r.collectedAccountId(), r, (a, b) -> a.rate().compareTo(b.rate()) >= 0 ? a : b);
        }
        if (byAccount.isEmpty()) return;
        String in = String.join(", ", java.util.Collections.nCopies(byAccount.size(), "?"));
        List<Object> params = new ArrayList<>(List.of(orgId, from, to));
        params.addAll(byAccount.keySet());
        jdbc.query("""
                SELECT l.account_id, SUM(l.credit - l.debit) FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                LEFT JOIN ledger_entry o ON o.id = e.reversal_of_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                  AND COALESCE(o.source_type, e.source_type) IN ('INVOICE', 'ADVANCE', 'CREDIT_NOTE')
                  AND l.account_id IN (%s)
                GROUP BY l.account_id
                """.formatted(in), rs -> {
            VatRow r = byAccount.get(rs.getObject(1, UUID.class));
            BigDecimal vat = rs.getBigDecimal(2);
            out.add(r.code(), vat.multiply(HUNDRED).divide(r.rate(), 2, RoundingMode.HALF_UP), vat);
        }, params.toArray());
    }

    /**
     * Payments of the period: customer payments into {@code collected} (when not null), supplier
     * bill payments into {@code recoverable}, each pro rata of its invoice's VAT per code.
     */
    private void payments(UUID orgId, Snapshot s, LocalDate from, LocalDate to, Buckets collected, Buckets recoverable) {
        record Pay(UUID invoiceId, UUID partnerId, BigDecimal amount, boolean advance, String partnerType, BigDecimal bankSign) {}
        List<Pay> pays = jdbc.query("""
                SELECT p.invoice_id, p.partner_id, p.amount, p.advance, pt.type,
                       (SELECT bt.amount FROM bank_transaction bt WHERE bt.organization_id = p.organization_id
                          AND bt.payment_id = p.id AND bt.deleted_at IS NULL ORDER BY bt.created_at LIMIT 1)
                FROM payment p LEFT JOIN partner pt ON pt.id = p.partner_id
                WHERE p.organization_id = ? AND p.deleted_at IS NULL AND p.payment_date >= ? AND p.payment_date <= ?
                """, (rs, i) -> new Pay(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3),
                rs.getBoolean(4), rs.getString(5), rs.getBigDecimal(6)), orgId, from, to);
        Map<UUID, Map<String, BigDecimal[]>> cache = new HashMap<>();
        Map<UUID, Invoice> invoices = new HashMap<>();
        for (Pay p : pays) {
            if (p.amount() == null || p.amount().signum() == 0) continue;
            if (p.invoiceId() == null) {
                // An advance with no invoice yet: customer side at the partner's default rate.
                boolean supplier = p.bankSign() != null ? p.bankSign().signum() < 0 : "VENDOR".equals(p.partnerType());
                if (!p.advance() || supplier || collected == null) continue;
                VatDeterminationService.Choice c = vatDetermination.determine(orgId, s, p.partnerId(), null, null, new HashMap<>());
                BigDecimal fraction = c.fraction();
                BigDecimal base = p.amount().divide(BigDecimal.ONE.add(fraction), 6, RoundingMode.HALF_UP);
                collected.add(c.vatCode(), base, p.amount().subtract(base));
                continue;
            }
            Invoice inv = invoices.computeIfAbsent(p.invoiceId(),
                    id -> invoiceRepository.findByOrganizationIdAndId(orgId, id).orElse(null));
            if (inv == null || inv.getTotal() == null || inv.getTotal().signum() == 0) continue;
            boolean supplier = inv.getType() == Invoice.InvoiceType.VENDOR;
            Buckets target = supplier ? recoverable : collected;
            if (target == null) continue;
            Map<String, BigDecimal[]> perCode = cache.computeIfAbsent(inv.getId(), id -> documentVat(s, inv));
            BigDecimal factor = p.amount().divide(inv.getTotal(), 10, RoundingMode.HALF_UP);
            if (inv.getKind() == Invoice.Kind.CREDIT_NOTE) factor = factor.negate();
            for (Map.Entry<String, BigDecimal[]> e : perCode.entrySet()) {
                target.add(e.getKey(), e.getValue()[0].multiply(factor), e.getValue()[1].multiply(factor));
            }
        }
    }

    /** Base and VAT per code of a document: its lines, less the advances it deducts; its VAT total wins. */
    private static Map<String, BigDecimal[]> documentVat(Snapshot s, Invoice inv) {
        Map<String, BigDecimal[]> perCode = new LinkedHashMap<>();
        for (VatDeterminationService.BreakdownRow r : VatDeterminationService.breakdown(inv.getLines(), Map.of())) {
            String code = r.vatCode();
            if (code == null) {
                VatRow row = s.vatFor(r.rate().movePointLeft(2));
                code = row == null ? VatDeterminationService.DEFAULT_CODE : row.code();
            }
            BigDecimal[] b = perCode.computeIfAbsent(code, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            b[0] = b[0].add(r.base());
            b[1] = b[1].add(r.vat());
        }
        if (inv.getAdvanceDeductions() != null) {
            for (Map<String, Object> d : inv.getAdvanceDeductions()) {
                Object code = d.get("vatCode");
                String c = code == null ? VatDeterminationService.DEFAULT_CODE : code.toString();
                BigDecimal[] b = perCode.computeIfAbsent(c, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                b[0] = b[0].subtract(decimal(d.get("base")));
                b[1] = b[1].subtract(decimal(d.get("vat")));
            }
        }
        BigDecimal tax = inv.getTax();
        if (perCode.isEmpty() && tax != null && tax.signum() != 0) {
            BigDecimal base = inv.getTotal().subtract(tax);
            VatRow row = base.signum() == 0 ? null : s.vatFor(tax.divide(base, 2, RoundingMode.HALF_UP));
            perCode.put(row == null ? VatDeterminationService.DEFAULT_CODE : row.code(), new BigDecimal[]{base, tax});
        } else if (tax != null && !perCode.isEmpty()) {
            // The document's own VAT total wins: the difference goes to its largest code.
            BigDecimal sum = perCode.values().stream().map(b -> b[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal diff = tax.subtract(sum);
            if (diff.signum() != 0) {
                perCode.values().stream().max(Comparator.comparing(b -> b[1].abs())).ifPresent(b -> b[1] = b[1].add(diff));
            }
        }
        return perCode;
    }

    private static BigDecimal decimal(Object v) {
        BigDecimal d = VatDeterminationService.decimal(v);
        return d == null ? BigDecimal.ZERO : d;
    }

    /** Expenses of the period with VAT: their code's (TVA20 by default) recoverable VAT. */
    private void expenses(UUID orgId, Snapshot s, LocalDate from, LocalDate to, Buckets out) {
        jdbc.query("""
                SELECT amount, vat_amount, vat_code FROM expense WHERE organization_id = ? AND deleted_at IS NULL
                  AND expense_date >= ? AND expense_date <= ? AND vat_amount > 0
                """, rs -> {
            BigDecimal amount = rs.getBigDecimal(1);
            BigDecimal vat = rs.getBigDecimal(2).min(amount);
            String code = rs.getString(3);
            if (code == null || s.vatByCode(code) == null) code = VatDeterminationService.DEFAULT_CODE;
            out.add(code, amount.subtract(vat), vat);
        }, orgId, from, to);
    }

    /** The debit balance of 34558 on {@code to}, without the period's own settlement. */
    private BigDecimal creditCarriedIn(UUID orgId, LocalDate to, UUID sourceId) {
        UUID account = postingSetup.accountByCode(orgId, "34558");
        BigDecimal b = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                WHERE l.organization_id = ? AND l.account_id = ? AND l.deleted_at IS NULL
                  AND e.entry_date >= ? AND e.entry_date <= ?
                  AND e.id NOT IN (SELECT s.id FROM ledger_entry s WHERE s.organization_id = ? AND s.source_type = ? AND s.source_id = ?)
                  AND (e.reversal_of_id IS NULL OR e.reversal_of_id NOT IN
                       (SELECT s.id FROM ledger_entry s WHERE s.organization_id = ? AND s.source_type = ? AND s.source_id = ?))
                """, BigDecimal.class, orgId, account, posting.carryForwardFloor(orgId, to), to, orgId, SOURCE, sourceId,
                orgId, SOURCE, sourceId);
        return b == null ? BigDecimal.ZERO : b.max(BigDecimal.ZERO);
    }
}
