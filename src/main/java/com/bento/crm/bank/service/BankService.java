package com.bento.crm.bank.service;

import com.bento.crm.bank.dto.BankDtos;
import com.bento.crm.bank.model.BankAccount;
import com.bento.crm.bank.model.BankImport;
import com.bento.crm.bank.model.BankRule;
import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.repository.BankAccountRepository;
import com.bento.crm.bank.repository.BankImportRepository;
import com.bento.crm.bank.repository.BankRuleRepository;
import com.bento.crm.bank.repository.BankTransactionRepository;
import com.bento.crm.bank.statement.ParsedStatement;
import com.bento.crm.bank.statement.StatementReader;
import com.bento.crm.bank.statement.StatementText;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.repository.InboundDocumentRepository;
import com.bento.crm.inbound.service.InboundIntakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Bank accounts, their statements and lines, and the categorization rules. */
@Service
@RequiredArgsConstructor
public class BankService {

    private final BankAccountRepository accountRepository;
    private final BankImportRepository importRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankRuleRepository ruleRepository;
    private final BankImportService importService;
    private final BankCategorizer categorizer;
    private final ReconciliationService reconciliation;
    private final StatementReader reader;
    private final FileStorageService fileStorageService;
    private final StoredFileRepository fileRepository;
    private final DriveService driveService;
    private final InboundDocumentRepository documentRepository;
    private final JdbcTemplate jdbc;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    // Accounts

    public List<BankDtos.Account> accounts() {
        UUID orgId = org();
        Map<UUID, Object[]> stats = new java.util.HashMap<>();
        jdbc.query("""
                SELECT a.id,
                       a.opening_balance + COALESCE(SUM(t.amount) FILTER (WHERE a.opening_date IS NULL OR t.booking_date >= a.opening_date), 0) AS balance,
                       MAX(t.booking_date) AS last_date,
                       COUNT(t.id) FILTER (WHERE t.status = 'UNMATCHED') AS unmatched
                FROM bank_account a LEFT JOIN bank_transaction t ON t.bank_account_id = a.id AND t.deleted_at IS NULL
                WHERE a.organization_id = ? AND a.deleted_at IS NULL GROUP BY a.id
                """, rs -> {
            stats.put(rs.getObject("id", UUID.class), new Object[]{rs.getBigDecimal("balance"),
                    rs.getObject("last_date", LocalDate.class), rs.getLong("unmatched")});
        }, orgId);
        return accountRepository.all(orgId).stream().map(a -> {
            Object[] s = stats.getOrDefault(a.getId(), new Object[]{a.getOpeningBalance(), null, 0L});
            return new BankDtos.Account(a.getId(), a.getName(), a.getBank(), a.getAccountNumber(), a.getCurrency(),
                    a.getOpeningBalance(), a.getOpeningDate(), a.isActive(), (BigDecimal) s[0], (LocalDate) s[1], (Long) s[2]);
        }).toList();
    }

    public BankDtos.Account account(UUID id) {
        return accounts().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
    }

    @Transactional
    public BankDtos.Account createAccount(BankDtos.AccountRequest r) {
        BankAccount a = new BankAccount();
        a.setOrganizationId(org());
        apply(a, r, true);
        return account(save(a).getId());
    }

    @Transactional
    public BankDtos.Account updateAccount(UUID id, BankDtos.AccountRequest r) {
        BankAccount a = find(id);
        apply(a, r, false);
        save(a);
        return account(id);
    }

    @Transactional
    public void deleteAccount(UUID id) {
        BankAccount a = find(id);
        Long lines = jdbc.queryForObject("SELECT count(*) FROM bank_transaction WHERE bank_account_id = ? AND deleted_at IS NULL",
                Long.class, id);
        if (lines != null && lines > 0) {
            throw new IllegalStateException("This account has statement lines: close it instead");
        }
        a.setDeletedAt(Instant.now());
        accountRepository.save(a);
    }

    private BankAccount save(BankAccount a) {
        try {
            return accountRepository.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Another account already has this number");
        }
    }

