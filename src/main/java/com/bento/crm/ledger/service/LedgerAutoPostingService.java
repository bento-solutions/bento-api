package com.bento.crm.ledger.service;

import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.repository.BankTransactionRepository;
import com.bento.crm.bank.statement.StatementText;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.expense.model.Expense;
import com.bento.crm.expense.repository.ExpenseRepository;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.invoice.repository.InvoiceRepository;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.model.LedgerEntryLine;
import com.bento.crm.ledger.model.LedgerJournal;
import com.bento.crm.ledger.repository.LedgerEntryLineRepository;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.ledger.repository.LedgerJournalRepository;
import com.bento.crm.ledger.service.LedgerPostingService.PostingLine;
import com.bento.crm.ledger.service.LedgerPostingSetupService.Snapshot;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The automatic postings: customer invoices (VT), supplier bills (AC), payments, expenses and
 * unmatched bank lines (BQ, or CA through the cash register), posted in the transaction that
 * changes the document.
 *
 * <p>Each call computes what the document should have in the ledger and brings its live entry in
 * line: nothing to do when it already matches, otherwise the live entry is reversed and the new
 * one posted. A deleted or cancelled document (or a draft, or one dated before the start date)
 * keeps only the reversal. So a document has at most one live entry, which the partial unique
 * index uq_ledger_entry_live_source also guarantees.
 *
 * <p>No double counting: a bank line matched to an invoice, a payment or an expense posts
 * nothing, the payment or expense posts on that line's bank account instead; a line set aside
 * (IGNORED) posts nothing either.
 */
@Service
@Slf4j
public class LedgerAutoPostingService {

    public static final String INVOICE = "INVOICE";
    public static final String BILL = "BILL";
    public static final String PAYMENT = "PAYMENT";
    public static final String EXPENSE = "EXPENSE";
    public static final String BANK_LINE = "BANK_LINE";
    /** A bank account's opening balance, in the opening (AN) journal. */
    public static final String BANK_OPENING = "BANK_OPENING";
    /** An advance invoice (customer or supplier side). */
    public static final String ADVANCE = "ADVANCE";
    /** A credit note (customer or supplier side). */
    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    /** An advance payment applied to its invoice: OD entry from 4421 to the receivable (or payable to 3411). */
    public static final String ADVANCE_APPLY = "ADVANCE_APPLY";
    public static final Set<String> SOURCES = Set.of(INVOICE, BILL, PAYMENT, EXPENSE, BANK_LINE, BANK_OPENING, ADVANCE,
            CREDIT_NOTE, ADVANCE_APPLY);
    private static final List<String> INVOICE_SOURCES = List.of(INVOICE, BILL, ADVANCE, CREDIT_NOTE);

    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern TVA = Pattern.compile("\\btva\\b");

    /** What a document should have in the ledger. Lines carry signed amounts: debit positive. */
    record Draft(String journal, LocalDate date, String description, String label, List<PostingLine> lines) {}

    private final LedgerPostingService posting;
    private final LedgerPostingSetupService postingSetup;
    private final LedgerJournalRepository journalRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerEntryLineRepository lineRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate perDocument;

