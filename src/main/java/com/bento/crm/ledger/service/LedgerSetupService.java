package com.bento.crm.ledger.service;

import com.bento.crm.ledger.model.LedgerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeds an organization's PCGE chart of accounts and its journals on its first ledger call.
 *
 * <p>Idempotent and safe under concurrent first calls: every row is inserted with
 * {@code ON CONFLICT DO NOTHING} against the partial unique index on (organization_id, code), so
 * a second concurrent caller waits for the first to commit and then inserts nothing. Organizations
 * already seeded are remembered (after commit) so later calls cost nothing.
 */
@Service
@RequiredArgsConstructor
public class LedgerSetupService {

    record Seed(String code, String name) {}

    record JournalSeed(String code, String name, String kind) {}

    static final List<Seed> ACCOUNTS = List.of(
            new Seed("1111", "Capital social"),
            new Seed("1161", "Report à nouveau (solde créditeur)"),
            new Seed("1169", "Report à nouveau (solde débiteur)"),
            new Seed("1191", "Résultat net de l'exercice (bénéficiaire)"),
            new Seed("1199", "Résultat net de l'exercice (déficitaire)"),
            new Seed("1481", "Emprunts auprès des établissements de crédit"),
            new Seed("3111", "Marchandises"),
            new Seed("3421", "Clients"),
            new Seed("3453", "Acomptes sur impôts sur les résultats"),
            new Seed("3481", "Créances sur les entreprises du groupe et associés"),
            new Seed("34552", "État, TVA récupérable sur charges"),
            new Seed("4411", "Fournisseurs"),
            new Seed("4417", "Fournisseurs, factures non parvenues"),
            new Seed("4455", "État, TVA facturée"),
            new Seed("44551", "État, TVA facturée 20 %"),
            new Seed("44552", "État, TVA facturée 14 %"),
            new Seed("44553", "État, TVA facturée 10 %"),
            new Seed("44554", "État, TVA facturée 7 %"),
            new Seed("4481", "Dettes sur les entreprises du groupe et associés"),
            new Seed("4456", "État, TVA due"),
            new Seed("4497", "Comptes d'attente"),
            new Seed("5115", "Virements de fonds"),
            new Seed("5141", "Banques"),
            new Seed("5161", "Caisse"),
            new Seed("6111", "Achats de marchandises"),
            new Seed("6114", "Variation de stocks de marchandises"),
            new Seed("6125", "Achats non stockés de matières et fournitures"),
            new Seed("6131", "Locations et charges locatives"),
            new Seed("6136", "Rémunérations d'intermédiaires et honoraires"),
            new Seed("6142", "Transports"),
            new Seed("6143", "Déplacements, missions et réceptions"),
            new Seed("6144", "Publicité, publications et relations publiques"),
            new Seed("6145", "Frais postaux et frais de télécommunications"),
            new Seed("6147", "Services bancaires"),
            new Seed("6161", "Impôts et taxes directs"),
            new Seed("6171", "Rémunérations du personnel"),
            new Seed("6174", "Charges sociales"),
            new Seed("6311", "Intérêts des emprunts et dettes"),
            new Seed("7111", "Ventes de marchandises au Maroc"),
            new Seed("7121", "Ventes de marchandises à l'étranger"),
            new Seed("7124", "Ventes de services produits au Maroc"),
            new Seed("7127", "Ventes de services produits à l'étranger"),
            new Seed("7381", "Intérêts et produits assimilés")
    );

    static final List<JournalSeed> JOURNALS = List.of(
            new JournalSeed("VT", "Ventes", "SALES"),
            new JournalSeed("AC", "Achats", "PURCHASES"),
            new JournalSeed("BQ", "Banque", "BANK"),
            new JournalSeed("CA", "Caisse", "CASH"),
            new JournalSeed("OD", "Opérations diverses", "MISC"),
            new JournalSeed("AN", "À-nouveaux", "OPENING")
    );

    record GroupSeed(String code, String name, String side, String accountCode, boolean isDefault) {}

    record VatSeed(String code, String name, String rate, String collected, String recoverable, boolean exempt) {}

