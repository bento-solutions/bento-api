package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.model.LedgerAccount;
import com.bento.crm.ledger.model.LedgerCostCenter;
import com.bento.crm.ledger.repository.LedgerAccountRepository;
import com.bento.crm.ledger.repository.LedgerCostCenterRepository;
import com.bento.crm.ledger.repository.LedgerEntryLineRepository;
import com.bento.crm.ledger.repository.LedgerJournalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The chart of accounts, the journals and the cost centers. */
@Service
@RequiredArgsConstructor
public class LedgerAccountService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final LedgerSetupService setup;
    private final LedgerAccountRepository accountRepository;
    private final LedgerJournalRepository journalRepository;
    private final LedgerCostCenterRepository costCenterRepository;
    private final LedgerEntryLineRepository lineRepository;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    // Accounts

    @Transactional
    public List<LedgerDtos.Account> accounts(boolean includeInactive) {
        UUID orgId = org();
        Map<UUID, BigDecimal> balances = new HashMap<>();
        jdbc.query("""
                SELECT l.account_id, SUM(l.debit - l.credit) AS balance
                FROM ledger_entry_line l JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                WHERE l.organization_id = ? AND l.deleted_at IS NULL
                GROUP BY l.account_id
                """, rs -> {
            balances.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
        }, orgId);
        return accountRepository.findAll(orgId, includeInactive).stream()
                .map(a -> toDto(a, balances.containsKey(a.getId()), balances.getOrDefault(a.getId(), ZERO)))
                .toList();
    }

    @Transactional
    public LedgerDtos.Account createAccount(LedgerDtos.AccountCreate request) {
        UUID orgId = org();
        String code = request.code().trim();
        if (!code.matches("^[1-8][0-9]{3,9}$")) {
            throw new IllegalArgumentException("The account code must be 4 to 10 digits starting with 1 to 8");
        }
        if (accountRepository.findByOrganizationIdAndCode(orgId, code).isPresent()) {
            throw new IllegalArgumentException("An account with code " + code + " already exists");
        }
        LedgerAccount account = new LedgerAccount();
        account.setOrganizationId(orgId);
        account.setCode(code);
        account.setName(request.name().trim());
        account.setAccountClass((short) (code.charAt(0) - '0'));
        account.setKind(LedgerAccount.kindOf(code));
        account.setActive(true);
        account.setSystem(false);
        return toDto(accountRepository.saveAndFlush(account), false, ZERO);
    }

    @Transactional
    public LedgerDtos.Account updateAccount(UUID id, LedgerDtos.AccountUpdate request) {
        UUID orgId = org();
        LedgerAccount account = account(orgId, id);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new IllegalArgumentException("The account name cannot be empty");
            }
            account.setName(request.name().trim());
        }
        if (request.active() != null) {
            account.setActive(request.active());
        }
        account = accountRepository.saveAndFlush(account);
        return toDto(account, lineRepository.existsByAccount(orgId, id), balance(orgId, id));
    }

    @Transactional
    public void deleteAccount(UUID id) {
        UUID orgId = org();
        LedgerAccount account = account(orgId, id);
        if (account.isSystem()) {
            throw new IllegalArgumentException("System accounts cannot be deleted: deactivate it instead");
        }
        if (lineRepository.existsByAccount(orgId, id)) {
            throw new IllegalArgumentException("This account has entries: deactivate it instead");
        }
        account.setDeletedAt(Instant.now());
        accountRepository.save(account);
    }

    private LedgerAccount account(UUID orgId, UUID id) {
        return accountRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }

    private BigDecimal balance(UUID orgId, UUID accountId) {
        BigDecimal b = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit - l.credit), 0)
                FROM ledger_entry_line l JOIN ledger_entry e ON e.id = l.entry_id AND e.deleted_at IS NULL
                WHERE l.organization_id = ? AND l.account_id = ? AND l.deleted_at IS NULL
                """, BigDecimal.class, orgId, accountId);
        return b == null ? ZERO : b.setScale(2);
    }

    private static LedgerDtos.Account toDto(LedgerAccount a, boolean used, BigDecimal balance) {
        return new LedgerDtos.Account(a.getId(), a.getCode(), a.getName(), a.getAccountClass(), a.getKind().name(),
                a.isActive(), a.isSystem(), used, balance.setScale(2));
    }

    // Journals

    @Transactional
    public List<LedgerDtos.Journal> journals() {
        UUID orgId = org();
        return journalRepository.findAll(orgId).stream()
                .map(j -> new LedgerDtos.Journal(j.getId(), j.getCode(), j.getName(), j.getKind().name()))
                .toList();
    }

    // Cost centers

    @Transactional
    public List<LedgerDtos.CostCenter> costCenters(boolean includeInactive) {
        UUID orgId = org();
        List<LedgerCostCenter> all = costCenterRepository.findAll(orgId, includeInactive);
        Map<UUID, String> missions = missionNames(orgId,
                all.stream().map(LedgerCostCenter::getMissionId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return all.stream().map(c -> toDto(c, missions)).toList();
    }

    @Transactional
    public LedgerDtos.CostCenter createCostCenter(LedgerDtos.CostCenterCreate request) {
        UUID orgId = org();
        String code = request.code().trim();
        if (code.isEmpty() || code.length() > 20) {
            throw new IllegalArgumentException("The cost center code must be 1 to 20 characters");
        }
        if (costCenterRepository.existsCode(orgId, code)) {
            throw new IllegalArgumentException("A cost center with code " + code + " already exists");
        }
        LedgerCostCenter cc = new LedgerCostCenter();
        cc.setOrganizationId(orgId);
        cc.setCode(code);
        cc.setName(request.name().trim());
        cc.setActive(true);
        cc.setMissionId(checkMission(orgId, request.missionId()));
        cc = costCenterRepository.saveAndFlush(cc);
        return toDto(cc, missionNames(orgId, cc.getMissionId() == null ? Set.of() : Set.of(cc.getMissionId())));
    }

    @Transactional
    public LedgerDtos.CostCenter updateCostCenter(UUID id, LedgerDtos.CostCenterUpdate request) {
        UUID orgId = org();
        LedgerCostCenter cc = costCenter(orgId, id);
        if (request.getName() != null) {
            if (request.getName().isBlank()) {
                throw new IllegalArgumentException("The cost center name cannot be empty");
            }
            cc.setName(request.getName().trim());
        }
        if (request.getActive() != null) {
            cc.setActive(request.getActive());
        }
        if (request.isMissionIdPresent()) {
            cc.setMissionId(checkMission(orgId, request.getMissionId()));
        }
        cc = costCenterRepository.saveAndFlush(cc);
        return toDto(cc, missionNames(orgId, cc.getMissionId() == null ? Set.of() : Set.of(cc.getMissionId())));
    }

    @Transactional
    public void deleteCostCenter(UUID id) {
        UUID orgId = org();
        LedgerCostCenter cc = costCenter(orgId, id);
        if (lineRepository.existsByCostCenter(orgId, id)) {
            throw new IllegalArgumentException("This cost center is used on entries: deactivate it instead");
        }
        cc.setDeletedAt(Instant.now());
        costCenterRepository.save(cc);
    }

    private LedgerCostCenter costCenter(UUID orgId, UUID id) {
        return costCenterRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Cost center not found"));
    }

    private UUID checkMission(UUID orgId, UUID missionId) {
        if (missionId == null) {
            return null;
        }
        if (missionNames(orgId, Set.of(missionId)).isEmpty()) {
            throw new ResourceNotFoundException("Mission not found");
        }
        return missionId;
    }

    private Map<UUID, String> missionNames(UUID orgId, Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID missionId : ids) {
            jdbc.query("SELECT id, name FROM mission WHERE organization_id = ? AND id = ? AND deleted_at IS NULL",
                    rs -> {
                        names.put(rs.getObject(1, UUID.class), rs.getString(2));
                    }, orgId, missionId);
        }
        return names;
    }

    private static LedgerDtos.CostCenter toDto(LedgerCostCenter c, Map<UUID, String> missions) {
        return new LedgerDtos.CostCenter(c.getId(), c.getCode(), c.getName(), c.isActive(), c.getMissionId(),
                c.getMissionId() == null ? null : missions.get(c.getMissionId()));
    }
}
