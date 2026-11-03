package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.service.AgingService;
import com.bento.crm.ledger.dto.LedgerStatementDtos.*;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customer and supplier subledgers: the balances per partner and a partner's statement, from the
 * posted lines that carry a partner.
 *
 * <p>Customer accounts are the receivable accounts of the customer posting groups and every 342x;
 * their advances are 4421x. Supplier accounts are the payable accounts of the supplier posting
 * groups and every 441x; their advances are 3411x. Customers read debit minus credit (positive:
 * they owe us), suppliers credit minus debit (positive: we owe them); advances are shown positive.
 *
 * <p>A carry-forward dated on the first day of the period counts as the opening balance, not as a
 * movement, and the movements before the last carry-forward are left out (it holds them).
 */
@Service
@RequiredArgsConstructor
public class LedgerPartnerService {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPPLIER = "SUPPLIER";
    private static final BigDecimal ZERO = LedgerStatementsService.ZERO;
    private static final List<String> BUCKETS = List.of("current", "d30", "d60", "d90", "over90");

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerReportService reports;
    private final AgingService aging;
    private final PartnerRepository partnerRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    static String type(String type) {
        String t = type == null ? CUSTOMER : type.trim().toUpperCase(Locale.ROOT);
        if (t.equals("VENDOR")) t = SUPPLIER;
        if (!t.equals(CUSTOMER) && !t.equals(SUPPLIER)) {
            throw new IllegalArgumentException("type is CUSTOMER or SUPPLIER");
        }
        return t;
    }

    /** The partner accounts of one side: the main (receivable or payable) ones and the advance ones. */
    record Accounts(Set<UUID> main, Set<UUID> advances) {
        Set<UUID> all() {
            Set<UUID> s = new LinkedHashSet<>(main);
            s.addAll(advances);
            return s;
        }
    }

    Accounts accounts(UUID orgId, String type) {
        boolean customer = CUSTOMER.equals(type);
        Set<UUID> main = new LinkedHashSet<>(jdbc.queryForList("SELECT account_id FROM ledger_partner_group"
                + " WHERE organization_id = ? AND side = ? AND account_id IS NOT NULL", UUID.class, orgId, type));
        main.addAll(jdbc.queryForList("SELECT id FROM ledger_account WHERE organization_id = ? AND code LIKE ?",
                UUID.class, orgId, (customer ? "342" : "441") + "%"));
        Set<UUID> adv = new LinkedHashSet<>(jdbc.queryForList("SELECT id FROM ledger_account WHERE organization_id = ?"
                + " AND code LIKE ?", UUID.class, orgId, (customer ? "4421" : "3411") + "%"));
        main.removeAll(adv);
        return new Accounts(main, adv);
    }