    static final List<GroupSeed> PARTNER_GROUPS = List.of(
            new GroupSeed("LOCAL", "Clients locaux", "CUSTOMER", "3421", true),
            new GroupSeed("GROUP", "Sociétés du groupe", "CUSTOMER", "3481", false),
            new GroupSeed("LOCAL", "Fournisseurs locaux", "SUPPLIER", "4411", true),
            new GroupSeed("GROUP", "Sociétés du groupe", "SUPPLIER", "4481", false));

    static final List<Seed> BUSINESS_GROUPS = List.of(new Seed("MAROC", "Maroc"), new Seed("EXPORT", "Export"));
    static final String DEFAULT_BUSINESS_GROUP = "MAROC";

    static final List<Seed> PRODUCT_GROUPS = List.of(new Seed("GOODS", "Marchandises"), new Seed("SERVICES", "Services"));
    static final String DEFAULT_PRODUCT_GROUP = "SERVICES";

    /** business group, product group, sales account, purchase account. */
    static final List<String[]> GENERAL_SETUP = List.of(
            new String[]{"MAROC", "GOODS", "7111", "6111"},
            new String[]{"MAROC", "SERVICES", "7124", "6125"},
            new String[]{"EXPORT", "GOODS", "7121", "6111"},
            new String[]{"EXPORT", "SERVICES", "7127", "6125"});

    static final List<VatSeed> VAT = List.of(
            new VatSeed("TVA20", "TVA 20 %", "20", "44551", "34552", false),
            new VatSeed("TVA14", "TVA 14 %", "14", "44552", "34552", false),
            new VatSeed("TVA10", "TVA 10 %", "10", "44553", "34552", false),
            new VatSeed("TVA7", "TVA 7 %", "7", "44554", "34552", false),
            new VatSeed("TVA0", "TVA 0 %", "0", "4455", "34552", false),
            new VatSeed("EXONERE", "Exonéré", "0", "4455", "34552", true),
            new VatSeed("EXPORT", "Export (exonéré)", "0", "4455", "34552", true));

    /** Bank line category -> account. TAXES lines whose label mentions TVA go to 4456 instead. */
    static final Map<String, String> BANK_CATEGORIES = orderedMap(
            "SALES", "4497", "SUPPLIERS", "4497", "SALARIES", "6171", "SOCIAL_CHARGES", "6174", "TAXES", "6161",
            "RENT", "6131", "UTILITIES", "6125", "TELECOM", "6145", "FUEL", "6125", "TRAVEL", "6143",
            "MEALS", "6143", "OFFICE", "6125", "SOFTWARE", "6125", "MARKETING", "6144", "FEES", "6136",
            "BANK_FEES", "6147", "LOAN", "1481", "TRANSFER", "5115", "CASH", "5161", "OTHER", "4497");

    /** Expense category -> account. */
    static final Map<String, String> EXPENSE_CATEGORIES = orderedMap(
            "TRAVEL", "6143", "FUEL", "6125", "MEALS", "6143", "OFFICE", "6125", "RENT", "6131",
            "UTILITIES", "6125", "TELECOM", "6145", "SOFTWARE", "6125", "MARKETING", "6144", "FEES", "6136",
            "SALARIES", "6171", "TAXES", "6161", "OTHER", "6125");

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private final JdbcTemplate jdbc;
    private final Set<UUID> seeded = ConcurrentHashMap.newKeySet();

