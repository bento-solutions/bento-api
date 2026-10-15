package com.bento.crm.bank.service;

import com.bento.crm.bank.model.BankAccount;
import com.bento.crm.bank.model.BankImport;
import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.repository.BankAccountRepository;
import com.bento.crm.bank.repository.BankImportRepository;
import com.bento.crm.bank.repository.BankTransactionRepository;
import com.bento.crm.bank.statement.ParsedStatement;
import com.bento.crm.bank.statement.StatementText;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records a read statement in an account: skips lines already imported from an earlier
 * (overlapping) statement, checks the statement's balances add up, sorts the new lines into
 * categories and matches the ones that clearly pay an open invoice.
 */
@Service
@RequiredArgsConstructor
public class BankImportService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final BankAccountRepository accountRepository;
    private final BankImportRepository importRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankCategorizer categorizer;
    private final ReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    /** The account whose number is the statement's, comparing digits (a RIB contains the account number). */
    public Optional<BankAccount> matchAccount(UUID orgId, String number) {
        if (number == null) return Optional.empty();
        String n = number.replaceAll("\\D", "");
        if (n.length() < 8) return Optional.empty();
        List<BankAccount> hits = accountRepository.all(orgId).stream().filter(a -> {
            String a1 = a.getAccountNumber().replaceAll("\\D", "");
            if (a1.length() < 8) return false;
            return a1.equals(n) || a1.contains(n) || n.contains(a1);
        }).toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    @Transactional
    public BankImport importInto(UUID orgId, BankAccount account, ParsedStatement s, UUID fileId, UUID inboundDocumentId,
                                 String format) {
        if (!account.isActive()) {
            throw new IllegalStateException("This bank account is closed");
        }
        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "SELECT fingerprint FROM bank_transaction WHERE bank_account_id = ? AND deleted_at IS NULL",
                String.class, account.getId()));
        boolean firstImport = existing.isEmpty();

        BankImport imp = new BankImport();
        imp.setOrganizationId(orgId);
        imp.setBankAccountId(account.getId());
        imp.setFileId(fileId);
        imp.setInboundDocumentId(inboundDocumentId);
        imp.setFormat(format);
        imp.setParser(s.parser());
        imp.setPeriodStart(s.periodStart());
        imp.setPeriodEnd(s.periodEnd());
        imp.setOpeningBalance(s.openingBalance());
        imp.setClosingBalance(s.closingBalance());
        imp.setBalanceMismatch(!balances(s));
        imp = importRepository.save(imp);

        Map<String, Integer> seen = new HashMap<>();
        List<BankTransaction> added = new ArrayList<>();
        int skipped = 0;
        for (ParsedStatement.Row r : s.rows()) {
            String key = r.bookingDate() + "|" + r.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + "|" + StatementText.norm(r.description()).replaceAll("[^a-z0-9]", "")
                    + "|" + (r.reference() == null ? "" : r.reference().replaceAll("\\s", ""));
            // Two identical lines on the same day (two equal fees) are two movements, not one seen twice.
            int occurrence = seen.merge(key, 1, Integer::sum);
            String fp = sha256(key + "#" + occurrence);
            if (!existing.add(fp)) {
                skipped++;
                continue;
            }
            BankTransaction t = new BankTransaction();
            t.setOrganizationId(orgId);
            t.setBankAccountId(account.getId());
            t.setImportId(imp.getId());
            t.setBookingDate(r.bookingDate());
            t.setValueDate(r.valueDate());
            t.setDescription(cut(r.description(), 500));
            t.setReference(cut(r.reference(), 120));
            t.setAmount(r.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            t.setBalanceAfter(r.balance());
            t.setFingerprint(fp);
            added.add(t);
        }
        categorizer.categorize(orgId, added, true);
        // save() merges (the version field is pre-set), so the saved copies are the ones with ids.
        added = transactionRepository.saveAll(added);
        imp.setRowsImported(added.size());
        imp.setRowsSkipped(skipped);
        importRepository.save(imp);

        // The first statement of an account that had no starting point gives it one.
        if (firstImport && account.getOpeningDate() == null && s.openingBalance() != null && s.periodStart() != null) {
            account.setOpeningBalance(s.openingBalance());
            account.setOpeningDate(s.periodStart());
            accountRepository.save(account);
        }
        transactionRepository.flush();
        reconciliation.autoMatch(orgId, added);
        // Callers read the result back with plain SQL, which does not flush the session.
        transactionRepository.flush();
        return imp;
    }

    /** Opening + movements = closing, or, without those, the running balances follow the movements. */
    static boolean balances(ParsedStatement s) {
        BigDecimal sum = s.rows().stream().map(ParsedStatement.Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (s.openingBalance() != null && s.closingBalance() != null) {
            return s.openingBalance().add(sum).subtract(s.closingBalance()).abs().compareTo(TOLERANCE) <= 0;
        }
        List<ParsedStatement.Row> rows = s.rows();
        ParsedStatement.Row first = rows.get(0), last = rows.get(rows.size() - 1);
        if (first.balance() != null && last.balance() != null) {
            BigDecimal start = first.balance().subtract(first.amount());
            return start.add(sum).subtract(last.balance()).abs().compareTo(TOLERANCE) <= 0
                    // Some banks list the newest line first.
                    || last.balance().subtract(last.amount()).add(sum).subtract(first.balance()).abs().compareTo(TOLERANCE) <= 0;
        }
        return true;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