    private static void apply(BankAccount a, BankDtos.AccountRequest r, boolean create) {
        if (create || r.name() != null) {
            if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Enter the account name");
            a.setName(r.name().trim());
        }
        if (create || r.accountNumber() != null) {
            String digits = r.accountNumber() == null ? "" : r.accountNumber().replaceAll("\\D", "");
            if (digits.length() < 8 || digits.length() > 30) {
                throw new IllegalArgumentException("Enter the RIB (24 digits) or the account number");
            }
            a.setAccountNumber(digits);
        }
        if (r.bank() != null) {
            a.setBank(r.bank());
        } else if (create) {
            BankAccount.Bank guess = StatementText.bank("", a.getAccountNumber());
            a.setBank(guess == null ? BankAccount.Bank.OTHER : guess);
        }
        if (r.currency() != null && !r.currency().isBlank()) {
            if (!r.currency().trim().matches("[A-Za-z]{3}")) throw new IllegalArgumentException("Currency is a 3-letter code");
            a.setCurrency(r.currency().trim().toUpperCase(Locale.ROOT));
        }
        if (r.openingBalance() != null) a.setOpeningBalance(r.openingBalance());
        if (r.openingDate() != null) a.setOpeningDate(r.openingDate());
        if (r.active() != null) a.setActive(r.active());
    }

    private BankAccount find(UUID id) {
        return accountRepository.find(org(), id).orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
    }

    // Statements

    public List<BankDtos.Import> imports(UUID accountId) {
        return importRepository.all(org()).stream()
                .filter(i -> accountId == null || i.getBankAccountId().equals(accountId))
                .sorted((x, y) -> y.getCreatedAt().compareTo(x.getCreatedAt()))
                .map(BankDtos.Import::of).toList();
    }

    /** A statement dropped on an account: stored in the drive, read and imported now. */
    @Transactional
    public BankDtos.ImportResult upload(UUID accountId, MultipartFile file) throws IOException {
        UUID orgId = org();
        BankAccount account = find(accountId);
        byte[] content = file.getBytes();
        String type = InboundIntakeService.sniff(content, file.getContentType(), file.getOriginalFilename());
        if (!InboundIntakeService.acceptable(InboundDocument.Kind.BANK_STATEMENT, type, content)) {
            throw new IllegalArgumentException("Send a CSV, an Excel file, a PDF or a picture of the statement");
        }
        StatementReader.Format format = StatementReader.format(type, file.getOriginalFilename());
        ParsedStatement s = reader.read(content, type, file.getOriginalFilename());
        checkNumber(account, s);
        StoredFile stored = fileStorageService.storeBytes(orgId, content,
                file.getOriginalFilename() == null ? "releve" : file.getOriginalFilename(), type, null, null,
                StoredFile.Source.UPLOAD, driveService.systemFolder(orgId, DriveService.BANK_STATEMENTS, YearMonth.now()).getId());
        BankImport imp = importService.importInto(orgId, account, s, stored.getId(), null, format.name().toLowerCase(Locale.ROOT));
        return result(imp, s);
    }