    /** Makes sure the organization has its accounts and journals. Cheap once done. */
    @Transactional
    public void ensure(UUID orgId) {
        if (seeded.contains(orgId)) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO ledger_account (organization_id, code, name, account_class, kind, is_system)
                VALUES (?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (organization_id, code) WHERE deleted_at IS NULL DO NOTHING
                """, ACCOUNTS, ACCOUNTS.size(), (ps, s) -> {
            ps.setObject(1, orgId);
            ps.setString(2, s.code());
            ps.setString(3, s.name());
            ps.setShort(4, (short) (s.code().charAt(0) - '0'));
            ps.setString(5, LedgerAccount.kindOf(s.code()).name());
        });
        jdbc.batchUpdate("""
                INSERT INTO ledger_journal (organization_id, code, name, kind)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (organization_id, code) WHERE deleted_at IS NULL DO NOTHING
                """, JOURNALS, JOURNALS.size(), (ps, j) -> {
            ps.setObject(1, orgId);
            ps.setString(2, j.code());
            ps.setString(3, j.name());
            ps.setString(4, j.kind());
        });
        seedPostingSetup(orgId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seeded.add(orgId);
                }
            });
        } else {
            seeded.add(orgId);
        }
    }

    private static final String ACCOUNT = "(SELECT id FROM ledger_account WHERE organization_id = ? AND code = ? AND deleted_at IS NULL)";

    /**
     * Seeds the posting groups, the general and VAT setup, the category accounts and the settings,
     * once per organization: the settings row is inserted first and only the call that inserted it
     * seeds the rest, so a group a person deleted is never brought back. A concurrent first call
     * waits on that row and then finds it there.
     */
    private void seedPostingSetup(UUID orgId) {
        LocalDate start = defaultStartDate(orgId);
        int inserted = jdbc.update("INSERT INTO ledger_settings (organization_id, start_date, cash_account_id) VALUES (?, ?, "
                + ACCOUNT + ") ON CONFLICT (organization_id) DO NOTHING", orgId, start, orgId, "5161");
        if (inserted == 0) {
            return;
        }
        for (GroupSeed g : PARTNER_GROUPS) {
            jdbc.update("INSERT INTO ledger_partner_group (organization_id, code, name, side, account_id, is_default) VALUES (?, ?, ?, ?, "
                    + ACCOUNT + ", ?) ON CONFLICT DO NOTHING", orgId, g.code(), g.name(), g.side(), orgId, g.accountCode(), g.isDefault());
        }
        for (Seed g : BUSINESS_GROUPS) {
            jdbc.update("INSERT INTO ledger_business_group (organization_id, code, name, is_default) VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING", orgId, g.code(), g.name(), g.code().equals(DEFAULT_BUSINESS_GROUP));
        }
        for (Seed g : PRODUCT_GROUPS) {
            jdbc.update("INSERT INTO ledger_product_group (organization_id, code, name, is_default) VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING", orgId, g.code(), g.name(), g.code().equals(DEFAULT_PRODUCT_GROUP));
        }
        for (String[] r : GENERAL_SETUP) {
            jdbc.update("""
                    INSERT INTO ledger_general_setup (organization_id, business_group_id, product_group_id, sales_account_id, purchase_account_id)
                    VALUES (?, (SELECT id FROM ledger_business_group WHERE organization_id = ? AND code = ?),
                            (SELECT id FROM ledger_product_group WHERE organization_id = ? AND code = ?), %s, %s)
                    ON CONFLICT DO NOTHING
                    """.formatted(ACCOUNT, ACCOUNT), orgId, orgId, r[0], orgId, r[1], orgId, r[2], orgId, r[3]);
        }
        for (VatSeed v : VAT) {
            jdbc.update("INSERT INTO ledger_vat_setup (organization_id, code, name, rate, collected_account_id, recoverable_account_id, exempt)"
                    + " VALUES (?, ?, ?, ?, " + ACCOUNT + ", " + ACCOUNT + ", ?) ON CONFLICT DO NOTHING",
                    orgId, v.code(), v.name(), new java.math.BigDecimal(v.rate()), orgId, v.collected(), orgId, v.recoverable(),
                    v.exempt());
        }
        seedCategories(orgId, "BANK", BANK_CATEGORIES);
        seedCategories(orgId, "EXPENSE", EXPENSE_CATEGORIES);
    }

    private void seedCategories(UUID orgId, String kind, Map<String, String> categories) {
        categories.forEach((category, code) -> jdbc.update(
                "INSERT INTO ledger_category_account (organization_id, kind, category, account_id) VALUES (?, ?, ?, "
                        + ACCOUNT + ") ON CONFLICT DO NOTHING", orgId, kind, category, orgId, code));
    }

    /** The first day of the organization's current fiscal year. */
    private LocalDate defaultStartDate(UUID orgId) {
        List<Integer> months = jdbc.queryForList("SELECT fiscal_year_start_month FROM organization WHERE id = ?",
                Integer.class, orgId);
        Integer month = months.isEmpty() ? null : months.get(0);
        int m = month == null || month < 1 || month > 12 ? 1 : month;
        LocalDate today = LocalDate.now();
        LocalDate candidate = LocalDate.of(today.getYear(), m, 1);
        return candidate.isAfter(today) ? candidate.minusYears(1) : candidate;
    }
}
