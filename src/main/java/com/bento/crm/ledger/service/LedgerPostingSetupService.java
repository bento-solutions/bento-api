package com.bento.crm.ledger.service;

import com.bento.crm.bank.service.BankCategories;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.model.LedgerAccount;
import com.bento.crm.ledger.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The posting setup: which accounts the automatic postings use. Partner groups give the
 * receivable or payable account, business x product groups the sales and purchase accounts, the
 * VAT setup the VAT accounts, and the bank, cash and category rows the treasury side. Seeded per
 * organization by {@link LedgerSetupService}; every write returns the whole setup again.
 */
@Service
@RequiredArgsConstructor
public class LedgerPostingSetupService {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPPLIER = "SUPPLIER";
    private static final String CODE_PATTERN = "^[A-Z0-9_-]{1,20}$";

    private final LedgerSetupService setup;
    private final LedgerAccountRepository accountRepository;
    private final JdbcTemplate jdbc;

    // Snapshot used by the automatic postings

    public record PartnerGroupRow(UUID id, String code, String name, String side, UUID accountId, boolean isDefault) {}

    /** A business or product group; vatCode is a product group's, vatRule and exemptVatCode a business group's. */
    public record GroupRow(UUID id, String code, String name, boolean isDefault, String vatCode, String vatRule,
                           String exemptVatCode) {}

    public record GeneralRow(UUID id, UUID businessGroupId, UUID productGroupId, UUID salesAccountId,
                             UUID purchaseAccountId) {}

    public record VatRow(UUID id, String code, String name, BigDecimal rate, UUID collectedAccountId,
                         UUID recoverableAccountId, boolean exempt, String mention) {}

    /** Everything a posting needs, read once per document. */
    public record Snapshot(LocalDate startDate, UUID cashAccountId, List<PartnerGroupRow> partnerGroups,
                           List<GroupRow> businessGroups, List<GroupRow> productGroups, List<GeneralRow> general,
                           List<VatRow> vat, Map<String, UUID> bankCategories, Map<String, UUID> expenseCategories,
                           String bankPostingMode) {

        public static final String ON_VALIDATION = "ON_VALIDATION";
        public static final String ON_IMPORT = "ON_IMPORT";

        /** Bank lines (and what they settle) wait for a person to validate them. */
        public boolean postOnValidation() {
            return !ON_IMPORT.equals(bankPostingMode);
        }

        /** The group row (business or product) with this id, or null. */
        public GroupRow businessGroupRow(UUID id) {
            return businessGroups.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
        }

        public GroupRow productGroupRow(UUID id) {
            return productGroups.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
        }

        /** The partner group's account, or the side's default group's when null or unknown. */
        public UUID partnerAccount(String side, UUID groupId) {
            PartnerGroupRow fallback = null;
            for (PartnerGroupRow g : partnerGroups) {
                if (!g.side().equals(side)) continue;
                if (g.id().equals(groupId)) return g.accountId();
                if (g.isDefault()) fallback = g;
            }
            if (fallback == null) {
                throw new IllegalArgumentException("No default " + side.toLowerCase(Locale.ROOT)
                        + " posting group: set one in the ledger settings");
            }
            return fallback.accountId();
        }

        public UUID businessGroup(UUID id) {
            return group(businessGroups, id, "business");
        }

        public UUID productGroup(UUID id) {
            return group(productGroups, id, "product");
        }

        /** The product group with this code (GOODS, SERVICES), else the default one. */
        public UUID productGroupByCode(String code) {
            return productGroups.stream().filter(g -> g.code().equals(code)).map(GroupRow::id).findFirst()
                    .orElseGet(() -> productGroup(null));
        }

        public boolean hasProductGroup(UUID id) {
            return productGroups.stream().anyMatch(g -> g.id().equals(id));
        }

        private static UUID group(List<GroupRow> groups, UUID id, String kind) {
            UUID fallback = null;
            for (GroupRow g : groups) {
                if (g.id().equals(id)) return g.id();
                if (g.isDefault()) fallback = g.id();
            }
            if (fallback == null) {
                throw new IllegalArgumentException("No default " + kind + " posting group: set one in the ledger settings");
            }
            return fallback;
        }

        public GeneralRow general(UUID businessGroupId, UUID productGroupId) {
            for (GeneralRow r : general) {
                if (r.businessGroupId().equals(businessGroupId) && r.productGroupId().equals(productGroupId)) return r;
            }
            UUID b = businessGroup(null), p = productGroup(null);
            for (GeneralRow r : general) {
                if (r.businessGroupId().equals(b) && r.productGroupId().equals(p)) return r;
            }
            throw new IllegalArgumentException("No sales and purchase accounts are set for this business and product group");
        }

        /** The VAT row of a rate given as a fraction (0.20), or null when none matches. */
        public VatRow vatFor(BigDecimal fraction) {
            BigDecimal percent = fraction.movePointRight(2);
            VatRow exempt = null;
            for (VatRow r : vat) {
                if (r.rate().compareTo(percent) == 0) {
                    if (!r.exempt()) return r;
                    if (exempt == null) exempt = r;
                }
            }
            return exempt;
        }

        public VatRow vatByCode(String code) {
            return vat.stream().filter(r -> r.code().equals(code)).findFirst().orElse(null);
        }
    }

