package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.dto.LedgerDtos;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Journal entries: listing, manual entries and reversals. Entries are never edited or deleted. */
@Service
@RequiredArgsConstructor
public class LedgerEntryService {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_REVERSAL = "REVERSAL";

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerJournalRepository journalRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerCostCenterRepository costCenterRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerEntryLineRepository lineRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    @Transactional
    public PageResponse<LedgerDtos.Entry> list(UUID journalId, LocalDate from, LocalDate to, UUID accountId,
                                               UUID partnerId, UUID costCenterId, String q, Pageable pageable) {
        UUID orgId = org();
        StringBuilder where = new StringBuilder(" FROM ledger_entry e WHERE e.organization_id = ? AND e.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        if (journalId != null) {
            where.append(" AND e.journal_id = ?");
            params.add(journalId);
        }
        if (from != null) {
            where.append(" AND e.entry_date >= ?");
            params.add(from);
        }
        if (to != null) {
            where.append(" AND e.entry_date <= ?");
            params.add(to);
        }
        if (accountId != null || partnerId != null || costCenterId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM ledger_entry_line l WHERE l.entry_id = e.id AND l.deleted_at IS NULL");
            if (accountId != null) {
                where.append(" AND l.account_id = ?");
                params.add(accountId);
            }
            if (partnerId != null) {
                where.append(" AND l.partner_id = ?");
                params.add(partnerId);
            }
            if (costCenterId != null) {
                where.append(" AND l.cost_center_id = ?");
                params.add(costCenterId);
            }
            where.append(")");
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(e.number) LIKE ? OR LOWER(e.description) LIKE ?)");
            params.add(like);
            params.add(like);
        }
        int size = pageable.isPaged() ? Math.min(Math.max(pageable.getPageSize(), 1), 200) : 20;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<UUID> ids = jdbc.queryForList("SELECT e.id" + where
                + " ORDER BY e.entry_date DESC, e.number DESC LIMIT ? OFFSET ?", UUID.class, pageParams.toArray());
        Map<UUID, LedgerEntry> byId = entryRepository.findByOrganizationIdAndIdIn(orgId, ids.isEmpty() ? List.of() : ids)
                .stream().collect(Collectors.toMap(LedgerEntry::getId, Function.identity()));
        List<LedgerEntry> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        List<LedgerDtos.Entry> content = toDtos(orgId, ordered);
        return PageResponse.fromPage(new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0 : total));
    }

    @Transactional
    public LedgerDtos.Entry get(UUID id) {
        UUID orgId = org();
        return toDtos(orgId, List.of(entry(orgId, id))).get(0);
    }

    @Transactional
    public LedgerDtos.Entry createManual(LedgerDtos.EntryCreate request) {
        UUID orgId = org();
        LedgerJournal journal = request.journalId() == null
                ? journalRepository.findByOrganizationIdAndCode(orgId, "OD")
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found"))
                : journalRepository.findByOrganizationIdAndId(orgId, request.journalId())
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found"));
        if (journal.getKind() != LedgerJournal.Kind.MISC && journal.getKind() != LedgerJournal.Kind.OPENING) {
            throw new IllegalArgumentException("Manual entries go in the OD or AN journal, not " + journal.getCode());
        }
        List<LedgerPostingService.PostingLine> lines = request.lines() == null ? List.of() : request.lines().stream()
                .map(l -> l == null ? null : new LedgerPostingService.PostingLine(l.accountId(), l.partnerId(),
                        l.costCenterId(), l.label(), l.debit(), l.credit()))
                .toList();
        LedgerEntry entry = posting.write(orgId, journal, request.entryDate(), request.description(), SOURCE_MANUAL,
                null, null, null, lines, false);
        return toDtos(orgId, List.of(entry)).get(0);
    }

    @Transactional
    public LedgerDtos.Entry reverse(UUID id, LedgerDtos.ReverseRequest request) {
        UUID orgId = org();
        LedgerEntry original = entry(orgId, id);
        if (original.getReversalOfId() != null) {
            throw new IllegalArgumentException("A reversal cannot itself be reversed");
        }
        if (original.getReversedById() != null) {
            throw new IllegalArgumentException("This entry has already been reversed");
        }
        LedgerJournal journal = journalRepository.findByOrganizationIdAndId(orgId, original.getJournalId())
                .orElseThrow(() -> new ResourceNotFoundException("Journal not found"));
        List<LedgerPostingService.PostingLine> lines = lineRepository
                .findByOrganizationIdAndEntryIdIn(orgId, List.of(original.getId())).stream()
                .map(l -> new LedgerPostingService.PostingLine(l.getAccountId(), l.getPartnerId(), l.getCostCenterId(),
                        l.getLabel(), l.getCredit(), l.getDebit()))
                .toList();
        LocalDate date = request != null && request.entryDate() != null ? request.entryDate() : LocalDate.now();
        String description = request != null && request.description() != null && !request.description().isBlank()
                ? request.description() : truncate("Reversal of " + original.getNumber() + ": " + original.getDescription());
        LedgerEntry reversal;
        try {
            reversal = posting.write(orgId, journal, date, description, SOURCE_REVERSAL, original.getId(),
                    original.getNumber(), original.getId(), lines, true);
            original.setReversedById(reversal.getId());
            entryRepository.saveAndFlush(original);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent reversals: the unique index on reversal_of_id let only one through.
            throw new IllegalArgumentException("This entry has already been reversed");
        }
        return toDtos(orgId, List.of(reversal)).get(0);
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }

    private LedgerEntry entry(UUID orgId, UUID id) {
        return entryRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Entry not found"));
    }

    /** Entries with their lines and the names the screens show. */
    List<LedgerDtos.Entry> toDtos(UUID orgId, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<UUID> entryIds = entries.stream().map(LedgerEntry::getId).toList();
        Map<UUID, List<LedgerEntryLine>> linesByEntry = lineRepository.findByOrganizationIdAndEntryIdIn(orgId, entryIds)
                .stream().collect(Collectors.groupingBy(LedgerEntryLine::getEntryId));
        Collection<LedgerEntryLine> allLines = linesByEntry.values().stream().flatMap(List::stream).toList();

        Set<UUID> accountIds = allLines.stream().map(LedgerEntryLine::getAccountId).collect(Collectors.toSet());
        Map<UUID, LedgerAccount> accounts = accountIds.isEmpty() ? Map.of()
                : accountRepository.findAllById(accountIds).stream()
                .filter(a -> orgId.equals(a.getOrganizationId()))
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        Set<UUID> ccIds = allLines.stream().map(LedgerEntryLine::getCostCenterId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, LedgerCostCenter> costCenters = ccIds.isEmpty() ? Map.of()
                : costCenterRepository.findAllById(ccIds).stream()
                .filter(c -> orgId.equals(c.getOrganizationId()))
                .collect(Collectors.toMap(LedgerCostCenter::getId, Function.identity()));
        Set<UUID> partnerIds = allLines.stream().map(LedgerEntryLine::getPartnerId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> partners = names(orgId, "partner", "name", partnerIds);
        Set<UUID> userIds = entries.stream().map(LedgerEntry::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> users = names(orgId, "app_user", "display_name", userIds);
        Map<UUID, String> journals = journalRepository.findAll(orgId).stream()
                .collect(Collectors.toMap(LedgerJournal::getId, LedgerJournal::getCode));

        List<LedgerDtos.Entry> result = new ArrayList<>();
        for (LedgerEntry e : entries) {
            List<LedgerDtos.Line> lines = linesByEntry.getOrDefault(e.getId(), List.of()).stream()
                    .map(l -> {
                        LedgerAccount a = accounts.get(l.getAccountId());
                        LedgerCostCenter cc = l.getCostCenterId() == null ? null : costCenters.get(l.getCostCenterId());
                        return new LedgerDtos.Line(l.getId(), l.getLineNo(), l.getAccountId(),
                                a == null ? null : a.getCode(), a == null ? null : a.getName(), l.getPartnerId(),
                                l.getPartnerId() == null ? null : partners.get(l.getPartnerId()), l.getCostCenterId(),
                                cc == null ? null : cc.getCode(), l.getLabel(), l.getDebit(), l.getCredit());
                    })
                    .toList();
            result.add(new LedgerDtos.Entry(e.getId(), e.getNumber(), e.getJournalId(), journals.get(e.getJournalId()),
                    e.getEntryDate(), e.getDescription(), e.getSourceType(), e.getSourceId(), e.getSourceLabel(),
                    e.getReversalOfId(), e.getReversedById(), e.getTotalDebit(), e.getTotalCredit(),
                    e.getCreatedBy() == null ? null : users.get(e.getCreatedBy()), e.getCreatedAt(), lines));
        }
        return result;
    }

    /** id -> column for the given ids of a tenant table (table and column are constants, never input). */
    private Map<UUID, String> names(UUID orgId, String table, String column, Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        UUID[] array = new HashSet<>(ids).toArray(UUID[]::new);
        jdbc.query("SELECT id, " + column + " FROM " + table + " WHERE organization_id = ? AND id = ANY (?)",
                ps -> {
                    ps.setObject(1, orgId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", array));
                },
                rs -> {
                    names.put(rs.getObject(1, UUID.class), rs.getString(2));
                });
        return names;
    }
}