    public LedgerAutoPostingService(LedgerPostingService posting, LedgerPostingSetupService postingSetup,
                                    LedgerJournalRepository journalRepository, LedgerEntryRepository entryRepository,
                                    LedgerEntryLineRepository lineRepository, InvoiceRepository invoiceRepository,
                                    PaymentRepository paymentRepository, ExpenseRepository expenseRepository,
                                    BankTransactionRepository bankTransactionRepository, JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager) {
        this.posting = posting;
        this.postingSetup = postingSetup;
        this.journalRepository = journalRepository;
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.expenseRepository = expenseRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.jdbc = jdbc;
        this.perDocument = new TransactionTemplate(transactionManager);
        this.perDocument.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Entry points, called by the services that change the documents

    /** A customer invoice (VT) or a supplier bill (AC), an advance invoice or a credit note. */
    @Transactional
    public void invoice(Invoice invoice) {
        if (invoice == null || invoice.getId() == null) return;
        UUID orgId = invoice.getOrganizationId();
        Snapshot s = postingSetup.snapshot(orgId);
        String want = invoiceSource(invoice);
        // A document that changed type or kind leaves nothing behind under its old one.
        for (String t : INVOICE_SOURCES) {
            if (!t.equals(want)) sync(orgId, t, invoice.getId(), null, s);
        }
        sync(orgId, want, invoice.getId(), invoiceDraft(invoice, s), s);
    }

    /** The source type a document posts under. */
    public static String invoiceSource(Invoice invoice) {
        Invoice.Kind kind = invoice.getKind() == null ? Invoice.Kind.INVOICE : invoice.getKind();
        return switch (kind) {
            case ADVANCE -> ADVANCE;
            case CREDIT_NOTE -> CREDIT_NOTE;
            default -> invoice.getType() == Invoice.InvoiceType.VENDOR ? BILL : INVOICE;
        };
    }

    /** A payment, and, for an advance applied to its invoice, the OD entry that moves it. */
    @Transactional
    public void payment(Payment payment) {
        if (payment == null || payment.getId() == null) return;
        Snapshot s = postingSetup.snapshot(payment.getOrganizationId());
        sync(payment.getOrganizationId(), PAYMENT, payment.getId(), paymentDraft(payment, s), s);
        sync(payment.getOrganizationId(), ADVANCE_APPLY, payment.getId(), advanceApplyDraft(payment, s), s);
    }

    @Transactional
    public void expense(Expense expense) {
        if (expense == null || expense.getId() == null) return;
        Snapshot s = postingSetup.snapshot(expense.getOrganizationId());
        sync(expense.getOrganizationId(), EXPENSE, expense.getId(), expenseDraft(expense, s), s);
    }

    @Transactional
    public void bankLine(BankTransaction line) {
        if (line == null || line.getId() == null) return;
        Snapshot s = postingSetup.snapshot(line.getOrganizationId());
        sync(line.getOrganizationId(), BANK_LINE, line.getId(), bankLineDraft(line, s), s);
    }

    @Transactional
    public void bankLines(List<BankTransaction> lines) {
        if (lines == null || lines.isEmpty()) return;
        Map<UUID, Snapshot> snapshots = new HashMap<>();
        for (BankTransaction t : lines) {
            if (t == null || t.getId() == null) continue;
            Snapshot s = snapshots.computeIfAbsent(t.getOrganizationId(), postingSetup::snapshot);
            sync(t.getOrganizationId(), BANK_LINE, t.getId(), bankLineDraft(t, s), s);
        }
    }

    /** A bank account's opening balance: posted when the account is saved, reversed when it is deleted. */
    @Transactional
    public void bankOpening(UUID orgId, UUID bankAccountId) {
        if (orgId == null || bankAccountId == null) return;
        Snapshot s = postingSetup.snapshot(orgId);
        sync(orgId, BANK_OPENING, bankAccountId, bankOpeningDraft(orgId, bankAccountId, s), s);
    }

    /** The document's live entry, for "View journal entry" on the document screens. */
    public LedgerEntry live(UUID orgId, String sourceType, UUID sourceId) {
        return entryRepository.findLive(orgId, sourceType, sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("No journal entry for this document"));
    }

    // The sync

    /** @return whether a new entry was posted */
    private boolean sync(UUID orgId, String sourceType, UUID sourceId, Draft draft, Snapshot s) {
        if (draft != null && draft.date().isBefore(s.startDate())) {
            draft = null;
        }
        LedgerEntry live = entryRepository.lockLive(orgId, sourceType, sourceId).orElse(null);
        if (live == null && draft == null) {
            return false;
        }
        String journalCode = draft == null ? null : draft.journal();
        LedgerJournal journal = journalCode == null ? null : journalRepository.findByOrganizationIdAndCode(orgId, journalCode)
                .orElseThrow(() -> new IllegalArgumentException("Journal " + journalCode + " is missing"));
        if (live != null && draft != null && same(orgId, live, journal, draft)) {
            return false;
        }
        if (live != null) {
            reverse(orgId, live);
        }
        if (draft == null) {
            return false;
        }
        posting.write(orgId, journal, draft.date(), draft.description(), sourceType, sourceId, draft.label(), null,
                draft.lines(), false, true);
        return true;
    }

    private boolean same(UUID orgId, LedgerEntry live, LedgerJournal journal, Draft draft) {
        if (!live.getJournalId().equals(journal.getId()) || !live.getEntryDate().equals(draft.date())
                || !Objects.equals(live.getSourceLabel(), cut(draft.label(), 160))) {
            return false;
        }
        List<String> have = lineRepository.findByOrganizationIdAndEntryIdIn(orgId, List.of(live.getId())).stream()
                .map(l -> key(l.getAccountId(), l.getPartnerId(), l.getCostCenterId(), l.getDebit(), l.getCredit()))
                .sorted().toList();
        List<String> want = draft.lines().stream()
                .map(l -> key(l.accountId(), l.partnerId(), l.costCenterId(), l.debit(), l.credit()))
                .sorted().toList();
        return have.equals(want);
    }

    private static String key(UUID account, UUID partner, UUID costCenter, BigDecimal debit, BigDecimal credit) {
        return account + "|" + partner + "|" + costCenter + "|" + money(debit).toPlainString() + "|"
                + money(credit).toPlainString();
    }

    /** Reverses a live entry on its own date, so the document's period shows only what it now is. */
    private void reverse(UUID orgId, LedgerEntry live) {
        LedgerJournal journal = journalRepository.findByOrganizationIdAndId(orgId, live.getJournalId())
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found"));
        List<PostingLine> lines = lineRepository.findByOrganizationIdAndEntryIdIn(orgId, List.of(live.getId())).stream()
                .map((LedgerEntryLine l) -> new PostingLine(l.getAccountId(), l.getPartnerId(), l.getCostCenterId(),
                        l.getLabel(), l.getCredit(), l.getDebit()))
                .toList();
        LedgerEntry reversal = posting.write(orgId, journal, live.getEntryDate(),
                "Reversal of " + live.getNumber() + ": " + live.getDescription(), LedgerEntryService.SOURCE_REVERSAL,
                live.getId(), live.getNumber(), live.getId(), lines, true, true);
        live.setReversedById(reversal.getId());
        entryRepository.saveAndFlush(live);
    }

    // What each document posts

    private record PartnerInfo(UUID id, String name, String type, UUID customerGroupId, UUID supplierGroupId,
                               UUID businessGroupId) {}

    private PartnerInfo partner(UUID orgId, UUID partnerId) {
        if (partnerId == null) return null;
        List<PartnerInfo> rows = jdbc.query("""
                SELECT id, COALESCE(NULLIF(company_name, ''), name), type, customer_group_id, supplier_group_id, business_group_id
                FROM partner WHERE organization_id = ? AND id = ?
                """, (rs, i) -> new PartnerInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class)), orgId, partnerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Signed amounts per (account, partner, cost center): debit positive, credit negative. */
    private static final class Lines {
        private record Key(UUID account, UUID partner, UUID costCenter) {}

        private final Map<Key, BigDecimal> amounts = new LinkedHashMap<>();
        private final Map<Key, String> labels = new HashMap<>();

        void add(UUID account, UUID partner, UUID costCenter, BigDecimal signed, String label) {
            if (signed == null || signed.signum() == 0) return;
            Key k = new Key(account, partner, costCenter);
            amounts.merge(k, signed, BigDecimal::add);
            if (label != null) labels.putIfAbsent(k, label);
        }

        List<PostingLine> toPostingLines() {
            List<PostingLine> out = new ArrayList<>();
            // Debits first, then credits, each in the order they were added.
            for (boolean debits : new boolean[]{true, false}) {
                amounts.forEach((k, v) -> {
                    BigDecimal a = money(v);
                    if (a.signum() == 0 || (a.signum() > 0) != debits) return;
                    out.add(new PostingLine(k.account(), k.partner(), k.costCenter(), labels.get(k),
                            a.signum() > 0 ? a : null, a.signum() < 0 ? a.negate() : null));
                });
            }
            return out;
        }
    }

    private Draft draft(String journal, LocalDate date, String description, String label, Lines lines) {
        List<PostingLine> out = lines.toPostingLines();
        return out.size() < 2 ? null : new Draft(journal, date, description, label, out);
    }

    Draft invoiceDraft(Invoice inv, Snapshot s) {
        if (inv.getDeletedAt() != null || inv.getStatus() == null || inv.getStatus() == Invoice.Status.DRAFT
                || inv.getInvoiceDate() == null || inv.getTotal() == null || inv.getPartnerId() == null) {
            return null;
        }
        UUID orgId = inv.getOrganizationId();
        BigDecimal total = money(inv.getTotal());
        if (total.signum() == 0) return null;
        boolean customer = inv.getType() != Invoice.InvoiceType.VENDOR;
        Invoice.Kind kind = inv.getKind() == null ? Invoice.Kind.INVOICE : inv.getKind();
        boolean advance = kind == Invoice.Kind.ADVANCE;
        boolean creditNote = kind == Invoice.Kind.CREDIT_NOTE;
        PartnerInfo p = partner(orgId, inv.getPartnerId());
        UUID partnerId = p == null ? null : p.id();
        UUID counter = customer
                ? s.partnerAccount(LedgerPostingSetupService.CUSTOMER, p == null ? null : p.customerGroupId())
                : s.partnerAccount(LedgerPostingSetupService.SUPPLIER, p == null ? null : p.supplierGroupId());
        UUID businessGroup = s.businessGroup(p == null ? null : p.businessGroupId());
        // Advances received (4421) or paid (3411), on the partner.
        UUID advanceAccount = postingSetup.accountByCode(orgId, customer ? "4421" : "3411");

        // Net amounts per (account, partner, cost center) from the lines; an advance invoice's lines go to 4421 / 3411.
        record NetKey(UUID account, UUID partner, UUID costCenter) {}
        Map<NetKey, BigDecimal> nets = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> products = new HashMap<>();
        if (inv.getLines() != null) {
            for (Map<String, Object> line : inv.getLines()) {
                if (line == null) continue;
                BigDecimal qty = decimal(line.get("qty"));
                BigDecimal price = decimal(first(line, "unitPrice", "unit_price"));
                if (qty == null || price == null) continue;
                BigDecimal net = qty.multiply(price);
                UUID costCenter = uuid(line.get("costCenterId"), "cost center");
                NetKey key;
                if (advance) {
                    key = new NetKey(advanceAccount, partnerId, costCenter);
                } else {
                    UUID productGroup = productGroup(orgId, line, s, products);
                    LedgerPostingSetupService.GeneralRow g = s.general(businessGroup, productGroup);
                    key = new NetKey(customer ? g.salesAccountId() : g.purchaseAccountId(), null, costCenter);
                }
                nets.merge(key, net, BigDecimal::add);
            }
        }
        nets.replaceAll((k, v) -> money(v));

        // VAT per account, from the lines' codes (exempt codes have a 0 rate and post nothing).
        Map<UUID, BigDecimal> vat = new LinkedHashMap<>();
        for (VatDeterminationService.BreakdownRow r : VatDeterminationService.breakdown(inv.getLines(), Map.of())) {
            if (r.vat().signum() == 0) continue;
            vat.merge(vatAccount(orgId, s, r.vatCode(), r.rate().movePointLeft(2), customer), r.vat(), BigDecimal::add);
        }

        // Advances deducted on a final invoice: their base back from 4421 / 3411, their VAT off the VAT account.
        BigDecimal deductedBase = BigDecimal.ZERO;
        Map<UUID, BigDecimal> deductedVat = new LinkedHashMap<>();
        if (!advance && inv.getAdvanceDeductions() != null) {
            for (Map<String, Object> d : inv.getAdvanceDeductions()) {
                BigDecimal b = money(decimal(d.get("base")));
                BigDecimal v = money(decimal(d.get("vat")));
                deductedBase = deductedBase.add(b);
                if (v.signum() != 0) {
                    Object code = d.get("vatCode");
                    BigDecimal rate = b.signum() == 0 ? DEFAULT_VAT : v.divide(b, 4, RoundingMode.HALF_UP);
                    deductedVat.merge(vatAccount(orgId, s, code == null ? null : code.toString(), rate, customer), v,
                            BigDecimal::add);
                }
            }
        }
        BigDecimal netVat = sum(vat).subtract(sum(deductedVat));

        // The document's own VAT total wins, the difference going to the largest VAT account.
        BigDecimal tax = inv.getTax() != null ? money(inv.getTax()) : netVat;
        BigDecimal vatDiff = tax.subtract(netVat);
        if (vatDiff.signum() != 0) {
            UUID target = vat.isEmpty() ? vatAccount(orgId, s, null, guessRate(tax, total), customer) : largest(vat);
            vat.merge(target, vatDiff, BigDecimal::add);
        }
        // Net amounts make up the rest of the total.
        BigDecimal netTarget = total.subtract(tax).add(deductedBase);
        if (nets.isEmpty()) {
            LedgerPostingSetupService.GeneralRow g = s.general(businessGroup, s.productGroup(null));
            nets.put(advance ? new NetKey(advanceAccount, partnerId, null)
                    : new NetKey(customer ? g.salesAccountId() : g.purchaseAccountId(), null, null), netTarget);
        } else {
            BigDecimal netDiff = netTarget.subtract(nets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            if (netDiff.signum() != 0) {
                NetKey target = nets.entrySet().stream().max(Comparator.comparing(e -> e.getValue().abs()))
                        .orElseThrow().getKey();
                nets.merge(target, netDiff, BigDecimal::add);
            }
        }

        // Customer: the receivable is a debit. A supplier bill mirrors it, and a credit note mirrors its kind.
        BigDecimal sign = BigDecimal.valueOf((customer ? 1 : -1) * (creditNote ? -1 : 1));
        String number = inv.getInvoiceNumber();
        String who = p == null ? inv.getCustomerName() : p.name();
        String what = switch (kind) {
            case ADVANCE -> customer ? "Advance invoice " : "Supplier advance invoice ";
            case CREDIT_NOTE -> customer ? "Credit note " : "Supplier credit note ";
            default -> customer ? "Invoice " : "Supplier bill ";
        };
        String description = what + (number == null ? "" : number) + (who == null ? "" : " - " + who);
        Lines lines = new Lines();
        if (customer) lines.add(counter, partnerId, null, total.multiply(sign), description);
        if (deductedBase.signum() != 0) {
            lines.add(advanceAccount, partnerId, null, deductedBase.multiply(sign), "Acomptes déduits");
        }
        nets.forEach((k, v) -> lines.add(k.account(), k.partner(), k.costCenter(), v.multiply(sign.negate()),
                advance ? "Acompte" : null));
        vat.forEach((a, v) -> lines.add(a, null, null, v.multiply(sign.negate()), "TVA"));
        deductedVat.forEach((a, v) -> lines.add(a, null, null, v.multiply(sign), "TVA"));
        if (!customer) lines.add(counter, partnerId, null, total.multiply(sign), description);
        return draft(customer ? "VT" : "AC", inv.getInvoiceDate(), description.trim(), number, lines);
    }

    private static BigDecimal sum(Map<?, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The line's product group: its own override, else its product's, else GOODS for stocked products. */
    private UUID productGroup(UUID orgId, Map<String, Object> line, Snapshot s, Map<UUID, Map<String, Object>> products) {
        UUID override = uuid(line.get("productGroupId"), "product group");
        if (override != null) {
            if (!s.hasProductGroup(override)) {
                throw new IllegalArgumentException("Unknown product posting group on an invoice line");
            }
            return override;
        }
        UUID productId = uuidOrNull(line.get("productId"));
        if (productId == null) {
            return s.productGroup(null);
        }
        Map<String, Object> product = products.computeIfAbsent(productId, id -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT product_group_id, track_stock FROM product WHERE organization_id = ? AND id = ?", orgId, id);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        });
        if (product.isEmpty()) {
            return s.productGroup(null);
        }
        UUID group = (UUID) product.get("product_group_id");
        if (group != null && s.hasProductGroup(group)) {
            return group;
        }
        return Boolean.TRUE.equals(product.get("track_stock")) ? s.productGroupByCode("GOODS")
                : s.productGroupByCode("SERVICES");
    }

    /** The VAT account of a code (else of a rate, else TVA20's): collected on sales, recoverable on purchases. */
    private UUID vatAccount(UUID orgId, Snapshot s, String code, BigDecimal rate, boolean collected) {
        LedgerPostingSetupService.VatRow row = code == null ? null : s.vatByCode(code);
        if (row == null && rate != null) row = s.vatFor(rate);
        if (row == null) row = s.vatByCode("TVA20");
        if (row == null) return postingSetup.accountByCode(orgId, collected ? "4455" : "34552");
        return collected ? row.collectedAccountId() : row.recoverableAccountId();
    }

    /** The standard rate nearest to tax / net, for documents with a VAT total but no lines. */
    private static BigDecimal guessRate(BigDecimal tax, BigDecimal total) {
        BigDecimal net = total.subtract(tax);
        if (net.signum() == 0) return DEFAULT_VAT;
        BigDecimal r = tax.divide(net, 4, RoundingMode.HALF_UP);
        BigDecimal best = DEFAULT_VAT;
        for (String c : new String[]{"0.20", "0.14", "0.10", "0.07"}) {
            BigDecimal candidate = new BigDecimal(c);
            if (candidate.subtract(r).abs().compareTo(best.subtract(r).abs()) < 0) best = candidate;
        }
        return best;
    }

    Draft paymentDraft(Payment pay, Snapshot s) {
        if (pay.getDeletedAt() != null || pay.getAmount() == null || pay.getPaymentDate() == null
                || pay.getPartnerId() == null) {
            return null;
        }
        BigDecimal amount = money(pay.getAmount());
        if (amount.signum() <= 0) return null;
        UUID orgId = pay.getOrganizationId();
        BankLink link = bankLink(orgId, pay.getId());
        // Created by matching a bank line still to validate: posts once the line is validated.
        if (link != null && link.created() && !link.validated() && s.postOnValidation()) return null;
        PartnerInfo p = partner(orgId, pay.getPartnerId());
        String invoiceType = null;
        String invoiceNumber = null;
        if (pay.getInvoiceId() != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT type, invoice_number FROM invoice WHERE organization_id = ? AND id = ?", orgId, pay.getInvoiceId());
            if (!rows.isEmpty()) {
                invoiceType = (String) rows.get(0).get("type");
                invoiceNumber = (String) rows.get(0).get("invoice_number");
            }
        }
        boolean supplier = paymentIsSupplier(invoiceType, link, p);
        UUID partnerId = p == null ? null : p.id();
        UUID counter = pay.isAdvance() ? postingSetup.accountByCode(orgId, supplier ? "3411" : "4421")
                : supplier
                ? s.partnerAccount(LedgerPostingSetupService.SUPPLIER, p == null ? null : p.supplierGroupId())
                : s.partnerAccount(LedgerPostingSetupService.CUSTOMER, p == null ? null : p.customerGroupId());
        boolean cash = pay.isCashRegister();
        // Payments recorded before bank accounts were tracked carry none: they went through the main bank account.
        UUID bankAccountId = pay.getBankAccountId() != null ? pay.getBankAccountId() : postingSetup.defaultBankAccountId(orgId);
        UUID money = cash ? s.cashAccountId() : postingSetup.bankLedgerAccount(orgId, bankAccountId);
        String who = p == null ? "" : p.name();
        String description = (pay.isAdvance() ? (supplier ? "Advance paid to " : "Advance received from ")
                : supplier ? "Payment to " : "Payment received from ") + who
                + (invoiceNumber == null ? "" : " - " + invoiceNumber);
        String label = pay.getReference() != null && !pay.getReference().isBlank() ? pay.getReference().trim()
                : invoiceNumber != null ? invoiceNumber : "Payment";
        Lines lines = new Lines();
        if (supplier) {
            lines.add(counter, partnerId, null, amount, description);
            lines.add(money, null, null, amount.negate(), null);
        } else {
            lines.add(money, null, null, amount, null);
            lines.add(counter, partnerId, null, amount.negate(), description);
        }
        return draft(cash ? "CA" : "BQ", pay.getPaymentDate(), description.trim(), label, lines);
    }

    /** The bank line a payment is matched to: whether matching created the payment, whether it is validated, its sign. */
    private record BankLink(boolean created, boolean validated, int sign) {}

    private BankLink bankLink(UUID orgId, UUID paymentId) {
        List<BankLink> rows = jdbc.query("SELECT payment_created, validated_at IS NOT NULL, amount FROM bank_transaction"
                + " WHERE organization_id = ? AND payment_id = ? AND deleted_at IS NULL ORDER BY created_at LIMIT 1",
                (rs, i) -> new BankLink(rs.getBoolean(1), rs.getBoolean(2), rs.getBigDecimal(3).signum()), orgId, paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A payment is the supplier side when its invoice is a bill, else when its bank line went out, else by the partner. */
    private static boolean paymentIsSupplier(String invoiceType, BankLink link, PartnerInfo p) {
        if (invoiceType != null) return invoiceType.equals("VENDOR");
        if (link != null && link.sign() != 0) return link.sign() < 0;
        return p != null && "VENDOR".equals(p.type());
    }

    /**
     * An advance payment applied to an invoice: OD entry on the day it was applied, from 4421 to the
     * customer's receivable, or from the supplier's payable to 3411.
     */
    Draft advanceApplyDraft(Payment pay, Snapshot s) {
        if (pay.getDeletedAt() != null || !pay.isAdvance() || pay.getInvoiceId() == null || pay.getAdvanceAppliedOn() == null
                || pay.getAmount() == null || pay.getPartnerId() == null) {
            return null;
        }
        BigDecimal amount = money(pay.getAmount());
        if (amount.signum() <= 0) return null;
        UUID orgId = pay.getOrganizationId();
        BankLink link = bankLink(orgId, pay.getId());
        if (link != null && link.created() && !link.validated() && s.postOnValidation()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, invoice_number FROM invoice WHERE organization_id = ? AND id = ?", orgId, pay.getInvoiceId());
        if (rows.isEmpty()) return null;
        String number = (String) rows.get(0).get("invoice_number");
        boolean supplier = "VENDOR".equals(rows.get(0).get("type"));
        PartnerInfo p = partner(orgId, pay.getPartnerId());
        UUID partnerId = p == null ? null : p.id();
        UUID advanceAccount = postingSetup.accountByCode(orgId, supplier ? "3411" : "4421");
        UUID counter = supplier
                ? s.partnerAccount(LedgerPostingSetupService.SUPPLIER, p == null ? null : p.supplierGroupId())
                : s.partnerAccount(LedgerPostingSetupService.CUSTOMER, p == null ? null : p.customerGroupId());
        String description = "Advance applied to " + (number == null ? "invoice" : number) + (p == null ? "" : " - " + p.name());
        Lines lines = new Lines();
        if (supplier) {
            lines.add(counter, partnerId, null, amount, description);
            lines.add(advanceAccount, partnerId, null, amount.negate(), description);
        } else {
            lines.add(advanceAccount, partnerId, null, amount, description);
            lines.add(counter, partnerId, null, amount.negate(), description);
        }
        return draft("OD", pay.getAdvanceAppliedOn(), description, number, lines);
    }

    Draft expenseDraft(Expense e, Snapshot s) {
        if (e.getDeletedAt() != null || e.getAmount() == null || e.getExpenseDate() == null) return null;
        BigDecimal amount = money(e.getAmount());
        if (amount.signum() <= 0) return null;
        UUID orgId = e.getOrganizationId();
        BigDecimal vatAmount = e.getVatAmount() == null ? BigDecimal.ZERO : money(e.getVatAmount()).max(BigDecimal.ZERO).min(amount);
        String category = e.getCategory() == null ? "OTHER" : e.getCategory().trim().toUpperCase(Locale.ROOT);
        UUID account = s.expenseCategories().get(category);
        if (account == null) account = s.expenseCategories().get("OTHER");
        if (account == null) account = postingSetup.accountByCode(orgId, "6125");

        // Matched to a bank line: paid through that line's account. Otherwise by payment method.
        List<UUID> matched = jdbc.queryForList("SELECT bank_account_id FROM bank_transaction WHERE organization_id = ?"
                + " AND expense_id = ? AND status = 'MATCHED' AND deleted_at IS NULL LIMIT 1", UUID.class, orgId, e.getId());
        if (!matched.isEmpty() && s.postOnValidation()) {
            Boolean validated = jdbc.queryForObject("SELECT bool_and(validated_at IS NOT NULL) FROM bank_transaction"
                    + " WHERE organization_id = ? AND expense_id = ? AND status = 'MATCHED' AND deleted_at IS NULL",
                    Boolean.class, orgId, e.getId());
            // Matched to a bank line still to validate: posts once the line is validated.
            if (!Boolean.TRUE.equals(validated)) return null;
        }
        boolean cash = matched.isEmpty() && e.getPaymentMethod() == Payment.Method.CASH;
        UUID money = cash ? s.cashAccountId() : postingSetup.bankLedgerAccount(orgId,
                matched.isEmpty() ? postingSetup.defaultBankAccountId(orgId) : matched.get(0));

        String what = e.getDescription() != null ? e.getDescription()
                : e.getVendorName() != null ? e.getVendorName() : category;
        String description = "Expense " + category + ": " + what;
        Lines lines = new Lines();
        lines.add(account, null, e.getCostCenterId(), amount.subtract(vatAmount), null);
        LedgerPostingSetupService.VatRow vatRow = e.getVatCode() != null && s.vatByCode(e.getVatCode()) != null
                ? s.vatByCode(e.getVatCode()) : s.vatByCode("TVA20");
        lines.add(vatRow == null ? postingSetup.accountByCode(orgId, "34552") : vatRow.recoverableAccountId(), null, null,
                vatAmount, "TVA");
        lines.add(money, null, null, amount.negate(), null);
        return draft(cash ? "CA" : "BQ", e.getExpenseDate(), description, cut(e.getReference() != null ? e.getReference() : what, 160), lines);
    }

    Draft bankLineDraft(BankTransaction t, Snapshot s) {
        if (t.getDeletedAt() != null || t.getStatus() != BankTransaction.Status.UNMATCHED || t.getAmount() == null
                || t.getBookingDate() == null) {
            return null;
        }
        // Waits for a person to validate it, unless the organization posts on import.
        if (t.getValidatedAt() == null && s.postOnValidation()) return null;
        BigDecimal amount = money(t.getAmount());
        if (amount.signum() == 0) return null;
        UUID orgId = t.getOrganizationId();
        UUID account = categoryAccount(orgId, t, s);
        UUID bank = postingSetup.bankLedgerAccount(orgId, t.getBankAccountId());
        String description = "Bank: " + (t.getDescription() == null ? "" : t.getDescription());
        Lines lines = new Lines();
        lines.add(bank, null, null, amount, null);
        lines.add(account, null, null, amount.negate(), null);
        return draft("BQ", t.getBookingDate(), description, cut(t.getReference() != null ? t.getReference()
                : t.getDescription(), 160), lines);
    }

    /** The account an unmatched bank line posts against: its category's (TVA taxes to 4456), else 4497. */
    private UUID categoryAccount(UUID orgId, BankTransaction t, Snapshot s) {
        String category = t.getCategory() == null ? "OTHER" : t.getCategory();
        if (category.equals("TAXES") && TVA.matcher(StatementText.norm(t.getDescription())).find()) {
            return postingSetup.accountByCode(orgId, "4456");
        }
        UUID account = s.bankCategories().get(category);
        if (account == null) account = s.bankCategories().get("OTHER");
        if (account == null) account = postingSetup.accountByCode(orgId, "4497");
        return account;
    }

    /**
     * The account a bank line hits when it posts, for the bank journal: the partner's account (or
     * 4421 / 3411 for an advance) when it pays through a payment, the expense's category account
     * when matched to an expense, the category's account (or 4497) when unmatched; null when set aside.
     */
    @Transactional
    public UUID proposedAccount(BankTransaction t) {
        if (t == null || t.getDeletedAt() != null || t.getStatus() == BankTransaction.Status.IGNORED) return null;
        UUID orgId = t.getOrganizationId();
        Snapshot s = postingSetup.snapshot(orgId);
        if (t.getStatus() == BankTransaction.Status.UNMATCHED) {
            return categoryAccount(orgId, t, s);
        }
        if (t.getPaymentId() != null) {
            Payment pay = paymentRepository.findByOrganizationIdAndId(orgId, t.getPaymentId()).orElse(null);
            if (pay == null) return null;
            String invoiceType = null;
            if (pay.getInvoiceId() != null) {
                List<String> types = jdbc.queryForList("SELECT type FROM invoice WHERE organization_id = ? AND id = ?",
                        String.class, orgId, pay.getInvoiceId());
                invoiceType = types.isEmpty() ? null : types.get(0);
            }
            PartnerInfo p = partner(orgId, pay.getPartnerId());
            boolean supplier = paymentIsSupplier(invoiceType, new BankLink(true, true, t.getAmount().signum()), p);
            if (pay.isAdvance()) return postingSetup.accountByCode(orgId, supplier ? "3411" : "4421");
            return supplier
                    ? s.partnerAccount(LedgerPostingSetupService.SUPPLIER, p == null ? null : p.supplierGroupId())
                    : s.partnerAccount(LedgerPostingSetupService.CUSTOMER, p == null ? null : p.customerGroupId());
        }
        if (t.getExpenseId() != null) {
            Expense e = expenseRepository.findForOrganization(orgId, t.getExpenseId()).orElse(null);
            if (e == null) return null;
            String category = e.getCategory() == null ? "OTHER" : e.getCategory().trim().toUpperCase(Locale.ROOT);
            UUID account = s.expenseCategories().get(category);
            if (account == null) account = s.expenseCategories().get("OTHER");
            return account != null ? account : postingSetup.accountByCode(orgId, "6125");
        }
        return null;
    }

    /** Whether the document has a live entry, after a validation. */
    public boolean hasLive(UUID orgId, String sourceType, UUID sourceId) {
        return entryRepository.findLive(orgId, sourceType, sourceId).isPresent();
    }

    /**
     * The balance the bank account held when the ledger starts: its opening balance, plus the statement
     * lines booked between its opening date and the ledger's start date (those lines do not post). It goes
     * against the suspense account 4497 until the opening trial balance is imported, which reclassifies it.
     */
    Draft bankOpeningDraft(UUID orgId, UUID bankAccountId, Snapshot s) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT name, opening_balance, opening_date, deleted_at"
                + " FROM bank_account WHERE organization_id = ? AND id = ?", orgId, bankAccountId);
        if (rows.isEmpty() || rows.get(0).get("deleted_at") != null) return null;
        Map<String, Object> a = rows.get(0);
        BigDecimal opening = money((BigDecimal) a.get("opening_balance"));
        LocalDate openingDate = a.get("opening_date") == null ? null : ((java.sql.Date) a.get("opening_date")).toLocalDate();
        LocalDate date = openingDate == null || openingDate.isBefore(s.startDate()) ? s.startDate() : openingDate;
        BigDecimal before = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM bank_transaction WHERE organization_id = ?"
                + " AND bank_account_id = ? AND deleted_at IS NULL AND booking_date < ?"
                + " AND (?::date IS NULL OR booking_date >= ?::date)", BigDecimal.class,
                orgId, bankAccountId, s.startDate(), openingDate, openingDate);
        BigDecimal amount = money(opening.add(before == null ? BigDecimal.ZERO : before));
        String name = (String) a.get("name");
        String description = "Opening balance " + (name == null ? "" : name);
        Lines lines = new Lines();
        lines.add(postingSetup.bankLedgerAccount(orgId, bankAccountId), null, null, amount, null);
        lines.add(postingSetup.accountByCode(orgId, "4497"), null, null, amount.negate(), "Opening balance to reclassify");
        return draft("AN", date, description.trim(), "Opening balance", lines);
    }

    // History

    private record Doc(String sourceType, UUID id, String label) {}

    /**
     * Posts every document dated on or after the start date that has no live entry. Each document
     * goes in its own transaction, so one that cannot post is reported and the rest still post.
     * Running it again posts nothing new.
     */
    public LedgerDtos.RepostResult repost(UUID orgId) {
        LocalDate start = perDocument.execute(st -> postingSetup.snapshot(orgId).startDate());
        List<Doc> docs = new ArrayList<>();
        jdbc.query("""
                SELECT id, type, invoice_number, kind FROM invoice WHERE organization_id = ? AND deleted_at IS NULL
                  AND status <> 'DRAFT' AND invoice_date >= ? ORDER BY invoice_date, created_at
                """, rs -> {
            String kind = rs.getString(4);
            String type = "ADVANCE".equals(kind) ? ADVANCE : "CREDIT_NOTE".equals(kind) ? CREDIT_NOTE
                    : "VENDOR".equals(rs.getString(2)) ? BILL : INVOICE;
            docs.add(new Doc(type, rs.getObject(1, UUID.class), rs.getString(3)));
        }, orgId, start);
        jdbc.query("SELECT id, reference FROM payment WHERE organization_id = ? AND deleted_at IS NULL AND payment_date >= ?"
                + " ORDER BY payment_date, created_at", rs -> {
            docs.add(new Doc(PAYMENT, rs.getObject(1, UUID.class), rs.getString(2)));
        }, orgId, start);
        jdbc.query("SELECT id, COALESCE(description, category) FROM expense WHERE organization_id = ? AND deleted_at IS NULL"
                + " AND expense_date >= ? ORDER BY expense_date, created_at", rs -> {
            docs.add(new Doc(EXPENSE, rs.getObject(1, UUID.class), rs.getString(2)));
        }, orgId, start);
        jdbc.query("SELECT id, description FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL"
                + " AND status = 'UNMATCHED' AND booking_date >= ? ORDER BY booking_date, created_at", rs -> {
            docs.add(new Doc(BANK_LINE, rs.getObject(1, UUID.class), rs.getString(2)));
        }, orgId, start);

        jdbc.query("SELECT id, name FROM bank_account WHERE organization_id = ? AND deleted_at IS NULL ORDER BY created_at", rs -> {
            docs.add(new Doc(BANK_OPENING, rs.getObject(1, UUID.class), rs.getString(2)));
        }, orgId);

        int posted = 0, skipped = 0;
        List<LedgerDtos.RepostError> errors = new ArrayList<>();
        for (Doc d : docs) {
            if (entryRepository.findLive(orgId, d.sourceType(), d.id()).isPresent()) {
                skipped++;
                continue;
            }
            try {
                Boolean done = perDocument.execute(st -> repostOne(orgId, d));
                if (Boolean.TRUE.equals(done)) posted++;
                else skipped++;
            } catch (RuntimeException e) {
                log.info("[ledger] repost of {} {} failed: {}", d.sourceType(), d.id(), e.getMessage());
                errors.add(new LedgerDtos.RepostError(d.sourceType(), d.id(), d.label(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new LedgerDtos.RepostResult(posted, skipped, errors);
    }

    private boolean repostOne(UUID orgId, Doc d) {
        Snapshot s = postingSetup.snapshot(orgId);
        return switch (d.sourceType()) {
            case INVOICE, BILL, ADVANCE, CREDIT_NOTE -> invoiceRepository.findByOrganizationIdAndId(orgId, d.id())
                    .map(i -> sync(orgId, d.sourceType(), i.getId(), invoiceDraft(i, s), s)).orElse(false);
            case PAYMENT -> paymentRepository.findByOrganizationIdAndId(orgId, d.id())
                    .map(p -> sync(orgId, PAYMENT, p.getId(), paymentDraft(p, s), s)
                            | sync(orgId, ADVANCE_APPLY, p.getId(), advanceApplyDraft(p, s), s)).orElse(false);
            case EXPENSE -> expenseRepository.findForOrganization(orgId, d.id())
                    .map(e -> sync(orgId, EXPENSE, e.getId(), expenseDraft(e, s), s)).orElse(false);
            case BANK_OPENING -> sync(orgId, BANK_OPENING, d.id(), bankOpeningDraft(orgId, d.id(), s), s);
            case BANK_LINE -> bankTransactionRepository.find(orgId, d.id())
                    .map(t -> sync(orgId, BANK_LINE, t.getId(), bankLineDraft(t, s), s)).orElse(false);
            default -> false;
        };
    }

    // Helpers

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static <K> K largest(Map<K, BigDecimal> map) {
        return map.entrySet().stream().max(Comparator.comparing(e -> e.getValue().abs())).orElseThrow().getKey();
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !(v instanceof String str && str.isBlank())) return v;
        }
        return null;
    }

    /** A VAT rate as a fraction: 0.2 stays, 20 becomes 0.20; missing means 20 %. */
    static BigDecimal rate(Object raw) {
        BigDecimal r = decimal(raw);
        if (r == null) return DEFAULT_VAT;
        if (r.compareTo(BigDecimal.ONE) > 0) r = r.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Integer || value instanceof Long) return BigDecimal.valueOf(((Number) value).longValue());
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuid(Object value, String what) {
        if (value == null || (value instanceof String s && s.isBlank())) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + what + " id on an invoice line");
        }
    }

    private static UUID uuidOrNull(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