    private static String in(Collection<?> ids) {
        return ids.stream().map(i -> "?").collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * The shared line filter: live lines with a partner on the given accounts, up to {@code to},
     * from the last carry-forward on or before {@code from}, without the carry-forwards inside the
     * period (the period already holds what they carry). Params: org, accounts..., to, floor, from.
     */
    private static String filter(Collection<UUID> accountIds) {
        return " FROM ledger_entry_line l"
                + " JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL"
                + " JOIN ledger_journal j ON j.id = e.journal_id"
                + " WHERE l.organization_id = ? AND l.deleted_at IS NULL AND l.partner_id IS NOT NULL"
                + " AND l.account_id IN " + in(accountIds) + " AND e.entry_date <= ? AND e.entry_date >= ?"
                + " AND NOT (COALESCE(e.source_type, '') = 'YEAR_END' AND j.kind = 'OPENING' AND e.entry_date > ?"
                + " AND e.reversed_by_id IS NULL AND e.reversal_of_id IS NULL)";
    }

    private List<Object> params(UUID orgId, Collection<UUID> accountIds, LedgerReportService.Period p) {
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        params.addAll(accountIds);
        params.add(p.to());
        params.add(posting.carryForwardFloor(orgId, p.from()));
        params.add(p.from());
        return params;
    }

    /** True for the lines that make the opening: before the period, or the carry-forward on its first day. */
    private static final String OPENING = "(e.entry_date < ? OR (COALESCE(e.source_type, '') = 'YEAR_END' AND j.kind = 'OPENING'"
            + " AND e.entry_date = ?))";

    // Balances

    private static final class Acc {
        UUID partnerId;
        String name;
        BigDecimal opening = ZERO, debit = ZERO, credit = ZERO, advances = ZERO;
        BigDecimal[] aging = {ZERO, ZERO, ZERO, ZERO, ZERO};
    }

    @Transactional
    public PartnerBalances balances(String type, LocalDate from, LocalDate to, String search, boolean onlyOpen,
                                    int page, int size) {
        return page(type, from, to, search, onlyOpen, Math.max(page, 0), size <= 0 ? 50 : Math.min(size, 1000));
    }

    private PartnerBalances page(String type, LocalDate from, LocalDate to, String search, boolean onlyOpen,
                                 int pg, int sz) {
        UUID orgId = org();
        String t = type(type);
        LedgerReportService.Period p = reports.period(orgId, from, to);
        List<PartnerBalance> all = compute(orgId, t, p, search, onlyOpen);
        BigDecimal[] tot = {ZERO, ZERO, ZERO, ZERO, ZERO, ZERO};
        BigDecimal[] ag = {ZERO, ZERO, ZERO, ZERO, ZERO};
        for (PartnerBalance b : all) {
            tot[0] = tot[0].add(b.opening());
            tot[1] = tot[1].add(b.debit());
            tot[2] = tot[2].add(b.credit());
            tot[3] = tot[3].add(b.closing());
            tot[4] = tot[4].add(b.advances());
            tot[5] = tot[5].add(b.overdue());
            ag[0] = ag[0].add(b.aging().current());
            ag[1] = ag[1].add(b.aging().d30());
            ag[2] = ag[2].add(b.aging().d60());
            ag[3] = ag[3].add(b.aging().d90());
            ag[4] = ag[4].add(b.aging().over90());
        }
        int fromIdx = (int) Math.min((long) pg * sz, all.size());
        int toIdx = (int) Math.min((long) fromIdx + sz, all.size());
        return new PartnerBalances(t, p.from(), p.to(), all.subList(fromIdx, toIdx),
                new PartnerBalanceTotals(tot[0], tot[1], tot[2], tot[3], tot[4], tot[5],
                        new Aging(ag[0], ag[1], ag[2], ag[3], ag[4])),
                pg, sz, all.size(), (int) ((all.size() + (long) sz - 1) / sz));
    }

    /** Every partner's balance of the period, sorted by name (for the list and the exports). */
    List<PartnerBalance> compute(UUID orgId, String t, LedgerReportService.Period p, String search, boolean onlyOpen) {
        Accounts accounts = accounts(orgId, t);
        boolean customer = CUSTOMER.equals(t);
        Map<UUID, Acc> byPartner = new LinkedHashMap<>();
        if (!accounts.all().isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(p.from(), p.from(), p.from(), p.from(), p.from(), p.from()));
            params.addAll(params(orgId, accounts.all(), p));
            jdbc.query("SELECT l.partner_id, pt.name, l.account_id,"
                    + " COALESCE(SUM(l.debit - l.credit) FILTER (WHERE " + OPENING + "), 0),"
                    + " COALESCE(SUM(l.debit) FILTER (WHERE NOT " + OPENING + "), 0),"
                    + " COALESCE(SUM(l.credit) FILTER (WHERE NOT " + OPENING + "), 0)"
                    + filter(accounts.all()).replace(" WHERE ", " LEFT JOIN partner pt ON pt.id = l.partner_id WHERE ")
                    + " GROUP BY l.partner_id, pt.name, l.account_id", rs -> {
                UUID partnerId = rs.getObject(1, UUID.class);
                Acc a = byPartner.computeIfAbsent(partnerId, k -> new Acc());
                a.partnerId = partnerId;
                a.name = rs.getString(2);
                UUID account = rs.getObject(3, UUID.class);
                BigDecimal opening = rs.getBigDecimal(4);
                BigDecimal debit = rs.getBigDecimal(5);
                BigDecimal credit = rs.getBigDecimal(6);
                if (accounts.advances().contains(account)) {
                    BigDecimal closing = opening.add(debit).subtract(credit);
                    a.advances = a.advances.add(customer ? closing.negate() : closing);
                } else {
                    a.opening = a.opening.add(customer ? opening : opening.negate());
                    a.debit = a.debit.add(debit);
                    a.credit = a.credit.add(credit);
                }
            }, params.toArray());
        }
        if (!byPartner.isEmpty()) {
            Invoice.InvoiceType invoiceType = customer ? Invoice.InvoiceType.CUSTOMER : Invoice.InvoiceType.VENDOR;
            for (AgingService.OpenItem o : aging.openAt(orgId, invoiceType, null, p.to())) {
                Acc a = byPartner.get(o.invoice().getPartnerId());
                if (a == null) continue;
                a.aging[o.bucket()] = a.aging[o.bucket()].add(o.due());
            }
        }
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<PartnerBalance> out = new ArrayList<>();
        for (Acc a : byPartner.values()) {
            String name = a.name == null ? "—" : a.name;
            if (q != null && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
            BigDecimal movement = customer ? a.debit.subtract(a.credit) : a.credit.subtract(a.debit);
            BigDecimal closing = LedgerStatementsService.money(a.opening.add(movement));
            BigDecimal advances = LedgerStatementsService.money(a.advances);
            if (onlyOpen && closing.signum() == 0 && advances.signum() == 0) continue;
            BigDecimal overdue = a.aging[1].add(a.aging[2]).add(a.aging[3]).add(a.aging[4]);
            out.add(new PartnerBalance(a.partnerId, name, LedgerStatementsService.money(a.opening),
                    LedgerStatementsService.money(a.debit), LedgerStatementsService.money(a.credit), closing, advances,
                    LedgerStatementsService.money(overdue), aging(a.aging)));
        }
        out.sort(Comparator.comparing((PartnerBalance b) -> b.partnerName().toLowerCase(Locale.ROOT))
                .thenComparing(b -> b.partnerId().toString()));
        return out;
    }

    private static Aging aging(BigDecimal[] a) {
        return new Aging(LedgerStatementsService.money(a[0]), LedgerStatementsService.money(a[1]),
                LedgerStatementsService.money(a[2]), LedgerStatementsService.money(a[3]),
                LedgerStatementsService.money(a[4]));
    }

    /** The balances of one side over a period, for the exports. */
    @Transactional
    public PartnerBalances allBalances(String type, LocalDate from, LocalDate to, String search, boolean onlyOpen) {
        return page(type, from, to, search, onlyOpen, 0, Integer.MAX_VALUE);
    }

    // Statement

    @Transactional
    public PartnerStatement statement(UUID partnerId, String type, LocalDate from, LocalDate to) {
        UUID orgId = org();
        Partner partner = partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
        String t = type != null ? type(type) : partner.getType() == Partner.PartnerType.VENDOR ? SUPPLIER : CUSTOMER;
        boolean customer = CUSTOMER.equals(t);
        LedgerReportService.Period p = reports.period(orgId, from, to);
        Accounts accounts = accounts(orgId, t);

        BigDecimal[] opening = {ZERO, ZERO};
        List<StatementLine> mainLines = new ArrayList<>();
        List<StatementLine> advLines = new ArrayList<>();
        if (!accounts.all().isEmpty()) {
            List<Object> params = new ArrayList<>(params(orgId, accounts.all(), p));
            params.add(p.from());
            params.add(p.from());
            params.add(partnerId);
            jdbc.query("SELECT l.account_id, SUM(l.debit - l.credit)" + filter(accounts.all())
                    + " AND " + OPENING + " AND l.partner_id = ? GROUP BY l.account_id", rs -> {
                boolean adv = accounts.advances().contains(rs.getObject(1, UUID.class));
                BigDecimal b = rs.getBigDecimal(2);
                // Main accounts: customers d - c, suppliers c - d; advances the other way round.
                BigDecimal signed = customer != adv ? b : b.negate();
                opening[adv ? 1 : 0] = opening[adv ? 1 : 0].add(signed);
            }, params.toArray());

            params = new ArrayList<>(params(orgId, accounts.all(), p));
            params.add(p.from());
            params.add(p.from());
            params.add(partnerId);
            String sql = "SELECT l.account_id, e.entry_date, e.id, e.number, j.code, e.source_type, e.source_id,"
                    + " i.invoice_number, COALESCE(l.label, e.description), l.debit, l.credit"
                    + filter(accounts.all()).replace(" WHERE ", " LEFT JOIN invoice i ON i.id = e.source_id"
                    + " AND i.organization_id = e.organization_id AND e.source_type IN ('INVOICE', 'BILL', 'ADVANCE', 'CREDIT_NOTE')"
                    + " WHERE ")
                    + " AND NOT " + OPENING + " AND l.partner_id = ?"
                    + " ORDER BY e.entry_date, e.number, l.line_no";
            BigDecimal[] running = {opening[0], opening[1]};
            jdbc.query(sql, rs -> {
                boolean adv = accounts.advances().contains(rs.getObject(1, UUID.class));
                BigDecimal debit = rs.getBigDecimal(10);
                BigDecimal credit = rs.getBigDecimal(11);
                BigDecimal delta = customer != adv ? debit.subtract(credit) : credit.subtract(debit);
                int k = adv ? 1 : 0;
                running[k] = running[k].add(delta);
                StatementLine line = new StatementLine(rs.getObject(2, LocalDate.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                        rs.getString(9), debit, credit, LedgerStatementsService.money(running[k]));
                (adv ? advLines : mainLines).add(line);
            }, params.toArray());
        }
        BigDecimal open = LedgerStatementsService.money(opening[0]);
        BigDecimal closing = mainLines.isEmpty() ? open : mainLines.get(mainLines.size() - 1).balance();
        BigDecimal advOpen = LedgerStatementsService.money(opening[1]);
        BigDecimal advClose = advLines.isEmpty() ? advOpen : advLines.get(advLines.size() - 1).balance();

        List<OpenDocument> docs = new ArrayList<>();
        BigDecimal[] ag = {ZERO, ZERO, ZERO, ZERO, ZERO};
        Invoice.InvoiceType invoiceType = customer ? Invoice.InvoiceType.CUSTOMER : Invoice.InvoiceType.VENDOR;
        for (AgingService.OpenItem o : aging.openAt(orgId, invoiceType, partnerId, p.to())) {
            Invoice i = o.invoice();
            String docType = i.getKind() == Invoice.Kind.ADVANCE ? "ADVANCE" : customer ? "INVOICE" : "BILL";
            docs.add(new OpenDocument(docType, i.getId(), i.getInvoiceNumber(), i.getInvoiceDate(), i.getDueDate(),
                    LedgerStatementsService.money(i.getTotal()), o.due(), o.daysOverdue(), BUCKETS.get(o.bucket())));
            ag[o.bucket()] = ag[o.bucket()].add(o.due());
        }
        return new PartnerStatement(t, partnerOf(partner), p.from(), p.to(), open, mainLines, closing,
                new StatementAdvances(advOpen, advLines, advClose), docs, aging(ag));
    }

    private static StatementPartner partnerOf(Partner partner) {
        Map<String, Object> company = partner.getCompany() == null ? new HashMap<>() : partner.getCompany();
        Object ice = company.get("ice");
        Object address = company.get("address");
        String addr = address != null && !address.toString().isBlank() ? address.toString()
                : partner.getCity() == null ? null
                : partner.getCity() + (partner.getCountry() != null ? ", " + partner.getCountry() : "");
        String name = partner.getCompanyName() != null && !partner.getCompanyName().isBlank()
                ? partner.getCompanyName() : partner.getName();
        return new StatementPartner(partner.getId(), name, ice == null ? null : ice.toString(), addr);
    }

    public Partner partner(UUID partnerId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
    }
}
