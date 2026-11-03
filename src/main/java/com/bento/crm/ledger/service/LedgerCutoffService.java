package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.ledger.service.LedgerPostingSetupService.Snapshot;
import com.bento.crm.ledger.service.LedgerPostingSetupService.VatRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cut-off assistant: it proposes the FNP (goods received, bill not yet in) and FAE (revenue
 * earned, invoice not yet issued) of a date, the user adds prepaid expenses (CCA) and revenue
 * billed in advance (PCA), and posting writes one OD entry on the date and its reversal on the
 * next day. Receipts and deliveries themselves post nothing.
 */
@Service
@RequiredArgsConstructor
public class LedgerCutoffService {

    public static final String SOURCE = "CUTOFF";
    public static final String REVERSAL_SOURCE = "CUTOFF_REVERSAL";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerPostingSetupService postingSetup;
    private final VatDeterminationService vatDetermination;
    private final LedgerEntryRepository entryRepository;
    private final LedgerEntryService entryService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    // Proposals

    @Transactional
    public LedgerClosingDtos.CutoffProposals proposals(LocalDate date) {
        UUID orgId = org();
        if (date == null) throw new IllegalArgumentException("The date is required");
        Snapshot s = postingSetup.snapshot(orgId);
        Map<UUID, String> codes = accountCodes(orgId);
        return new LedgerClosingDtos.CutoffProposals(date, fnp(orgId, s, date, codes), fae(orgId, s, date, codes),
                List.of(), List.of());
    }

    private record PartnerGroups(String name, UUID businessGroupId) {}

    private PartnerGroups partner(UUID orgId, UUID partnerId) {
        if (partnerId == null) return new PartnerGroups(null, null);
        List<PartnerGroups> rows = jdbc.query("SELECT COALESCE(NULLIF(company_name, ''), name), business_group_id FROM partner"
                + " WHERE organization_id = ? AND id = ?", (rs, i) -> new PartnerGroups(rs.getString(1),
                rs.getObject(2, UUID.class)), orgId, partnerId);
        return rows.isEmpty() ? new PartnerGroups(null, null) : rows.get(0);
    }

    /** Purchase orders delivered on or before the date whose bill is not in by then. */
    private List<LedgerClosingDtos.CutoffItem> fnp(UUID orgId, Snapshot s, LocalDate date, Map<UUID, String> codes) {
        List<LedgerClosingDtos.CutoffItem> out = new ArrayList<>();
        jdbc.query("""
                SELECT po.id, po.order_number, po.vendor_partner_id, po.subtotal, po.tax, po.lines::text FROM purchase_order po
                WHERE po.organization_id = ? AND po.deleted_at IS NULL
                  AND COALESCE(po.delivery_date, po.order_date, CAST(po.updated_at AS date)) <= ?
                  AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.organization_id = po.organization_id AND i.purchase_order_id = po.id
                                  AND i.deleted_at IS NULL AND i.status <> 'DRAFT' AND i.invoice_date <= ?)
                  AND (po.status = 'DELIVERED' OR (po.status = 'INVOICED' AND EXISTS (SELECT 1 FROM invoice i
                       WHERE i.organization_id = po.organization_id AND i.purchase_order_id = po.id AND i.deleted_at IS NULL
                         AND i.status <> 'DRAFT' AND i.invoice_date > ?)))
                ORDER BY po.delivery_date, po.order_number
                """, rs -> {
            BigDecimal base = rs.getBigDecimal(4);
            if (base == null || base.signum() <= 0) return;
            BigDecimal vat = rs.getBigDecimal(5) == null ? BigDecimal.ZERO : rs.getBigDecimal(5);
            UUID poId = rs.getObject(1, UUID.class);
            UUID partnerId = rs.getObject(3, UUID.class);
            PartnerGroups p = partner(orgId, partnerId);
            UUID group = productGroupOfFirstLine(orgId, s, rs.getString(6));
            UUID account = s.general(s.businessGroup(p.businessGroupId()), group).purchaseAccountId();
            VatRow row = vat.signum() == 0 ? null : s.vatFor(vat.divide(base, 2, RoundingMode.HALF_UP));
            out.add(new LedgerClosingDtos.CutoffItem("FNP:" + poId, "PURCHASE_ORDER", poId, partnerId, p.name(),
                    "FNP " + (rs.getString(2) == null ? "" : rs.getString(2)) + (p.name() == null ? "" : " - " + p.name()),
                    SignedLines.money(base), row == null ? (vat.signum() == 0 ? null : VatDeterminationService.DEFAULT_CODE) : row.code(),
                    SignedLines.money(vat), account, codes.get(account)));
        }, orgId, date, date, date);
        return out;
    }