    /** A statement from the inbox that could not be placed: a person chose the account. */
    @Transactional
    public BankDtos.ImportResult importDocument(UUID documentId, UUID accountId) {
        UUID orgId = org();
        InboundDocument d = documentRepository.find(orgId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (d.getKind() != InboundDocument.Kind.BANK_STATEMENT) {
            throw new IllegalArgumentException("This document is not a bank statement");
        }
        if (d.getStatus() == InboundDocument.Status.FILED || d.getStatus() == InboundDocument.Status.QUEUED) {
            throw new IllegalStateException("This statement is already imported or waiting to be read");
        }
        BankAccount account = find(accountId);
        StoredFile file = fileRepository.findById(d.getFileId()).orElseThrow(() -> new ResourceNotFoundException("File not found"));
        byte[] content = fileStorageService.readAll(file);
        ParsedStatement s = reader.read(content, file.getContentType(), file.getFileName());
        checkNumber(account, s);
        BankImport imp = importService.importInto(orgId, account, s, file.getId(), d.getId(),
                StatementReader.format(file.getContentType(), file.getFileName()).name().toLowerCase(Locale.ROOT));
        d.setStatus(InboundDocument.Status.FILED);
        d.setReviewReason(imp.isBalanceMismatch() ? "The statement's balances do not add up: check the lines" : null);
        d.setProcessedAt(Instant.now());
        documentRepository.save(d);
        return result(imp, s);
    }

    /** A statement that names another account is refused, so lines never land in the wrong account. */
    private static void checkNumber(BankAccount account, ParsedStatement s) {
        if (s.accountNumber() == null || s.accountNumber().length() < 8) return;
        String a = account.getAccountNumber(), n = s.accountNumber();
        if (!(a.equals(n) || a.contains(n) || n.contains(a))) {
            throw new IllegalArgumentException("This statement is for account " + n + ", not " + a);
        }
    }

    private BankDtos.ImportResult result(BankImport imp, ParsedStatement s) {
        Long matched = jdbc.queryForObject("SELECT count(*) FROM bank_transaction WHERE import_id = ? AND status = 'MATCHED'",
                Long.class, imp.getId());
        return new BankDtos.ImportResult(BankDtos.Import.of(imp), s.bank(), s.accountNumber(), matched == null ? 0 : matched.intValue());
    }

    @Transactional
    public void deleteImport(UUID id) {
        UUID orgId = org();
        BankImport imp = importRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Import not found"));
        Long matched = jdbc.queryForObject("SELECT count(*) FROM bank_transaction WHERE import_id = ? AND status = 'MATCHED'"
                + " AND deleted_at IS NULL", Long.class, id);
        if (matched != null && matched > 0) {
            throw new IllegalStateException("Unmatch this statement's lines before removing it");
        }
        jdbc.update("UPDATE bank_transaction SET deleted_at = now() WHERE import_id = ? AND deleted_at IS NULL", id);
        imp.setDeletedAt(Instant.now());
        importRepository.save(imp);
    }

    // Lines

    private static final String TX_SELECT = """
            SELECT t.*, i.invoice_number,
                   CASE WHEN t.invoice_id IS NOT NULL THEN COALESCE(pa.name, i.customer_name)
                        WHEN t.expense_id IS NOT NULL THEN COALESCE(e.vendor_name, e.description, e.category)
                        WHEN t.payment_id IS NOT NULL THEN pp.name END AS match_label
            FROM bank_transaction t
            LEFT JOIN invoice i ON i.id = t.invoice_id
            LEFT JOIN partner pa ON pa.id = i.partner_id
            LEFT JOIN expense e ON e.id = t.expense_id
            LEFT JOIN payment p ON p.id = t.payment_id
            LEFT JOIN partner pp ON pp.id = p.partner_id
            """;

    public PageResponse<BankDtos.Transaction> transactions(UUID accountId, BankTransaction.Status status, String category,
                                                           String q, LocalDate from, LocalDate to, String direction,
                                                           int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE t.organization_id = ? AND t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(org());
        if (accountId != null) { where.append(" AND t.bank_account_id = ?"); args.add(accountId); }
        if (status != null) { where.append(" AND t.status = ?"); args.add(status.name()); }
        if ("NONE".equalsIgnoreCase(category)) where.append(" AND t.category IS NULL");
        else if (category != null && !category.isBlank()) { where.append(" AND t.category = ?"); args.add(category.toUpperCase(Locale.ROOT)); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (t.description ILIKE ? OR t.reference ILIKE ?)");
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        if (from != null) { where.append(" AND t.booking_date >= ?"); args.add(from); }
        if (to != null) { where.append(" AND t.booking_date <= ?"); args.add(to); }
        if ("IN".equalsIgnoreCase(direction)) where.append(" AND t.amount > 0");
        if ("OUT".equalsIgnoreCase(direction)) where.append(" AND t.amount < 0");
        Long total = jdbc.queryForObject("SELECT count(*) FROM bank_transaction t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<BankDtos.Transaction> rows = jdbc.query(TX_SELECT + where
                + " ORDER BY t.booking_date DESC, t.created_at DESC LIMIT ? OFFSET ?", BankService::row, pageArgs.toArray());
        return PageResponse.fromPage(new PageImpl<>(rows, PageRequest.of(page, size), total == null ? 0 : total));
    }

    public BankDtos.Transaction transaction(UUID id) {
        List<BankDtos.Transaction> r = jdbc.query(TX_SELECT + " WHERE t.organization_id = ? AND t.id = ? AND t.deleted_at IS NULL",
                BankService::row, org(), id);
        if (r.isEmpty()) throw new ResourceNotFoundException("Bank line not found");
        return r.get(0);
    }

    public List<BankDtos.Candidate> candidates(UUID id, String q) {
        BankTransaction t = reconciliation.transaction(org(), id);
        return q == null || q.isBlank() ? reconciliation.candidates(org(), t) : reconciliation.search(org(), t, q);
    }

    public BankDtos.Transaction match(UUID id, BankDtos.MatchRequest r) {
        reconciliation.match(org(), id, r.kind(), r.id());
        return transaction(id);
    }

    public BankDtos.Transaction unmatch(UUID id) {
        reconciliation.unmatch(org(), id);
        return transaction(id);
    }

    public BankDtos.Transaction ignore(UUID id, boolean ignore) {
        reconciliation.ignore(org(), id, ignore);
        return transaction(id);
    }

    /** Sets a line's category; with "remember", also a rule so later lines like it get the same. */
    @Transactional
    public BankDtos.Transaction setCategory(UUID id, BankDtos.CategoryRequest r) {
        UUID orgId = org();
        BankTransaction t = reconciliation.transaction(orgId, id);
        String category = BankCategories.validate(r.category());
        t.setCategory(category);
        t.setCategorySource(category == null ? null : BankTransaction.CategorySource.MANUAL);
        transactionRepository.save(t);
        if (Boolean.TRUE.equals(r.remember()) && category != null) {
            String text = r.containsText() == null || r.containsText().isBlank() ? t.getDescription() : r.containsText().trim();
            createRule(new BankDtos.RuleRequest(text.length() > 120 ? text.substring(0, 120) : text,
                    t.getAmount().signum() > 0 ? BankRule.Direction.IN : BankRule.Direction.OUT, category));
        }
        transactionRepository.flush();
        return transaction(id);
    }

    /** Runs the rules, the keywords and the AI again over lines without a category. */
    @Transactional
    public int recategorize() {
        UUID orgId = org();
        List<BankTransaction> open = transactionRepository.all(orgId).stream().filter(t -> t.getCategory() == null).toList();
        int n = categorizer.categorize(orgId, open, true);
        transactionRepository.saveAll(open);
        return n;
    }

    // Rules

    public List<BankDtos.Rule> rules() {
        return ruleRepository.all(org()).stream().map(BankDtos.Rule::of).toList();
    }

    @Transactional
    public BankDtos.Rule createRule(BankDtos.RuleRequest r) {
        UUID orgId = org();
        if (r.containsText() == null || r.containsText().trim().length() < 2) {
            throw new IllegalArgumentException("Enter the text the label contains");
        }
        BankRule rule = new BankRule();
        rule.setOrganizationId(orgId);
        rule.setContainsText(r.containsText().trim());
        rule.setDirection(r.direction() == null ? BankRule.Direction.ANY : r.direction());
        rule.setCategory(BankCategories.validate(r.category()));
        if (rule.getCategory() == null) throw new IllegalArgumentException("Choose a category");
        rule = ruleRepository.save(rule);
        // Lines already imported that the rule covers and nobody sorted by hand follow it now.
        List<BankRule> one = List.of(rule);
        List<BankTransaction> changed = new ArrayList<>();
        for (BankTransaction t : transactionRepository.all(orgId)) {
            if (t.getCategorySource() == BankTransaction.CategorySource.MANUAL
                    || t.getCategorySource() == BankTransaction.CategorySource.MATCH
                    || t.getCategorySource() == BankTransaction.CategorySource.RULE) continue;
            if (BankCategorizer.rule(one, t) != null) {
                t.setCategory(rule.getCategory());
                t.setCategorySource(BankTransaction.CategorySource.RULE);
                changed.add(t);
            }
        }
        transactionRepository.saveAll(changed);
        return BankDtos.Rule.of(rule);
    }

    @Transactional
    public void deleteRule(UUID id) {
        BankRule r = ruleRepository.find(org(), id).orElseThrow(() -> new ResourceNotFoundException("Rule not found"));
        r.setDeletedAt(Instant.now());
        ruleRepository.save(r);
    }

    private static BankDtos.Transaction row(ResultSet rs, int i) throws SQLException {
        String source = rs.getString("category_source");
        return new BankDtos.Transaction(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                rs.getObject("booking_date", LocalDate.class), rs.getObject("value_date", LocalDate.class),
                rs.getString("description"), rs.getString("reference"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"), rs.getString("category"),
                source == null ? null : BankTransaction.CategorySource.valueOf(source),
                BankTransaction.Status.valueOf(rs.getString("status")), rs.getObject("invoice_id", UUID.class),
                rs.getString("invoice_number"), rs.getObject("payment_id", UUID.class), rs.getObject("expense_id", UUID.class),
                rs.getBoolean("payment_created"), rs.getString("match_label"));
    }
}
