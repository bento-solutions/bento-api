package com.bento.crm.bank.service;

import com.bento.crm.bank.dto.BankDtos;
import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.repository.BankTransactionRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.expense.repository.ExpenseRepository;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.service.LedgerAutoPostingService;
import com.bento.crm.ledger.service.LedgerPostingSetupService;
import com.bento.crm.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The bank journal (phase 3): bank lines, with what they settle, wait for a person to validate
 * them before they post (bank posting mode ON_VALIDATION, the default). Validating posts the
 * line's entry (or its payment's, or its expense's); unvalidating reverses it.
 */
@Service
@RequiredArgsConstructor
public class BankJournalService {

    private static final String SUSPENSE = "4497";

    private final BankTransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final LedgerAutoPostingService ledger;
    private final LedgerPostingSetupService postingSetup;
    private final JdbcTemplate jdbc;
    private final com.bento.crm.ledger.service.LedgerPostingService posting;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    private static UUID currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getPrincipal() == null) return null;
        try {
            return UUID.fromString(a.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // List

    @Transactional
    public BankDtos.JournalPage journal(UUID bankAccountId, String status, LocalDate from, LocalDate to, int page, int size) {
        UUID orgId = org();
        StringBuilder where = new StringBuilder(" WHERE t.organization_id = ? AND t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (bankAccountId != null) { where.append(" AND t.bank_account_id = ?"); args.add(bankAccountId); }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if (s.equals("TO_VALIDATE")) where.append(" AND t.validated_at IS NULL");
            else if (s.equals("VALIDATED")) where.append(" AND t.validated_at IS NOT NULL");
            else throw new IllegalArgumentException("The status is TO_VALIDATE or VALIDATED");
        }
        if (from != null) { where.append(" AND t.booking_date >= ?"); args.add(from); }
        if (to != null) { where.append(" AND t.booking_date <= ?"); args.add(to); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM bank_transaction t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<UUID> ids = jdbc.queryForList("SELECT t.id FROM bank_transaction t" + where
                + " ORDER BY t.booking_date DESC, t.created_at DESC LIMIT ? OFFSET ?", UUID.class, pageArgs.toArray());

        Map<UUID, String[]> accounts = new HashMap<>();
        jdbc.query("SELECT id, code, name FROM ledger_account WHERE organization_id = ?", rs -> {
            accounts.put(rs.getObject(1, UUID.class), new String[]{rs.getString(2), rs.getString(3)});
        }, orgId);
        List<BankDtos.JournalLine> rows = new ArrayList<>();
        for (UUID id : ids) {
            BankTransaction t = transactionRepository.find(orgId, id).orElse(null);
            if (t == null) continue;
            rows.add(line(orgId, t, accounts));
        }
        long count = total == null ? 0 : total;
        return new BankDtos.JournalPage(rows, page, size, count, size == 0 ? 0 : (int) Math.ceil((double) count / size),
                summary(orgId, bankAccountId));
    }

    private BankDtos.JournalLine line(UUID orgId, BankTransaction t, Map<UUID, String[]> accounts) {
        UUID partnerId = null;
        String partnerName = null;
        String invoiceNumber = null;
        Boolean advance = null;
        List<Map<String, Object>> info = jdbc.queryForList("""
                SELECT COALESCE(p.partner_id, i.partner_id) AS partner_id,
                       (SELECT COALESCE(NULLIF(pa.company_name, ''), pa.name) FROM partner pa
                        WHERE pa.id = COALESCE(p.partner_id, i.partner_id)) AS partner_name,
                       i.invoice_number, p.advance
                FROM bank_transaction t
                LEFT JOIN payment p ON p.id = t.payment_id
                LEFT JOIN invoice i ON i.id = COALESCE(t.invoice_id, p.invoice_id)
                WHERE t.id = ?
                """, t.getId());
        if (!info.isEmpty()) {
            partnerId = (UUID) info.get(0).get("partner_id");
            partnerName = (String) info.get(0).get("partner_name");
            invoiceNumber = (String) info.get(0).get("invoice_number");
            advance = (Boolean) info.get(0).get("advance");
        }
        UUID proposed = ledger.proposedAccount(t);
        String[] acc = proposed == null ? null : accounts.get(proposed);
        return new BankDtos.JournalLine(t.getId(), t.getBankAccountId(), t.getBookingDate(), t.getDescription(),
                t.getAmount(), t.getStatus(), t.getCategory(), partnerId, partnerName, t.getInvoiceId(), invoiceNumber,
                t.getPaymentId(), t.getExpenseId(), Boolean.TRUE.equals(advance) ? Boolean.TRUE : null,
                acc == null ? null : acc[0], acc == null ? null : acc[1], t.getValidatedAt(), t.getValidatedBy());
    }

    /** Lines still to validate, the statement balance, the bank account's ledger balance and the difference. */
    private BankDtos.JournalSummary summary(UUID orgId, UUID bankAccountId) {
        Long toValidate = jdbc.queryForObject("SELECT count(*) FROM bank_transaction WHERE organization_id = ?"
                + " AND deleted_at IS NULL AND validated_at IS NULL AND (CAST(? AS uuid) IS NULL OR bank_account_id = CAST(? AS uuid))",
                Long.class, orgId, bankAccountId, bankAccountId);
        BigDecimal statement = jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.opening_balance + COALESCE((SELECT SUM(t.amount) FROM bank_transaction t
                         WHERE t.bank_account_id = a.id AND t.deleted_at IS NULL
                           AND (a.opening_date IS NULL OR t.booking_date >= a.opening_date)), 0)), 0)
                FROM bank_account a
                WHERE a.organization_id = ? AND a.deleted_at IS NULL AND (CAST(? AS uuid) IS NULL OR a.id = CAST(? AS uuid))
                """, BigDecimal.class, orgId, bankAccountId, bankAccountId);
        List<UUID> ledgerAccounts = new ArrayList<>();
        for (UUID id : jdbc.queryForList("SELECT id FROM bank_account WHERE organization_id = ? AND deleted_at IS NULL"
                + " AND (CAST(? AS uuid) IS NULL OR id = CAST(? AS uuid))", UUID.class, orgId, bankAccountId, bankAccountId)) {
            ledgerAccounts.add(postingSetup.bankLedgerAccount(orgId, id));
        }
        BigDecimal ledgerBalance = BigDecimal.ZERO;
        for (UUID a : ledgerAccounts) {
            ledgerBalance = ledgerBalance.add(posting.balance(orgId, a, null));
        }
        BigDecimal st = (statement == null ? BigDecimal.ZERO : statement).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lb = ledgerBalance.setScale(2, RoundingMode.HALF_UP);
        return new BankDtos.JournalSummary(toValidate == null ? 0 : toValidate, st, lb, st.subtract(lb));
    }

    // Validate and unvalidate

    /** Validates the given lines, or every line still to validate up to a date, and posts their entries. */
    @Transactional
    public BankDtos.ValidateResult validate(BankDtos.ValidateRequest r) {
        UUID orgId = org();
        if (r == null || ((r.transactionIds() == null || r.transactionIds().isEmpty()) && r.upTo() == null)) {
            throw new IllegalArgumentException("Give the lines to validate (transactionIds) or a date (upTo)");
        }
        List<UUID> ids;
        if (r.transactionIds() != null && !r.transactionIds().isEmpty()) {
            ids = new ArrayList<>();
            for (UUID id : r.transactionIds()) {
                BankTransaction t = transactionRepository.find(orgId, id)
                        .orElseThrow(() -> new ResourceNotFoundException("Bank line not found"));
                if (r.bankAccountId() != null && !r.bankAccountId().equals(t.getBankAccountId())) {
                    throw new IllegalArgumentException("A line is not on this bank account");
                }
                if (t.getValidatedAt() == null && !ids.contains(id)) ids.add(id);
            }
        } else {
            ids = jdbc.queryForList("SELECT id FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL"
                    + " AND validated_at IS NULL AND booking_date <= ? AND (CAST(? AS uuid) IS NULL OR bank_account_id = CAST(? AS uuid))"
                    + " ORDER BY booking_date, created_at", UUID.class, orgId, r.upTo(), r.bankAccountId(), r.bankAccountId());
        }
        UUID user = currentUser();
        UUID suspense = postingSetup.accountByCode(orgId, SUSPENSE);
        int posted = 0;
        List<BankDtos.ValidateWarning> warnings = new ArrayList<>();
        for (UUID id : ids) {
            BankTransaction t = transactionRepository.find(orgId, id).orElseThrow();
            t.setValidatedAt(Instant.now());
            t.setValidatedBy(user);
            t = transactionRepository.saveAndFlush(t);
            if (sync(orgId, t)) posted++;
            if (suspense.equals(ledger.proposedAccount(t))) {
                warnings.add(new BankDtos.ValidateWarning(t.getId(),
                        "Posted to the suspense account 4497: categorize or match the line to reclassify it"));
            }
        }
        return new BankDtos.ValidateResult(ids.size(), posted, warnings);
    }

    /** Takes a line back to "to validate": its postings (and its payment's or expense's) are reversed. */
    @Transactional
    public BankDtos.JournalLine unvalidate(UUID id) {
        UUID orgId = org();
        BankTransaction t = transactionRepository.find(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Bank line not found"));
        if (t.getValidatedAt() == null) {
            throw new IllegalArgumentException("This line is not validated");
        }
        // Unvalidating reverses postings dated on the line's day: not in a locked period.
        posting.checkNotLocked(orgId, t.getBookingDate());
        t.setValidatedAt(null);
        t.setValidatedBy(null);
        t = transactionRepository.saveAndFlush(t);
        sync(orgId, t);
        Map<UUID, String[]> accounts = new HashMap<>();
        jdbc.query("SELECT id, code, name FROM ledger_account WHERE organization_id = ?", rs -> {
            accounts.put(rs.getObject(1, UUID.class), new String[]{rs.getString(2), rs.getString(3)});
        }, orgId);
        return line(orgId, t, accounts);
    }

    /** Brings the line's entry, and its payment's or expense's, in line with its state; true when something posts. */
    private boolean sync(UUID orgId, BankTransaction t) {
        ledger.bankLine(t);
        boolean live = ledger.hasLive(orgId, LedgerAutoPostingService.BANK_LINE, t.getId());
        if (t.getPaymentId() != null) {
            var p = paymentRepository.findByOrganizationIdAndId(orgId, t.getPaymentId()).orElse(null);
            if (p != null) {
                ledger.payment(p);
                live |= ledger.hasLive(orgId, LedgerAutoPostingService.PAYMENT, p.getId());
            }
        }
        if (t.getExpenseId() != null) {
            var e = expenseRepository.findForOrganization(orgId, t.getExpenseId()).orElse(null);
            if (e != null) {
                ledger.expense(e);
                live |= ledger.hasLive(orgId, LedgerAutoPostingService.EXPENSE, e.getId());
            }
        }
        return live;
    }

    // Mode

    /**
     * Changes the bank posting mode. To ON_IMPORT: everything still to validate posts now. To
     * ON_VALIDATION: the lines already posted under ON_IMPORT count as validated.
     */
    @Transactional
    public LedgerDtos.PostingSetup setMode(String mode) {
        UUID orgId = org();
        String m = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!m.equals(LedgerPostingSetupService.Snapshot.ON_VALIDATION) && !m.equals(LedgerPostingSetupService.Snapshot.ON_IMPORT)) {
            throw new IllegalArgumentException("The mode is ON_VALIDATION or ON_IMPORT");
        }
        String current = postingSetup.snapshot(orgId).bankPostingMode();
        if (!m.equals(current)) {
            if (m.equals(LedgerPostingSetupService.Snapshot.ON_VALIDATION)) {
                jdbc.update("UPDATE bank_transaction SET validated_at = now(), validated_by = ? WHERE organization_id = ?"
                        + " AND validated_at IS NULL AND deleted_at IS NULL", currentUser(), orgId);
                postingSetup.setBankPostingMode(orgId, m);
            } else {
                postingSetup.setBankPostingMode(orgId, m);
                for (UUID id : jdbc.queryForList("SELECT id FROM bank_transaction WHERE organization_id = ?"
                        + " AND validated_at IS NULL AND deleted_at IS NULL ORDER BY booking_date", UUID.class, orgId)) {
                    transactionRepository.find(orgId, id).ifPresent(t -> sync(orgId, t));
                }
            }
        }
        return postingSetup.current();
    }
}
