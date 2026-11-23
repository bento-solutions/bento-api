package com.bento.crm.ledger.service;

import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.model.LedgerAccount;
import com.bento.crm.ledger.model.LedgerCostCenter;
import com.bento.crm.ledger.model.LedgerEntry;
import com.bento.crm.ledger.model.LedgerEntryLine;
import com.bento.crm.ledger.model.LedgerJournal;
import com.bento.crm.ledger.repository.LedgerAccountRepository;
import com.bento.crm.ledger.repository.LedgerCostCenterRepository;
import com.bento.crm.ledger.repository.LedgerEntryLineRepository;
import com.bento.crm.ledger.repository.LedgerEntryRepository;
import com.bento.crm.ledger.repository.LedgerJournalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one way an entry gets into the ledger: manual entries, reversals and (from phase 2) the
 * automatic postings of invoices, payments and expenses all go through {@link #post}. It checks
 * that the entry balances and that every account, partner and cost center belongs to the
 * organization, numbers the entry in its journal and year, and writes the entry with its lines.
 */
@Service
@RequiredArgsConstructor
public class LedgerPostingService {

    /** One line to post. Exactly one of debit and credit is positive; null counts as zero. */
    public record PostingLine(UUID accountId, UUID partnerId, UUID costCenterId, String label,
                              BigDecimal debit, BigDecimal credit, Dimensions dimensions) {
        public PostingLine {
            if (dimensions == null) dimensions = Dimensions.NONE;
        }

        public PostingLine(UUID accountId, UUID partnerId, UUID costCenterId, String label, BigDecimal debit,
                           BigDecimal credit) {
            this(accountId, partnerId, costCenterId, label, debit, credit, Dimensions.NONE);
        }

        /** The same line with debit and credit swapped, for a reversal. */
        public static PostingLine reversing(LedgerEntryLine l) {
            return new PostingLine(l.getAccountId(), l.getPartnerId(), l.getCostCenterId(), l.getLabel(), l.getCredit(),
                    l.getDebit(), Dimensions.of(l));
        }
    }

    /** The analytic dimensions of an entry line besides its cost center: Mission, Deal, Customer and Vendor. */
    public record Dimensions(UUID missionId, UUID dealId, UUID customerId, UUID vendorId) {
        public static final Dimensions NONE = new Dimensions(null, null, null, null);

        public static Dimensions of(LedgerEntryLine l) {
            return new Dimensions(l.getMissionId(), l.getDealId(), l.getCustomerId(), l.getVendorId());
        }

        public boolean isEmpty() {
            return missionId == null && dealId == null && customerId == null && vendorId == null;
        }
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static final String LOCKED = "This period is locked";

    /**
     * What a posting dated on or before the lock date does: a manual entry (or a closing action a
     * person takes) is refused; an automatic posting moves to the day after the lock date.
     */
    public enum LockPolicy { REJECT, SHIFT }

    private final LedgerSetupService setup;
    private final LedgerJournalRepository journalRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerCostCenterRepository costCenterRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerEntryLineRepository lineRepository;
    private final JdbcTemplate jdbc;

    /**
     * Validates and writes a balanced entry in the journal with this code.
     *
     * @throws IllegalArgumentException (400) when the entry is malformed or does not balance
     * @throws ResourceNotFoundException (404) when an account, partner, cost center or the journal
     *         is not the organization's
     */
    @Transactional
    public LedgerEntry post(UUID orgId, String journalCode, LocalDate date, String description, String sourceType,
                            UUID sourceId, String sourceLabel, List<PostingLine> lines) {
        setup.ensure(orgId);
        LedgerJournal journal = journalRepository.findByOrganizationIdAndCode(orgId, journalCode)
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found: " + journalCode));
        return write(orgId, journal, date, description, sourceType, sourceId, sourceLabel, null, lines, false, false,
                LockPolicy.SHIFT);
    }

    /** Same as {@link #post}, refusing a date on or before the lock date (an action a person takes). */
    @Transactional
    public LedgerEntry postManual(UUID orgId, String journalCode, LocalDate date, String description, String sourceType,
                                  UUID sourceId, String sourceLabel, List<PostingLine> lines) {
        setup.ensure(orgId);
        LedgerJournal journal = journalRepository.findByOrganizationIdAndCode(orgId, journalCode)
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found: " + journalCode));
        return write(orgId, journal, date, description, sourceType, sourceId, sourceLabel, null, lines, false, false,
                LockPolicy.REJECT);
    }

    /**
     * Reverses an entry with a mirror entry (source REVERSAL) dated {@code date}, and marks it
     * reversed. Refused (400) when the date is locked and the policy is REJECT.
     */
    @Transactional
    public LedgerEntry reverse(UUID orgId, LedgerEntry original, LocalDate date, LockPolicy policy) {
        if (original.getReversedById() != null) {
            throw new IllegalArgumentException("This entry has already been reversed");
        }
        LedgerJournal journal = journalRepository.findByOrganizationIdAndId(orgId, original.getJournalId())
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found"));
        List<PostingLine> lines = lineRepository.findByOrganizationIdAndEntryIdIn(orgId, List.of(original.getId())).stream()
                .map(PostingLine::reversing)
                .toList();
        LedgerEntry reversal = write(orgId, journal, date, "Reversal of " + original.getNumber() + ": " + original.getDescription(),
                LedgerEntryService.SOURCE_REVERSAL, original.getId(), original.getNumber(), original.getId(), lines, true, true,
                policy);
        original.setReversedById(reversal.getId());
        entryRepository.saveAndFlush(original);
        return reversal;
    }

    // Lock date

    /** The organization's lock date, or null when none is set. */
    public LocalDate lockDate(UUID orgId) {
        List<LocalDate> rows = jdbc.queryForList("SELECT lock_date FROM ledger_settings WHERE organization_id = ?",
                LocalDate.class, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Whether a date is on or before the lock date. */
    public boolean isLocked(UUID orgId, LocalDate date) {
        LocalDate lock = lockDate(orgId);
        return lock != null && date != null && !date.isAfter(lock);
    }

    /** 400 "This period is locked" when the date is on or before the lock date. */
    public void checkNotLocked(UUID orgId, LocalDate date) {
        if (isLocked(orgId, date)) {
            throw new IllegalArgumentException(LOCKED);
        }
    }

    /** The date an automatic posting dated {@code date} gets: the day after the lock date when locked. */
    public LocalDate effectiveDate(UUID orgId, LocalDate date) {
        LocalDate lock = lockDate(orgId);
        return lock != null && date != null && !date.isAfter(lock) ? lock.plusDays(1) : date;
    }

    // Year-end carry-forward

    /**
     * The date of the last live carry-forward entry (the AN entry a year-end close posts) on or
     * before {@code asOf}, or 0001-01-01. Balances as of a date count only entries from then on:
     * the carry-forward already holds everything before it.
     */
    public LocalDate carryForwardFloor(UUID orgId, LocalDate asOf) {
        LocalDate floor = jdbc.queryForObject("""
                SELECT MAX(e.entry_date) FROM ledger_entry e JOIN ledger_journal j ON j.id = e.journal_id AND j.kind = 'OPENING'
                WHERE e.organization_id = ? AND e.source_type = 'YEAR_END' AND e.reversed_by_id IS NULL
                  AND e.reversal_of_id IS NULL AND e.deleted_at IS NULL AND e.entry_date <= ?
                """, LocalDate.class, orgId, asOf == null ? LocalDate.of(9999, 12, 31) : asOf);
        return floor == null ? LocalDate.of(1, 1, 1) : floor;
    }

    /** Balance (debit minus credit) of an account as of a date (null: everything), from the last carry-forward. */
    public BigDecimal balance(UUID orgId, UUID accountId, LocalDate asOf) {
        LocalDate to = asOf == null ? LocalDate.of(9999, 12, 31) : asOf;
        BigDecimal b = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                WHERE l.organization_id = ? AND l.account_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                """, BigDecimal.class, orgId, accountId, carryForwardFloor(orgId, to), to);
        return b == null ? ZERO : b.setScale(2, RoundingMode.HALF_UP);
    }

    /** Looks an account up by its code, for automatic postings that know the PCGE codes. */
    @Transactional
    public UUID accountId(UUID orgId, String code) {
        setup.ensure(orgId);
        return accountRepository.findByOrganizationIdAndCode(orgId, code)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + code)).getId();
    }

    /**
     * The shared write path. Reversals pass {@code allowInactiveAccounts}: undoing an entry must
     * stay possible after one of its accounts was deactivated.
     */
    LedgerEntry write(UUID orgId, LedgerJournal journal, LocalDate date, String description, String sourceType,
                      UUID sourceId, String sourceLabel, UUID reversalOfId, List<PostingLine> lines,
                      boolean allowInactiveAccounts) {
        return write(orgId, journal, date, description, sourceType, sourceId, sourceLabel, reversalOfId, lines,
                allowInactiveAccounts, allowInactiveAccounts, LockPolicy.REJECT);
    }

    /**
     * Automatic postings and reversals pass {@code allowDeletedPartners}: a document whose
     * customer was since moved to the bin still posts (and unposts) on that customer, which still
     * exists until the purge.
     */
    LedgerEntry write(UUID orgId, LedgerJournal journal, LocalDate date, String description, String sourceType,
                      UUID sourceId, String sourceLabel, UUID reversalOfId, List<PostingLine> lines,
                      boolean allowInactiveAccounts, boolean allowDeletedPartners, LockPolicy lockPolicy) {
        if (date == null) {
            throw new IllegalArgumentException("The entry date is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("The description is required");
        }
        LocalDate lock = lockDate(orgId);
        if (lock != null && !date.isAfter(lock)) {
            if (lockPolicy != LockPolicy.SHIFT) {
                throw new IllegalArgumentException(LOCKED);
            }
            String note = " (locked period, dated " + date + ")";
            String base = description.trim();
            if (base.length() + note.length() > 255) base = base.substring(0, 255 - note.length());
            description = base + note;
            date = lock.plusDays(1);
        }
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("An entry needs at least 2 lines");
        }

        List<BigDecimal[]> amounts = new ArrayList<>();
        BigDecimal totalDebit = ZERO;
        BigDecimal totalCredit = ZERO;
        for (int i = 0; i < lines.size(); i++) {
            PostingLine line = lines.get(i);
            int n = i + 1;
            if (line == null || line.accountId() == null) {
                throw new IllegalArgumentException("Line " + n + ": the account is required");
            }
            BigDecimal debit = amount(line.debit(), n);
            BigDecimal credit = amount(line.credit(), n);
            if (debit.signum() < 0 || credit.signum() < 0) {
                throw new IllegalArgumentException("Line " + n + ": amounts cannot be negative");
            }
            if (debit.signum() > 0 && credit.signum() > 0) {
                throw new IllegalArgumentException("Line " + n + ": enter either a debit or a credit, not both");
            }
            if (debit.signum() == 0 && credit.signum() == 0) {
                throw new IllegalArgumentException("Line " + n + ": enter a debit or a credit");
            }
            amounts.add(new BigDecimal[]{debit, credit});
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException("The entry does not balance: debit " + totalDebit.toPlainString()
                    + ", credit " + totalCredit.toPlainString());
        }

        Set<UUID> accountIds = lines.stream().map(PostingLine::accountId).collect(Collectors.toSet());
        Map<UUID, LedgerAccount> accounts = accountRepository.findByOrganizationIdAndIdIn(orgId, accountIds).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        Set<UUID> costCenterIds = lines.stream().map(PostingLine::costCenterId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, LedgerCostCenter> costCenters = costCenterIds.isEmpty() ? Map.of()
                : costCenterRepository.findByOrganizationIdAndIdIn(orgId, costCenterIds).stream()
                .collect(Collectors.toMap(LedgerCostCenter::getId, Function.identity()));
        Set<UUID> partnerIds = new HashSet<>();
        for (PostingLine line : lines) {
            if (line.partnerId() != null) {
                partnerIds.add(line.partnerId());
            }
        }
        Set<UUID> knownPartners = knownPartners(orgId, partnerIds, allowDeletedPartners);

        for (int i = 0; i < lines.size(); i++) {
            PostingLine line = lines.get(i);
            int n = i + 1;
            LedgerAccount account = accounts.get(line.accountId());
            if (account == null) {
                throw new ResourceNotFoundException("Line " + n + ": account not found");
            }
            if (!account.isActive() && !allowInactiveAccounts) {
                throw new IllegalArgumentException("Line " + n + ": account " + account.getCode() + " is inactive");
            }
            if (line.costCenterId() != null) {
                LedgerCostCenter cc = costCenters.get(line.costCenterId());
                if (cc == null) {
                    throw new ResourceNotFoundException("Line " + n + ": cost center not found");
                }
                if (!cc.isActive() && !allowInactiveAccounts) {
                    throw new IllegalArgumentException("Line " + n + ": cost center " + cc.getCode() + " is inactive");
                }
            }
            if (line.partnerId() != null && !knownPartners.contains(line.partnerId())) {
                throw new ResourceNotFoundException("Line " + n + ": partner not found");
            }
            checkDimensions(orgId, line.dimensions(), n);
        }

        LedgerEntry entry = new LedgerEntry();
        entry.setOrganizationId(orgId);
        entry.setJournalId(journal.getId());
        entry.setNumber(nextNumber(orgId, journal, date.getYear()));
        entry.setEntryDate(date);
        String desc = description.trim();
        entry.setDescription(desc.length() > 255 ? desc.substring(0, 255) : desc);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setSourceLabel(sourceLabel == null || sourceLabel.length() <= 160 ? sourceLabel : sourceLabel.substring(0, 160));
        entry.setReversalOfId(reversalOfId);
        entry.setTotalDebit(totalDebit);
        entry.setTotalCredit(totalCredit);
        entry = entryRepository.saveAndFlush(entry);

        List<LedgerEntryLine> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            PostingLine line = lines.get(i);
            LedgerEntryLine row = new LedgerEntryLine();
            row.setOrganizationId(orgId);
            row.setEntryId(entry.getId());
            row.setLineNo(i + 1);
            row.setAccountId(line.accountId());
            row.setPartnerId(line.partnerId());
            row.setCostCenterId(line.costCenterId());
            row.setMissionId(line.dimensions().missionId());
            row.setDealId(line.dimensions().dealId());
            row.setCustomerId(line.dimensions().customerId());
            row.setVendorId(line.dimensions().vendorId());
            String label = line.label() == null || line.label().isBlank() ? entry.getDescription() : line.label().trim();
            row.setLabel(label.length() > 255 ? label.substring(0, 255) : label);
            row.setDebit(amounts.get(i)[0]);
            row.setCredit(amounts.get(i)[1]);
            rows.add(row);
        }
        lineRepository.saveAllAndFlush(rows);
        return entry;
    }

    private static BigDecimal amount(BigDecimal value, int lineNo) {
        if (value == null) {
            return ZERO;
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Line " + lineNo + ": amounts have at most 2 decimals");
        }
    }

    /** 400 when a line's mission, deal, customer or vendor is not one of the organization's (deleted ones still count). */
    private void checkDimensions(UUID orgId, Dimensions d, int n) {
        if (d.isEmpty()) return;
        if (d.missionId() != null && !exists(orgId, "mission", d.missionId())) {
            throw new IllegalArgumentException("Line " + n + ": unknown mission");
        }
        if (d.dealId() != null && !exists(orgId, "deal", d.dealId())) {
            throw new IllegalArgumentException("Line " + n + ": unknown deal");
        }
        if (d.customerId() != null && !exists(orgId, "partner", d.customerId())) {
            throw new IllegalArgumentException("Line " + n + ": unknown customer");
        }
        if (d.vendorId() != null && !exists(orgId, "partner", d.vendorId())) {
            throw new IllegalArgumentException("Line " + n + ": unknown vendor");
        }
    }

    /** Whether the row exists in this tenant table (table is a constant, never input). */
    private boolean exists(UUID orgId, String table, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table
                + " WHERE organization_id = ? AND id = ?)", Boolean.class, orgId, id));
    }

    private Set<UUID> knownPartners(UUID orgId, Set<UUID> ids, boolean includeDeleted) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<UUID> found = new HashSet<>();
        for (UUID id : ids) {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM partner WHERE organization_id = ? AND id = ?"
                            + (includeDeleted ? "" : " AND deleted_at IS NULL") + ")",
                    Boolean.class, orgId, id);
            if (Boolean.TRUE.equals(exists)) {
                found.add(id);
            }
        }
        return found;
    }

    /**
     * Next number in the journal for the entry's year ("OD-2026-0001"). The upsert locks the
     * counter row until the posting commits, so concurrent postings to one journal queue up and a
     * rolled-back posting gives its number back: no duplicates and no gaps.
     */
    private String nextNumber(UUID orgId, LedgerJournal journal, int year) {
        Long value = jdbc.queryForObject("""
                INSERT INTO ledger_entry_sequence (organization_id, journal_id, year, next_value)
                VALUES (?, ?, ?, 2)
                ON CONFLICT (organization_id, journal_id, year)
                DO UPDATE SET next_value = ledger_entry_sequence.next_value + 1
                RETURNING next_value - 1
                """, Long.class, orgId, journal.getId(), year);
        return String.format("%s-%d-%04d", journal.getCode(), year, value);
    }
}