    @Transactional
    public Snapshot snapshot(UUID orgId) {
        setup.ensure(orgId);
        Map<String, Object> settings = jdbc.queryForMap(
                "SELECT start_date, cash_account_id, bank_posting_mode FROM ledger_settings WHERE organization_id = ?", orgId);
        LocalDate start = ((java.sql.Date) settings.get("start_date")).toLocalDate();
        UUID cash = (UUID) settings.get("cash_account_id");
        String bankPostingMode = (String) settings.get("bank_posting_mode");
        List<PartnerGroupRow> partnerGroups = jdbc.query(
                "SELECT id, code, name, side, account_id, is_default FROM ledger_partner_group WHERE organization_id = ?"
                        + " ORDER BY side, is_default DESC, code",
                (rs, i) -> new PartnerGroupRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getBoolean(6)), orgId);
        List<GroupRow> business = jdbc.query("SELECT id, code, name, is_default, NULL, vat_rule, exempt_vat_code"
                        + " FROM ledger_business_group WHERE organization_id = ? ORDER BY is_default DESC, code",
                LedgerPostingSetupService::groupRow, orgId);
        List<GroupRow> product = jdbc.query("SELECT id, code, name, is_default, vat_code, NULL, NULL"
                        + " FROM ledger_product_group WHERE organization_id = ? ORDER BY is_default DESC, code",
                LedgerPostingSetupService::groupRow, orgId);
        List<GeneralRow> general = jdbc.query("""
                SELECT g.id, g.business_group_id, g.product_group_id, g.sales_account_id, g.purchase_account_id
                FROM ledger_general_setup g
                JOIN ledger_business_group b ON b.id = g.business_group_id
                JOIN ledger_product_group p ON p.id = g.product_group_id
                WHERE g.organization_id = ? ORDER BY b.is_default DESC, b.code, p.is_default DESC, p.code
                """, (rs, i) -> new GeneralRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class)), orgId);
        List<VatRow> vat = jdbc.query("""
                SELECT id, code, name, rate, collected_account_id, recoverable_account_id, exempt, mention
                FROM ledger_vat_setup WHERE organization_id = ? ORDER BY exempt, rate DESC, code
                """, (rs, i) -> new VatRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getBoolean(7),
                rs.getString(8)), orgId);
        return new Snapshot(start, cash, partnerGroups, business, product, general, vat, categories(orgId, "BANK"),
                categories(orgId, "EXPENSE"), bankPostingMode == null ? Snapshot.ON_VALIDATION : bankPostingMode);
    }

    private static GroupRow groupRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new GroupRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getString(5), rs.getString(6), rs.getString(7));
    }

    private Map<String, UUID> categories(UUID orgId, String kind) {
        Map<String, UUID> map = new LinkedHashMap<>();
        jdbc.query("SELECT category, account_id FROM ledger_category_account WHERE organization_id = ? AND kind = ?"
                + " ORDER BY category", rs -> {
            map.put(rs.getString(1), rs.getObject(2, UUID.class));
        }, orgId, kind);
        return map;
    }

    /** The account of a PCGE code, for the few rules that name one (4456, 34552, 5141). */
    @Transactional
    public UUID accountByCode(UUID orgId, String code) {
        setup.ensure(orgId);
        return accountRepository.findByOrganizationIdAndCode(orgId, code)
                .orElseThrow(() -> new IllegalArgumentException("Account " + code + " is missing from the chart of accounts"))
                .getId();
    }

    /**
     * The ledger account of a bank account, creating its 5141 sub-account (514101, 514102...) the
     * first time. Without a bank account, the 5141 account itself.
     */
    @Transactional
    public UUID bankLedgerAccount(UUID orgId, UUID bankAccountId) {
        setup.ensure(orgId);
        if (bankAccountId == null) {
            return accountByCode(orgId, "5141");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, ledger_account_id FROM bank_account WHERE organization_id = ? AND id = ? FOR UPDATE",
                orgId, bankAccountId);
        if (rows.isEmpty()) {
            return accountByCode(orgId, "5141");
        }
        UUID existing = (UUID) rows.get(0).get("ledger_account_id");
        if (existing != null) {
            return existing;
        }
        String name = (String) rows.get(0).get("name");
        name = name == null || name.isBlank() ? "Banque" : name.trim();
        if (name.length() > 160) name = name.substring(0, 160);
        Integer max = jdbc.queryForObject("SELECT MAX(CAST(SUBSTRING(code FROM 5) AS INT)) FROM ledger_account"
                + " WHERE organization_id = ? AND code ~ '^5141[0-9]{2}$'", Integer.class, orgId);
        int next = max == null ? 1 : max + 1;
        UUID accountId = null;
        for (int attempt = 0; accountId == null && attempt < 100; attempt++, next++) {
            if (next > 99) {
                throw new IllegalStateException("No free 5141 sub-account left for this bank account");
            }
            String code = String.format("5141%02d", next);
            List<UUID> ids = jdbc.queryForList("""
                    INSERT INTO ledger_account (organization_id, code, name, account_class, kind, is_system)
                    VALUES (?, ?, ?, 5, 'ASSET', TRUE)
                    ON CONFLICT (organization_id, code) WHERE deleted_at IS NULL DO NOTHING
                    RETURNING id
                    """, UUID.class, orgId, code, name);
            accountId = ids.isEmpty() ? null : ids.get(0);
        }
        jdbc.update("UPDATE bank_account SET ledger_account_id = ? WHERE organization_id = ? AND id = ?",
                accountId, orgId, bankAccountId);
        return accountId;
    }

    /** The bank account a payment goes through when nobody said: the first open one, else the first one. */
    public UUID defaultBankAccountId(UUID orgId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM bank_account WHERE organization_id = ? AND deleted_at IS NULL"
                + " ORDER BY is_active DESC, created_at LIMIT 1", UUID.class, orgId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void ensureBankAccounts(UUID orgId) {
        for (UUID id : jdbc.queryForList("SELECT id FROM bank_account WHERE organization_id = ? AND deleted_at IS NULL"
                + " AND ledger_account_id IS NULL ORDER BY created_at", UUID.class, orgId)) {
            bankLedgerAccount(orgId, id);
        }
    }

    // API

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    @Transactional
    public LedgerDtos.PostingSetup get() {
        return view(org());
    }

    private LedgerDtos.PostingSetup view(UUID orgId) {
        ensureBankAccounts(orgId);
        Snapshot s = snapshot(orgId);
        Map<UUID, String> codes = new HashMap<>();
        jdbc.query("SELECT id, code FROM ledger_account WHERE organization_id = ?", rs -> {
            codes.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, orgId);
        List<LedgerDtos.PartnerGroup> partnerGroups = s.partnerGroups().stream()
                .map(g -> new LedgerDtos.PartnerGroup(g.id(), g.code(), g.name(), g.side(), g.accountId(),
                        codes.get(g.accountId()), g.isDefault()))
                .toList();
        List<LedgerDtos.Group> business = s.businessGroups().stream()
                .map(g -> new LedgerDtos.Group(g.id(), g.code(), g.name(), g.isDefault(), null,
                        g.vatRule() == null ? "STANDARD" : g.vatRule(), g.exemptVatCode())).toList();
        List<LedgerDtos.Group> product = s.productGroups().stream()
                .map(g -> new LedgerDtos.Group(g.id(), g.code(), g.name(), g.isDefault(), g.vatCode(), null, null)).toList();
        List<LedgerDtos.GeneralSetupRow> general = s.general().stream()
                .map(r -> new LedgerDtos.GeneralSetupRow(r.id(), r.businessGroupId(), r.productGroupId(),
                        r.salesAccountId(), codes.get(r.salesAccountId()), r.purchaseAccountId(),
                        codes.get(r.purchaseAccountId())))
                .toList();
        List<LedgerDtos.VatSetupRow> vat = s.vat().stream()
                .map(r -> new LedgerDtos.VatSetupRow(r.id(), r.code(), r.name(), r.rate(), r.collectedAccountId(),
                        codes.get(r.collectedAccountId()), r.recoverableAccountId(), codes.get(r.recoverableAccountId()),
                        r.exempt(), r.mention()))
                .toList();
        List<LedgerDtos.BankAccountRow> banks = jdbc.query("""
                SELECT b.id, b.name, b.ledger_account_id FROM bank_account b
                WHERE b.organization_id = ? AND b.deleted_at IS NULL ORDER BY b.created_at
                """, (rs, i) -> {
            UUID accountId = rs.getObject(3, UUID.class);
            return new LedgerDtos.BankAccountRow(rs.getObject(1, UUID.class), rs.getString(2), accountId,
                    accountId == null ? null : codes.get(accountId));
        }, orgId);
        List<LedgerDtos.CategoryRow> bankCategories = new ArrayList<>();
        s.bankCategories().forEach((c, a) -> bankCategories.add(new LedgerDtos.CategoryRow(c, a, codes.get(a))));
        bankCategories.sort((x, y) -> Integer.compare(order(BankCategories.ALL, x.category()),
                order(BankCategories.ALL, y.category())));
        List<LedgerDtos.CategoryRow> expenseCategories = new ArrayList<>();
        s.expenseCategories().forEach((c, a) -> expenseCategories.add(new LedgerDtos.CategoryRow(c, a, codes.get(a))));
        expenseCategories.sort((x, y) -> Integer.compare(order(com.bento.crm.expense.model.Expense.CATEGORIES, x.category()),
                order(com.bento.crm.expense.model.Expense.CATEGORIES, y.category())));
        return new LedgerDtos.PostingSetup(partnerGroups, business, product, general, vat, banks, s.cashAccountId(),
                codes.get(s.cashAccountId()), bankCategories, expenseCategories, s.startDate(), s.bankPostingMode());
    }

    private static int order(List<String> known, String value) {
        int i = known.indexOf(value);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** An active account of the organization: 404 when unknown, 400 when inactive. */
    private UUID account(UUID orgId, UUID accountId, String what) {
        if (accountId == null) {
            throw new IllegalArgumentException("The " + what + " account is required");
        }
        LedgerAccount a = accountRepository.findByOrganizationIdAndId(orgId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!a.isActive()) {
            throw new IllegalArgumentException("Account " + a.getCode() + " is inactive");
        }
        return a.getId();
    }

    private static String code(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!c.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("The code is 1 to 20 letters, digits, '-' or '_'");
        }
        return c;
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The name is required");
        }
        String n = name.trim();
        if (n.length() > 160) {
            throw new IllegalArgumentException("The name is at most 160 characters");
        }
        return n;
    }

    // Partner groups

    @Transactional
    public LedgerDtos.PostingSetup createPartnerGroup(LedgerDtos.PartnerGroupCreate r) {
        UUID orgId = org();
        String side = r.side() == null ? "" : r.side().trim().toUpperCase(Locale.ROOT);
        if (!side.equals(CUSTOMER) && !side.equals(SUPPLIER)) {
            throw new IllegalArgumentException("The side is CUSTOMER or SUPPLIER");
        }
        String code = code(r.code());
        UUID accountId = account(orgId, r.accountId(), "partner");
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_partner_group WHERE organization_id = ?"
                + " AND side = ? AND code = ?)", Boolean.class, orgId, side, code);
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalArgumentException("A " + side.toLowerCase(Locale.ROOT) + " group with code " + code + " already exists");
        }
        jdbc.update("INSERT INTO ledger_partner_group (organization_id, code, name, side, account_id, is_default)"
                + " VALUES (?, ?, ?, ?, ?, FALSE)", orgId, code, name(r.name()), side, accountId);
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updatePartnerGroup(UUID id, LedgerDtos.PartnerGroupUpdate r) {
        UUID orgId = org();
        Map<String, Object> g = row(orgId, "ledger_partner_group", id, "Partner group not found");
        if (r.name() != null) {
            jdbc.update("UPDATE ledger_partner_group SET name = ?, updated_at = now() WHERE id = ?", name(r.name()), id);
        }
        if (r.accountId() != null) {
            jdbc.update("UPDATE ledger_partner_group SET account_id = ?, updated_at = now() WHERE id = ?",
                    account(orgId, r.accountId(), "partner"), id);
        }
        if (r.isDefault() != null) {
            boolean isDefault = (Boolean) g.get("is_default");
            if (r.isDefault() && !isDefault) {
                jdbc.update("UPDATE ledger_partner_group SET is_default = FALSE, updated_at = now()"
                        + " WHERE organization_id = ? AND side = ? AND is_default", orgId, g.get("side"));
                jdbc.update("UPDATE ledger_partner_group SET is_default = TRUE, updated_at = now() WHERE id = ?", id);
            } else if (!r.isDefault() && isDefault) {
                throw new IllegalArgumentException("Make another group the default instead");
            }
        }
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup deletePartnerGroup(UUID id) {
        UUID orgId = org();
        Map<String, Object> g = row(orgId, "ledger_partner_group", id, "Partner group not found");
        if ((Boolean) g.get("is_default")) {
            throw new IllegalArgumentException("The default group cannot be deleted");
        }
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM partner WHERE organization_id = ?"
                + " AND (customer_group_id = ? OR supplier_group_id = ?))", Boolean.class, orgId, id, id);
        if (Boolean.TRUE.equals(used)) {
            throw new IllegalArgumentException("This group is assigned to partners: move them to another group first");
        }
        jdbc.update("DELETE FROM ledger_partner_group WHERE organization_id = ? AND id = ?", orgId, id);
        return view(orgId);
    }

    // Business and product groups

    @Transactional
    public LedgerDtos.PostingSetup createBusinessGroup(LedgerDtos.GroupCreate r) {
        return createGroup(r, true);
    }

    @Transactional
    public LedgerDtos.PostingSetup createProductGroup(LedgerDtos.GroupCreate r) {
        return createGroup(r, false);
    }

    /** A new group gets its general-setup rows, copied from the default group's rows. */
    private LedgerDtos.PostingSetup createGroup(LedgerDtos.GroupCreate r, boolean business) {
        UUID orgId = org();
        String table = business ? "ledger_business_group" : "ledger_product_group";
        String code = code(r.code());
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table
                + " WHERE organization_id = ? AND code = ?)", Boolean.class, orgId, code);
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalArgumentException("A " + (business ? "business" : "product") + " group with code "
                    + code + " already exists");
        }
        UUID id = jdbc.queryForObject("INSERT INTO " + table + " (organization_id, code, name, is_default)"
                + " VALUES (?, ?, ?, FALSE) RETURNING id", UUID.class, orgId, code, name(r.name()));
        String mine = business ? "business_group_id" : "product_group_id";
        String other = business ? "product_group_id" : "business_group_id";
        jdbc.update("""
                INSERT INTO ledger_general_setup (organization_id, %1$s, %2$s, sales_account_id, purchase_account_id)
                SELECT g.organization_id, ?, g.%2$s, g.sales_account_id, g.purchase_account_id
                FROM ledger_general_setup g JOIN %3$s d ON d.id = g.%1$s AND d.is_default
                WHERE g.organization_id = ?
                ON CONFLICT DO NOTHING
                """.formatted(mine, other, table), id, orgId);
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateBusinessGroup(UUID id, LedgerDtos.GroupUpdate r) {
        UUID orgId = org();
        row(orgId, "ledger_business_group", id, "Business group not found");
        String rule = r.getVatRule() == null ? null : r.getVatRule().trim().toUpperCase(Locale.ROOT);
        if (rule != null && !rule.equals("STANDARD") && !rule.equals("EXEMPT")) {
            throw new IllegalArgumentException("The VAT rule is STANDARD or EXEMPT");
        }
        if (r.isExemptVatCodePresent()) {
            String code = vatCode(orgId, r.getExemptVatCode());
            jdbc.update("UPDATE ledger_business_group SET exempt_vat_code = ?, updated_at = now() WHERE id = ?", code, id);
        }
        if (rule != null) {
            jdbc.update("UPDATE ledger_business_group SET vat_rule = ?, updated_at = now() WHERE id = ?", rule, id);
        }
        String effectiveRule = jdbc.queryForObject("SELECT vat_rule FROM ledger_business_group WHERE id = ?", String.class, id);
        String exemptCode = jdbc.queryForObject("SELECT exempt_vat_code FROM ledger_business_group WHERE id = ?", String.class, id);
        if ("EXEMPT".equals(effectiveRule) && exemptCode == null) {
            throw new IllegalArgumentException("An exempt business group needs the VAT code its documents use (exemptVatCode)");
        }
        return updateGroup(id, r, "ledger_business_group", "Business group not found");
    }

    @Transactional
    public LedgerDtos.PostingSetup updateProductGroup(UUID id, LedgerDtos.GroupUpdate r) {
        UUID orgId = org();
        row(orgId, "ledger_product_group", id, "Product group not found");
        if (r.isVatCodePresent()) {
            jdbc.update("UPDATE ledger_product_group SET vat_code = ?, updated_at = now() WHERE id = ?",
                    vatCode(orgId, r.getVatCode()), id);
        }
        return updateGroup(id, r, "ledger_product_group", "Product group not found");
    }

    /** A VAT code of the organization's VAT setup (upper-cased), or null for blank: 400 when unknown. */
    public String vatCode(UUID orgId, String code) {
        if (code == null || code.isBlank()) return null;
        setup.ensure(orgId);
        String c = code.trim().toUpperCase(Locale.ROOT);
        Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_vat_setup WHERE organization_id = ? AND code = ?)",
                Boolean.class, orgId, c);
        if (!Boolean.TRUE.equals(known)) {
            throw new IllegalArgumentException("Unknown VAT code: " + code);
        }
        return c;
    }

    private LedgerDtos.PostingSetup updateGroup(UUID id, LedgerDtos.GroupUpdate r, String table, String notFound) {
        UUID orgId = org();
        Map<String, Object> g = row(orgId, table, id, notFound);
        if (r.name() != null) {
            jdbc.update("UPDATE " + table + " SET name = ?, updated_at = now() WHERE id = ?", name(r.name()), id);
        }
        if (r.isDefault() != null) {
            boolean isDefault = (Boolean) g.get("is_default");
            if (r.isDefault() && !isDefault) {
                jdbc.update("UPDATE " + table + " SET is_default = FALSE, updated_at = now() WHERE organization_id = ?"
                        + " AND is_default", orgId);
                jdbc.update("UPDATE " + table + " SET is_default = TRUE, updated_at = now() WHERE id = ?", id);
            } else if (!r.isDefault() && isDefault) {
                throw new IllegalArgumentException("Make another group the default instead");
            }
        }
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup deleteBusinessGroup(UUID id) {
        UUID orgId = org();
        Map<String, Object> g = row(orgId, "ledger_business_group", id, "Business group not found");
        if ((Boolean) g.get("is_default")) {
            throw new IllegalArgumentException("The default group cannot be deleted");
        }
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM partner WHERE organization_id = ?"
                + " AND business_group_id = ?)", Boolean.class, orgId, id);
        if (Boolean.TRUE.equals(used)) {
            throw new IllegalArgumentException("This group is assigned to partners: move them to another group first");
        }
        jdbc.update("DELETE FROM ledger_business_group WHERE organization_id = ? AND id = ?", orgId, id);
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup deleteProductGroup(UUID id) {
        UUID orgId = org();
        Map<String, Object> g = row(orgId, "ledger_product_group", id, "Product group not found");
        if ((Boolean) g.get("is_default")) {
            throw new IllegalArgumentException("The default group cannot be deleted");
        }
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM product WHERE organization_id = ?"
                + " AND product_group_id = ?)", Boolean.class, orgId, id);
        if (Boolean.TRUE.equals(used)) {
            throw new IllegalArgumentException("This group is assigned to products: move them to another group first");
        }
        jdbc.update("DELETE FROM ledger_product_group WHERE organization_id = ? AND id = ?", orgId, id);
        return view(orgId);
    }

    private Map<String, Object> row(UUID orgId, String table, UUID id, String notFound) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table
                + " WHERE organization_id = ? AND id = ? FOR UPDATE", orgId, id);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(notFound);
        }
        return rows.get(0);
    }

    // General, VAT, bank, cash and category accounts

    @Transactional
    public LedgerDtos.PostingSetup updateGeneral(UUID id, LedgerDtos.GeneralSetupUpdate r) {
        UUID orgId = org();
        row(orgId, "ledger_general_setup", id, "General setup row not found");
        if (r.salesAccountId() == null && r.purchaseAccountId() == null) {
            throw new IllegalArgumentException("Give the sales or the purchase account");
        }
        if (r.salesAccountId() != null) {
            jdbc.update("UPDATE ledger_general_setup SET sales_account_id = ?, updated_at = now() WHERE id = ?",
                    account(orgId, r.salesAccountId(), "sales"), id);
        }
        if (r.purchaseAccountId() != null) {
            jdbc.update("UPDATE ledger_general_setup SET purchase_account_id = ?, updated_at = now() WHERE id = ?",
                    account(orgId, r.purchaseAccountId(), "purchase"), id);
        }
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateVat(UUID id, LedgerDtos.VatSetupUpdate r) {
        UUID orgId = org();
        row(orgId, "ledger_vat_setup", id, "VAT setup row not found");
        if (r.collectedAccountId() == null && r.recoverableAccountId() == null && r.mention() == null) {
            throw new IllegalArgumentException("Give the collected or the recoverable VAT account, or the mention");
        }
        if (r.mention() != null) {
            String mention = r.mention().trim();
            jdbc.update("UPDATE ledger_vat_setup SET mention = ?, updated_at = now() WHERE id = ?",
                    mention.isEmpty() ? null : mention, id);
        }
        if (r.collectedAccountId() != null) {
            jdbc.update("UPDATE ledger_vat_setup SET collected_account_id = ?, updated_at = now() WHERE id = ?",
                    account(orgId, r.collectedAccountId(), "collected VAT"), id);
        }
        if (r.recoverableAccountId() != null) {
            jdbc.update("UPDATE ledger_vat_setup SET recoverable_account_id = ?, updated_at = now() WHERE id = ?",
                    account(orgId, r.recoverableAccountId(), "recoverable VAT"), id);
        }
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateBank(UUID bankAccountId, LedgerDtos.AccountAssignment r) {
        UUID orgId = org();
        UUID accountId = account(orgId, r.accountId(), "bank");
        int n = jdbc.update("UPDATE bank_account SET ledger_account_id = ? WHERE organization_id = ? AND id = ?"
                + " AND deleted_at IS NULL", accountId, orgId, bankAccountId);
        if (n == 0) {
            throw new ResourceNotFoundException("Bank account not found");
        }
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateCash(LedgerDtos.AccountAssignment r) {
        UUID orgId = org();
        jdbc.update("UPDATE ledger_settings SET cash_account_id = ?, updated_at = now() WHERE organization_id = ?",
                account(orgId, r.accountId(), "cash"), orgId);
        return view(orgId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateBankCategory(String category, LedgerDtos.AccountAssignment r) {
        UUID orgId = org();
        String c = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!BankCategories.ALL.contains(c)) {
            throw new IllegalArgumentException("Unknown bank category: " + category);
        }
        upsertCategory(orgId, "BANK", c, account(orgId, r.accountId(), "category"));
        return view(orgId);
    }

    /** Expense categories are free text: any category can get its own account. */
    @Transactional
    public LedgerDtos.PostingSetup updateExpenseCategory(String category, LedgerDtos.AccountAssignment r) {
        UUID orgId = org();
        String c = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (c.isEmpty() || c.length() > 60) {
            throw new IllegalArgumentException("The category is 1 to 60 characters");
        }
        upsertCategory(orgId, "EXPENSE", c, account(orgId, r.accountId(), "category"));
        return view(orgId);
    }

    private void upsertCategory(UUID orgId, String kind, String category, UUID accountId) {
        jdbc.update("""
                INSERT INTO ledger_category_account (organization_id, kind, category, account_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (organization_id, kind, category) DO UPDATE SET account_id = EXCLUDED.account_id, updated_at = now()
                """, orgId, kind, category, accountId);
    }

    @Transactional
    public LedgerDtos.PostingSetup updateStartDate(LedgerDtos.StartDateUpdate r) {
        UUID orgId = org();
        if (r.startDate() == null) {
            throw new IllegalArgumentException("The start date is required");
        }
        jdbc.queryForList("SELECT start_date FROM ledger_settings WHERE organization_id = ? FOR UPDATE", orgId);
        Boolean posted = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_entry WHERE organization_id = ?"
                + " AND source_type IN ('INVOICE', 'BILL', 'PAYMENT', 'EXPENSE', 'BANK_LINE', 'ADVANCE', 'CREDIT_NOTE', 'ADVANCE_APPLY') AND deleted_at IS NULL)",
                Boolean.class, orgId);
        LocalDate current = jdbc.queryForObject("SELECT start_date FROM ledger_settings WHERE organization_id = ?",
                LocalDate.class, orgId);
        if (Boolean.TRUE.equals(posted) && !Objects.equals(current, r.startDate())) {
            throw new IllegalArgumentException("Re-post the history to change the start date");
        }
        jdbc.update("UPDATE ledger_settings SET start_date = ?, updated_at = now() WHERE organization_id = ?",
                r.startDate(), orgId);
        return view(orgId);
    }

    /** Stores the bank posting mode; the caller re-posts what the change affects. */
    @Transactional
    public void setBankPostingMode(UUID orgId, String mode) {
        setup.ensure(orgId);
        jdbc.update("UPDATE ledger_settings SET bank_posting_mode = ?, updated_at = now() WHERE organization_id = ?", mode, orgId);
    }

    /** The current setup of the organization in context. */
    @Transactional
    public LedgerDtos.PostingSetup current() {
        return view(org());
    }

    // Assignments on partners and products

    /** Checks a partner's group ids before they are saved: right organization, right side. */
    public void checkPartnerGroups(UUID orgId, UUID customerGroupId, UUID supplierGroupId, UUID businessGroupId) {
        if (customerGroupId == null && supplierGroupId == null && businessGroupId == null) {
            return;
        }
        setup.ensure(orgId);
        checkPartnerGroup(orgId, customerGroupId, CUSTOMER);
        checkPartnerGroup(orgId, supplierGroupId, SUPPLIER);
        if (businessGroupId != null && !exists(orgId, "ledger_business_group", businessGroupId)) {
            throw new IllegalArgumentException("Unknown business posting group");
        }
    }

    private void checkPartnerGroup(UUID orgId, UUID id, String side) {
        if (id == null) return;
        List<String> sides = jdbc.queryForList("SELECT side FROM ledger_partner_group WHERE organization_id = ? AND id = ?",
                String.class, orgId, id);
        if (sides.isEmpty()) {
            throw new IllegalArgumentException("Unknown " + side.toLowerCase(Locale.ROOT) + " posting group");
        }
        if (!sides.get(0).equals(side)) {
            throw new IllegalArgumentException("This posting group is for " + sides.get(0).toLowerCase(Locale.ROOT)
                    + "s, not " + side.toLowerCase(Locale.ROOT) + "s");
        }
    }

    public void checkProductGroup(UUID orgId, UUID productGroupId) {
        if (productGroupId == null) return;
        setup.ensure(orgId);
        if (!exists(orgId, "ledger_product_group", productGroupId)) {
            throw new IllegalArgumentException("Unknown product posting group");
        }
    }

    private boolean exists(UUID orgId, String table, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table
                + " WHERE organization_id = ? AND id = ?)", Boolean.class, orgId, id));
    }
}
