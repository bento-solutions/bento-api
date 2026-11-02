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
                              BigDecimal debit, BigDecimal credit) {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

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
        return write(orgId, journal, date, description, sourceType, sourceId, sourceLabel, null, lines, false);
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
                allowInactiveAccounts, allowInactiveAccounts);
    }

    /**
     * Automatic postings and reversals pass {@code allowDeletedPartners}: a document whose
     * customer was since moved to the bin still posts (and unposts) on that customer, which still
     * exists until the purge.
     */
    LedgerEntry write(UUID orgId, LedgerJournal journal, LocalDate date, String description, String sourceType,
                      UUID sourceId, String sourceLabel, UUID reversalOfId, List<PostingLine> lines,
                      boolean allowInactiveAccounts, boolean allowDeletedPartners) {
        if (date == null) {
            throw new IllegalArgumentException("The entry date is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("The description is required");
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