    private UUID productGroupOfFirstLine(UUID orgId, Snapshot s, String linesJson) {
        try {
            List<Map<String, Object>> lines = linesJson == null ? List.of()
                    : objectMapper.readValue(linesJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> l : lines) {
                Object pid = l == null ? null : l.get("productId");
                if (pid == null) continue;
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT product_group_id, track_stock FROM product"
                        + " WHERE organization_id = ? AND id = ?", orgId, UUID.fromString(pid.toString()));
                if (rows.isEmpty()) break;
                UUID g = (UUID) rows.get(0).get("product_group_id");
                if (g != null && s.hasProductGroup(g)) return g;
                return Boolean.TRUE.equals(rows.get(0).get("track_stock")) ? s.productGroupByCode("GOODS") : s.productGroup(null);
            }
        } catch (Exception e) {
            // Unreadable lines: the default group.
        }
        return s.productGroup(null);
    }

    /**
     * Won deals not invoiced by then (net of advances), delivered notes not invoiced by then (once
     * per deal, in place of the deal), rebillable costs not invoiced, recurring invoices due.
     */
    private List<LedgerClosingDtos.CutoffItem> fae(UUID orgId, Snapshot s, LocalDate date, Map<UUID, String> codes) {
        List<LedgerClosingDtos.CutoffItem> out = new ArrayList<>();
        Map<UUID, Map<String, Object>> products = new HashMap<>();
        jdbc.query("""
                SELECT d.id, d.title, d.partner_id, COALESCE(d.amount, d.order_total_amount, 0) - COALESCE(d.discount, 0),
                       (SELECT COALESCE(SUM(i.subtotal), 0) FROM invoice i WHERE i.organization_id = d.organization_id
                          AND i.deal_id = d.id AND i.kind = 'ADVANCE' AND i.type = 'CUSTOMER' AND i.deleted_at IS NULL
                          AND i.status NOT IN ('DRAFT', 'CREDITED') AND i.invoice_date <= ?)
                FROM deal d
                WHERE d.organization_id = ? AND d.deleted_at IS NULL AND d.stage IN ('CLOSED_WON', 'AWAITING_INVOICING')
                  AND COALESCE(d.delivery_date, d.order_date, CAST(d.updated_at AS date)) <= ?
                  AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.organization_id = d.organization_id AND i.deal_id = d.id
                                  AND i.kind = 'INVOICE' AND i.type = 'CUSTOMER' AND i.deleted_at IS NULL
                                  AND i.status <> 'DRAFT' AND i.invoice_date <= ?)
                  AND NOT EXISTS (SELECT 1 FROM delivery_note n WHERE n.organization_id = d.organization_id AND n.deal_id = d.id
                                  AND n.deleted_at IS NULL AND n.status <> 'CANCELLED')
                ORDER BY d.title
                """, rs -> {
            BigDecimal base = rs.getBigDecimal(4).subtract(rs.getBigDecimal(5));
            if (base.signum() <= 0) return;
            UUID dealId = rs.getObject(1, UUID.class);
            UUID partnerId = rs.getObject(3, UUID.class);
            out.add(faeItem(orgId, s, codes, products, "DEAL", dealId, partnerId, "FAE " + rs.getString(2), base, null));
        }, date, orgId, date, date);
        // Delivered notes whose invoice is not out by the date: the deal amount net of advances, once per deal
        // (a deal with a delivery note is proposed from the note, not from its stage).
        jdbc.query("""
                SELECT DISTINCT ON (d.id) n.id, n.number, d.partner_id, COALESCE(d.amount, d.order_total_amount, 0) - COALESCE(d.discount, 0),
                       (SELECT COALESCE(SUM(i.subtotal), 0) FROM invoice i WHERE i.organization_id = d.organization_id
                          AND i.deal_id = d.id AND i.kind = 'ADVANCE' AND i.type = 'CUSTOMER' AND i.deleted_at IS NULL
                          AND i.status NOT IN ('DRAFT', 'CREDITED') AND i.invoice_date <= ?),
                       d.title
                FROM delivery_note n JOIN deal d ON d.id = n.deal_id AND d.deleted_at IS NULL
                WHERE n.organization_id = ? AND n.deleted_at IS NULL AND n.status IN ('DELIVERED', 'INVOICED')
                  AND n.note_date <= ?
                  AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.organization_id = d.organization_id AND i.deal_id = d.id
                                  AND i.kind = 'INVOICE' AND i.type = 'CUSTOMER' AND i.deleted_at IS NULL
                                  AND i.status <> 'DRAFT' AND i.invoice_date <= ?)
                ORDER BY d.id, n.note_date, n.created_at
                """, rs -> {
            BigDecimal base = rs.getBigDecimal(4).subtract(rs.getBigDecimal(5));
            if (base.signum() <= 0) return;
            String number = rs.getString(2);
            out.add(faeItem(orgId, s, codes, products, "DELIVERY_NOTE", rs.getObject(1, UUID.class), rs.getObject(3, UUID.class),
                    "FAE " + (number == null ? "" : number + " ") + rs.getString(6), base, null));
        }, date, orgId, date, date);
        jdbc.query("""
                SELECT id, description, partner_id, amount, vat_rate FROM disbursement
                WHERE organization_id = ? AND deleted_at IS NULL AND rebillable AND invoice_id IS NULL AND spent_on <= ?
                ORDER BY spent_on
                """, rs -> {
            BigDecimal rate = rs.getBigDecimal(5);
            VatRow row = rate == null ? null : s.vatFor(rate.stripTrailingZeros());
            out.add(faeItem(orgId, s, codes, products, "DISBURSEMENT", rs.getObject(1, UUID.class), rs.getObject(3, UUID.class),
                    "FAE débours " + rs.getString(2), rs.getBigDecimal(4), row == null ? "TVA0" : row.code()));
        }, orgId, date);
        jdbc.query("""
                SELECT id, name, partner_id, lines::text FROM recurring_invoice
                WHERE organization_id = ? AND deleted_at IS NULL AND status = 'ACTIVE' AND type = 'CUSTOMER'
                  AND next_run_date IS NOT NULL AND next_run_date <= ?
                ORDER BY next_run_date
                """, rs -> {
            List<Map<String, Object>> lines;
            try {
                lines = objectMapper.readValue(rs.getString(4), new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                return;
            }
            UUID partnerId = rs.getObject(3, UUID.class);
            List<VatDeterminationService.BreakdownRow> rows = vatDetermination.fillLines(orgId, partnerId, lines);
            for (VatDeterminationService.BreakdownRow r : rows) {
                if (r.base().signum() <= 0) continue;
                LedgerClosingDtos.CutoffItem item = faeItem(orgId, s, codes, products, "RECURRING_INVOICE",
                        rs.getObject(1, UUID.class), partnerId, "FAE " + rs.getString(2), r.base(), r.vatCode());
                out.add(rows.size() == 1 ? item : new LedgerClosingDtos.CutoffItem(item.key() + ":" + r.vatCode(),
                        item.sourceType(), item.sourceId(), item.partnerId(), item.partnerName(), item.label(), item.base(),
                        item.vatCode(), item.vat(), item.accountId(), item.accountCode()));
            }
        }, orgId, date);
        return out;
    }

    private LedgerClosingDtos.CutoffItem faeItem(UUID orgId, Snapshot s, Map<UUID, String> codes,
                                                 Map<UUID, Map<String, Object>> products, String sourceType, UUID sourceId,
                                                 UUID partnerId, String label, BigDecimal base, String vatCode) {
        PartnerGroups p = partner(orgId, partnerId);
        String code = vatCode != null ? vatCode
                : vatDetermination.determine(orgId, s, partnerId, null, null, products).vatCode();
        VatRow row = s.vatByCode(code);
        BigDecimal vat = row == null ? BigDecimal.ZERO : base.multiply(row.rate()).movePointLeft(2);
        UUID account = s.general(s.businessGroup(p.businessGroupId()), s.productGroup(null)).salesAccountId();
        return new LedgerClosingDtos.CutoffItem("FAE:" + sourceType + ":" + sourceId, sourceType, sourceId, partnerId, p.name(),
                label + (p.name() == null ? "" : " - " + p.name()), SignedLines.money(base), code, SignedLines.money(vat),
                account, codes.get(account));
    }

    private Map<UUID, String> accountCodes(UUID orgId) {
        Map<UUID, String> codes = new HashMap<>();
        jdbc.query("SELECT id, code FROM ledger_account WHERE organization_id = ?", rs -> {
            codes.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, orgId);
        return codes;
    }

    // Posting

    @Transactional
    public LedgerClosingDtos.Cutoff get(LocalDate date) {
        UUID orgId = org();
        if (date == null) throw new IllegalArgumentException("The date is required");
        return view(orgId, date);
    }

    @Transactional
    public LedgerClosingDtos.Cutoff post(LedgerClosingDtos.CutoffRequest r) {
        UUID orgId = org();
        LocalDate date = r.date();
        UUID sourceId = sourceId(orgId, date);
        if (entryRepository.lockLive(orgId, SOURCE, sourceId).isPresent()) {
            throw new IllegalArgumentException("A cut-off is already posted on " + date + ": cancel it first");
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new IllegalArgumentException("Add at least one cut-off line");
        }
        posting.checkNotLocked(orgId, date);
        Snapshot s = postingSetup.snapshot(orgId);
        UUID fnpAccount = posting.accountId(orgId, "4417");
        UUID fnpVat = posting.accountId(orgId, "3458");
        UUID faeAccount = posting.accountId(orgId, "3427");
        UUID faeVat = posting.accountId(orgId, "4458");
        UUID cca = posting.accountId(orgId, "3491");
        UUID pca = posting.accountId(orgId, "4491");
        SignedLines lines = new SignedLines();
        int n = 0;
        for (LedgerClosingDtos.CutoffLine l : r.lines()) {
            n++;
            if (l == null) throw new IllegalArgumentException("Line " + n + " is empty");
            String type = l.type() == null ? "" : l.type().trim().toUpperCase(Locale.ROOT);
            BigDecimal base = l.base() == null ? BigDecimal.ZERO : l.base();
            if (base.signum() <= 0) throw new IllegalArgumentException("Line " + n + ": the base must be positive");
            base = SignedLines.money(base);
            BigDecimal vat = BigDecimal.ZERO;
            if (type.equals("FNP") || type.equals("FAE")) {
                if (l.vat() != null) {
                    vat = SignedLines.money(l.vat());
                } else if (l.vatCode() != null && !l.vatCode().isBlank()) {
                    VatRow row = s.vatByCode(l.vatCode().trim().toUpperCase(Locale.ROOT));
                    if (row == null) throw new IllegalArgumentException("Line " + n + ": unknown VAT code " + l.vatCode());
                    vat = SignedLines.money(base.multiply(row.rate()).movePointLeft(2));
                }
                if (vat.signum() < 0) throw new IllegalArgumentException("Line " + n + ": the VAT cannot be negative");
            }
            String label = l.label() == null || l.label().isBlank() ? type : l.label().trim();
            switch (type) {
                case "FNP" -> {
                    lines.add(l.accountId(), null, null, base, label);
                    lines.add(fnpVat, null, null, vat, "TVA " + label);
                    lines.add(fnpAccount, l.partnerId(), null, base.add(vat).negate(), label);
                }
                case "FAE" -> {
                    lines.add(faeAccount, l.partnerId(), null, base.add(vat), label);
                    lines.add(l.accountId(), null, null, base.negate(), label);
                    lines.add(faeVat, null, null, vat.negate(), "TVA " + label);
                }
                case "CCA" -> {
                    lines.add(cca, null, null, base, label);
                    lines.add(l.accountId(), null, null, base.negate(), label);
                }
                case "PCA" -> {
                    lines.add(l.accountId(), null, null, base, label);
                    lines.add(pca, null, null, base.negate(), label);
                }
                default -> throw new IllegalArgumentException("Line " + n + ": the type is FNP, FAE, CCA or PCA");
            }
        }
        List<LedgerPostingService.PostingLine> posted = lines.lines();
        if (posted.size() < 2) throw new IllegalArgumentException("Nothing to post");
        posting.postManual(orgId, "OD", date, "Cut-off au " + date, SOURCE, sourceId, "Cut-off " + date, posted);
        List<LedgerPostingService.PostingLine> mirror = posted.stream()
                .map(l -> new LedgerPostingService.PostingLine(l.accountId(), l.partnerId(), l.costCenterId(), l.label(),
                        l.credit(), l.debit()))
                .toList();
        posting.postManual(orgId, "OD", date.plusDays(1), "Extourne du cut-off au " + date, REVERSAL_SOURCE, sourceId,
                "Cut-off " + date, mirror);
        return view(orgId, date);
    }

    @Transactional
    public LedgerClosingDtos.Cutoff cancel(LocalDate date) {
        UUID orgId = org();
        UUID sourceId = sourceId(orgId, date);
        LedgerEntry entry = entryRepository.lockLive(orgId, SOURCE, sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("No cut-off is posted on this date"));
        posting.reverse(orgId, entry, entry.getEntryDate(), LedgerPostingService.LockPolicy.REJECT);
        entryRepository.lockLive(orgId, REVERSAL_SOURCE, sourceId).ifPresent(rev ->
                posting.reverse(orgId, rev, rev.getEntryDate(), LedgerPostingService.LockPolicy.REJECT));
        return view(orgId, date);
    }

    private LedgerClosingDtos.Cutoff view(UUID orgId, LocalDate date) {
        UUID sourceId = sourceId(orgId, date);
        LedgerEntry entry = entryRepository.findLive(orgId, SOURCE, sourceId).orElse(null);
        LedgerEntry reversal = entryRepository.findLive(orgId, REVERSAL_SOURCE, sourceId).orElse(null);
        LedgerDtos.Entry e = entry == null ? null : entryService.toDtos(orgId, List.of(entry)).get(0);
        LedgerDtos.Entry rv = reversal == null ? null : entryService.toDtos(orgId, List.of(reversal)).get(0);
        return new LedgerClosingDtos.Cutoff(date, entry == null ? null : entry.getId(),
                reversal == null ? null : reversal.getId(), reversal == null ? null : reversal.getEntryDate(), e, rv);
    }

    static UUID sourceId(UUID orgId, LocalDate date) {
        return SignedLines.sourceId(orgId, "CUTOFF|" + date);
    }
}
