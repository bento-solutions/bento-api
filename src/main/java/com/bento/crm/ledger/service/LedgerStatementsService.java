package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.ledger.dto.LedgerStatementDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The financial statements in the Moroccan CGNC format (simplified): the bilan (balance sheet)
 * as of a date and the CPC (income statement) over a period, from the posted entry lines.
 *
 * <p>The bilan reads classes 1 to 5 from the last carry-forward on. The year-end result entry of
 * the reported year is left out, and the result is shown on its own row from classes 6 to 8, so
 * a closed year shows the same figures as an open one and nothing is counted twice. The results
 * of earlier years that were never closed go to "Report à nouveau" (116). A carried 1191/1199 (an
 * earlier closed year's result) shows as "Résultats nets en instance d'affectation" (118).
 *
 * <p>The CPC leaves the YEAR_END entries out, so a closed year still shows its result.
 */
@Service
@RequiredArgsConstructor
public class LedgerStatementsService {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final LedgerSetupService setup;
    private final LedgerPostingService posting;
    private final LedgerClosingService closing;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    // Static tables: the official CGNC rubric names, in French, never translated.

    private record SectionDef(String key, String label, List<String> prefixes) {}

    private static final List<SectionDef> ACTIF = List.of(
            new SectionDef("A1", "Immobilisations en non-valeurs", List.of("21")),
            new SectionDef("A2", "Immobilisations incorporelles", List.of("22")),
            new SectionDef("A3", "Immobilisations corporelles", List.of("23")),
            new SectionDef("A4", "Immobilisations financières", List.of("24", "25")),
            new SectionDef("A5", "Écarts de conversion - actif", List.of("27")),
            new SectionDef("A6", "Stocks", List.of("31")),
            new SectionDef("A7", "Créances de l'actif circulant", List.of("34")),
            new SectionDef("A8", "Titres et valeurs de placement", List.of("35")),
            new SectionDef("A9", "Écarts de conversion - actif (éléments circulants)", List.of("37")),
            new SectionDef("A10", "Trésorerie - actif", List.of("51")),
            new SectionDef("A99", "Autres comptes d'actif", List.of()));

    private static final List<SectionDef> PASSIF = List.of(
            new SectionDef("P1", "Capitaux propres", List.of("11")),
            new SectionDef("P2", "Capitaux propres assimilés", List.of("13")),
            new SectionDef("P3", "Dettes de financement", List.of("14")),
            new SectionDef("P4", "Provisions durables pour risques et charges", List.of("15")),
            new SectionDef("P5", "Écarts de conversion - passif", List.of("17")),
            new SectionDef("P6", "Dettes du passif circulant", List.of("44")),
            new SectionDef("P7", "Autres provisions pour risques et charges", List.of("45")),
            new SectionDef("P8", "Écarts de conversion - passif (éléments circulants)", List.of("47")),
            new SectionDef("P9", "Trésorerie - passif", List.of("55")),
            new SectionDef("P99", "Autres comptes de passif", List.of()));

    static final String RESULT_CODE = "119";
    static final String RESULT_LABEL = "Résultat net de l'exercice";
    private static final String PENDING_CODE = "118";
    private static final String RAN_CODE = "116";

    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        String[][] t = {
                {"211", "Frais préliminaires"}, {"212", "Charges à répartir sur plusieurs exercices"},
                {"213", "Primes de remboursement des obligations"},
                {"221", "Immobilisation en recherche et développement"},
                {"222", "Brevets, marques, droits et valeurs similaires"}, {"223", "Fonds commercial"},
                {"228", "Autres immobilisations incorporelles"}, {"229", "Immobilisations incorporelles en cours"},
                {"231", "Terrains"}, {"232", "Constructions"}, {"233", "Installations techniques, matériel et outillage"},
                {"234", "Matériel de transport"}, {"235", "Mobilier, matériel de bureau et aménagements divers"},
                {"238", "Autres immobilisations corporelles"}, {"239", "Immobilisations corporelles en cours"},
                {"241", "Prêts immobilisés"}, {"248", "Autres créances financières"}, {"251", "Titres de participation"},
                {"258", "Autres titres immobilisés"}, {"271", "Diminution des créances immobilisées"},
                {"272", "Augmentation des dettes de financement"},
                {"311", "Marchandises"}, {"312", "Matières et fournitures consommables"}, {"313", "Produits en cours"},
                {"314", "Produits intermédiaires et produits résiduels"}, {"315", "Produits finis"},
                {"341", "Fournisseurs débiteurs, avances et acomptes"}, {"342", "Clients et comptes rattachés"},
                {"343", "Personnel"}, {"345", "État"}, {"346", "Comptes d'associés"}, {"348", "Autres débiteurs"},
                {"349", "Comptes de régularisation - actif"}, {"350", "Titres et valeurs de placement"},
                {"370", "Écarts de conversion - actif (éléments circulants)"},
                {"511", "Chèques et valeurs à encaisser"}, {"514", "Banques, TG et CCP"},
                {"516", "Caisses, régies d'avances et accréditifs"},
                {"111", "Capital social ou personnel"}, {"112", "Primes d'émission, de fusion et d'apport"},
                {"113", "Écarts de réévaluation"}, {"114", "Réserve légale"}, {"115", "Autres réserves"},
                {"116", "Report à nouveau"}, {"118", "Résultats nets en instance d'affectation"},
                {"119", RESULT_LABEL}, {"131", "Subventions d'investissement"}, {"135", "Provisions réglementées"},
                {"141", "Emprunts obligataires"}, {"148", "Autres dettes de financement"},
                {"151", "Provisions pour risques"}, {"155", "Provisions pour charges"},
                {"171", "Augmentation des créances immobilisées"}, {"172", "Diminution des dettes de financement"},
                {"441", "Fournisseurs et comptes rattachés"}, {"442", "Clients créditeurs, avances et acomptes"},
                {"443", "Personnel"}, {"444", "Organismes sociaux"}, {"445", "État"}, {"446", "Comptes d'associés"},
                {"448", "Autres créanciers"}, {"449", "Comptes de régularisation - passif"},
                {"450", "Autres provisions pour risques et charges"},
                {"470", "Écarts de conversion - passif (éléments circulants)"},
                {"552", "Crédits d'escompte"}, {"553", "Crédits de trésorerie"}, {"554", "Banques (soldes créditeurs)"},
                // CPC
                {"711", "Ventes de marchandises (en l'état)"}, {"712", "Ventes de biens et services produits"},
                {"713", "Variation de stocks de produits"},
                {"714", "Immobilisations produites par l'entreprise pour elle-même"},
                {"716", "Subventions d'exploitation"}, {"718", "Autres produits d'exploitation"},
                {"719", "Reprises d'exploitation : transferts de charges"},
                {"611", "Achats revendus de marchandises"}, {"612", "Achats consommés de matières et fournitures"},
                {"613", "Autres charges externes"}, {"616", "Impôts et taxes"}, {"617", "Charges de personnel"},
                {"618", "Autres charges d'exploitation"}, {"619", "Dotations d'exploitation"},
                {"732", "Produits des titres de participation et autres titres immobilisés"},
                {"733", "Gains de change"}, {"738", "Intérêts et autres produits financiers"},
                {"739", "Reprises financières : transferts de charges"},
                {"631", "Charges d'intérêts"}, {"633", "Pertes de change"}, {"638", "Autres charges financières"},
                {"639", "Dotations financières"},
                {"751", "Produits des cessions d'immobilisations"}, {"756", "Subventions d'équilibre"},
                {"757", "Reprises sur subventions d'investissement"}, {"758", "Autres produits non courants"},
                {"759", "Reprises non courantes : transferts de charges"},
                {"651", "Valeurs nettes d'amortissements des immobilisations cédées"},
                {"656", "Subventions accordées"}, {"658", "Autres charges non courantes"},
                {"659", "Dotations non courantes aux amortissements et aux provisions"},
                {"670", "Impôts sur les résultats"}, {"671", "Impôts sur les résultats"},
        };
        for (String[] r : t) LABELS.put(r[0], r[1]);
    }

    static String label(String code3, Map<String, String> fallbackNames) {
        String l = LABELS.get(code3);
        if (l != null) return l;
        l = fallbackNames.get(code3);
        return l != null ? l : "Comptes " + code3;
    }

    // Years

    record Year(int year, LocalDate start, LocalDate end) {}

    Year year(UUID orgId, int y) {
        LocalDate start = closing.yearStart(orgId, y);
        return new Year(y, start, start.plusYears(1).minusDays(1));
    }

    Year yearOf(UUID orgId, LocalDate date) {
        return year(orgId, closing.yearOf(orgId, date));
    }

    private boolean closed(UUID orgId, int y) {
        Boolean closed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_fiscal_year WHERE organization_id = ?"
                + " AND year = ? AND status = 'CLOSED')", Boolean.class, orgId, y);
        return Boolean.TRUE.equals(closed);
    }

    // Bilan

    /** One account's balance (debit minus credit) before the fiscal year start and within it, up to the date. */
    private record AccBal(String code, String name, int accountClass, BigDecimal before, BigDecimal during) {
        BigDecimal total() {
            return before.add(during);
        }
    }

    private List<AccBal> bilanBalances(UUID orgId, LocalDate asOf, LocalDate fyStart) {
        LocalDate floor = posting.carryForwardFloor(orgId, asOf);
        return jdbc.query("""
                SELECT a.code, a.name, a.account_class,
                       COALESCE(SUM(l.debit - l.credit) FILTER (WHERE e.entry_date < ?), 0),
                       COALESCE(SUM(l.debit - l.credit) FILTER (WHERE e.entry_date >= ?), 0)
                FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id AND a.organization_id = l.organization_id
                JOIN ledger_journal j ON j.id = e.journal_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                  AND NOT (COALESCE(e.source_type, '') = 'YEAR_END' AND e.entry_date >= ? AND j.kind <> 'OPENING')
                GROUP BY a.code, a.name, a.account_class
                ORDER BY a.code
                """, (rs, i) -> new AccBal(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5)), fyStart, fyStart, orgId, floor, asOf, fyStart);
    }

    /** A row being built: the current and prior figures. */
    private static final class RowAcc {
        final String code;
        String label;
        String from;
        String to;
        BigDecimal gross = ZERO;
        BigDecimal amort = ZERO;
        BigDecimal priorNet = ZERO;

        RowAcc(String code, String label, String from, String to) {
            this.code = code;
            this.label = label;
            this.from = from;
            this.to = to;
        }

        boolean empty() {
            return gross.signum() == 0 && amort.signum() == 0 && priorNet.signum() == 0;
        }
    }

    private static final class SideAcc {
        final List<SectionDef> defs;
        final Map<String, Map<String, RowAcc>> rows = new LinkedHashMap<>();

        SideAcc(List<SectionDef> defs) {
            this.defs = defs;
            for (SectionDef d : defs) rows.put(d.key(), new TreeMap<>());
        }

        String sectionOf(String code) {
            if (code.length() < 2) return null;
            String p2 = code.substring(0, 2);
            for (SectionDef d : defs) if (d.prefixes().contains(p2)) return d.key();
            return null;
        }

        RowAcc row(String section, String code3, String label, String from, String to) {
            return rows.get(section).computeIfAbsent(code3, k -> new RowAcc(code3, label, from, to));
        }
    }

    private static boolean isAmortProv(String code) {
        return code.startsWith("28") || code.startsWith("29") || code.startsWith("39") || code.startsWith("59");
    }

    private static String code3(String code) {
        return code.length() >= 3 ? code.substring(0, 3) : (code + "000").substring(0, 3);
    }

    /** The figures of one date: rows added into the side accumulators, the result and the warnings. */
    private record Snapshot(BigDecimal result) {}

    private Snapshot fill(UUID orgId, LocalDate asOf, SideAcc actif, SideAcc passif, boolean prior,
                          Map<String, String> names, List<BilanWarning> warnings) {
        Year fy = yearOf(orgId, asOf);
        List<AccBal> balances = bilanBalances(orgId, asOf, fy.start());
        BigDecimal result = ZERO;
        BigDecimal earlier = ZERO;
        for (AccBal b : balances) {
            if (b.accountClass() >= 6) {
                result = result.subtract(b.during());
                earlier = earlier.subtract(b.before());
            }
        }
        for (AccBal b : balances) {
            String c3 = code3(b.code());
            names.putIfAbsent(c3, b.name());
        }
        for (AccBal b : balances) {
            if (b.accountClass() >= 6) continue;
            BigDecimal dc = b.total();
            if (dc.signum() == 0) continue;
            String code = b.code();
            int cls = b.accountClass();
            if (isAmortProv(code)) {
                // 2834 -> 234, 3942 -> 342, 5914 -> 514: amortization and provisions against their asset.
                String eff = code.charAt(0) + code.substring(2);
                String section = actif.sectionOf(eff);
                if (section == null) section = "A99";
                String c3 = code3(eff);
                RowAcc r = actif.row(section, c3, label(c3, names), c3, c3);
                BigDecimal v = dc.negate();
                if (prior) r.priorNet = r.priorNet.subtract(v);
                else {
                    r.amort = r.amort.add(v);
                    if (v.signum() < 0) warnings.add(warning(b, v, "Debit balance on an amortization or provision account"));
                }
                continue;
            }
            String aSection = actif.sectionOf(code);
            String pSection = passif.sectionOf(code);
            boolean onActif = aSection != null || (pSection == null && (cls == 2 || cls == 3 || cls == 5));
            if (onActif) {
                String section = aSection != null ? aSection : "A99";
                String c3 = code3(code);
                RowAcc r = actif.row(section, c3, label(c3, names), c3, c3);
                if (prior) r.priorNet = r.priorNet.add(dc);
                else {
                    r.gross = r.gross.add(dc);
                    if (dc.signum() < 0) warnings.add(warning(b, dc, "Credit balance on an asset account"));
                }
                continue;
            }
            String section = pSection != null ? pSection : "P99";
            String c3 = code3(code);
            String from = c3;
            String to = c3;
            if (c3.equals(RESULT_CODE)) {
                // A carried 1191/1199: an earlier closed year's result, not yet allocated.
                c3 = PENDING_CODE;
                from = PENDING_CODE;
                to = RESULT_CODE;
            }
            RowAcc r = passif.row(section, c3, label(c3, names), from, to);
            BigDecimal v = dc.negate();
            if (prior) r.priorNet = r.priorNet.add(v);
            else {
                r.gross = r.gross.add(v);
                boolean equity = code.startsWith("116") || code.startsWith("118") || code.startsWith("119")
                        || code.startsWith("1119");
                if (v.signum() < 0 && !equity) warnings.add(warning(b, v, "Debit balance on a liability account"));
            }
        }
        if (earlier.signum() != 0) {
            RowAcc r = passif.row("P1", RAN_CODE, label(RAN_CODE, names), RAN_CODE, RAN_CODE);
            if (prior) r.priorNet = r.priorNet.add(earlier);
            else r.gross = r.gross.add(earlier);
        }
        return new Snapshot(result);
    }

    private static BilanWarning warning(AccBal b, BigDecimal balance, String message) {
        return new BilanWarning(b.code(), b.name(), money(balance), message);
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public Bilan bilan(Integer year, LocalDate date, boolean comparative) {
        UUID orgId = org();
        Year fy = date != null ? yearOf(orgId, date) : year(orgId, year != null ? year : closing.yearOf(orgId, LocalDate.now()));
        LocalDate asOf = date != null ? date : fy.end();
        LocalDate priorAsOf = comparative ? asOf.minusYears(1) : null;

        SideAcc actif = new SideAcc(ACTIF);
        SideAcc passif = new SideAcc(PASSIF);
        Map<String, String> names = new HashMap<>();
        List<BilanWarning> warnings = new ArrayList<>();
        BigDecimal result = fill(orgId, asOf, actif, passif, false, names, warnings).result();
        BigDecimal resultPrior = null;
        if (comparative) {
            resultPrior = fill(orgId, priorAsOf, actif, passif, true, names, new ArrayList<>()).result();
        }
        RowAcc res = passif.row("P1", RESULT_CODE, RESULT_LABEL, "6", "8");
        res.gross = res.gross.add(result);
        if (resultPrior != null) res.priorNet = res.priorNet.add(resultPrior);

        List<BilanSection> a = sections(actif, true);
        List<BilanSection> p = sections(passif, false);
        BigDecimal totalActif = a.stream().map(BilanSection::totalNet).reduce(ZERO, BigDecimal::add);
        BigDecimal totalPassif = p.stream().map(BilanSection::totalNet).reduce(ZERO, BigDecimal::add);
        BigDecimal totalActifPrior = comparative ? a.stream().map(BilanSection::totalNetPrior).reduce(ZERO, BigDecimal::add) : null;
        BigDecimal totalPassifPrior = comparative ? p.stream().map(BilanSection::totalNetPrior).reduce(ZERO, BigDecimal::add) : null;
        boolean balanced = totalActif.subtract(totalPassif).abs().compareTo(TOLERANCE) <= 0
                && (!comparative || totalActifPrior.subtract(totalPassifPrior).abs().compareTo(TOLERANCE) <= 0);
        warnings.sort((x, y) -> x.accountCode().compareTo(y.accountCode()));
        return new Bilan(fy.year(), closed(orgId, fy.year()), asOf, priorAsOf, a, p, totalActif, totalPassif,
                totalActifPrior, totalPassifPrior, money(result), resultPrior == null ? null : money(resultPrior),
                balanced, warnings);
    }

    private static List<BilanSection> sections(SideAcc side, boolean actif) {
        List<BilanSection> out = new ArrayList<>();
        for (SectionDef d : side.defs) {
            List<BilanRow> rows = new ArrayList<>();
            BigDecimal g = ZERO, am = ZERO, n = ZERO, np = ZERO;
            for (RowAcc r : side.rows.get(d.key()).values()) {
                boolean keep = !r.empty() || r.code.equals(RESULT_CODE);
                if (!keep) continue;
                BigDecimal gross = money(r.gross);
                BigDecimal amort = actif ? money(r.amort) : ZERO;
                BigDecimal net = gross.subtract(amort);
                BigDecimal prior = money(r.priorNet);
                rows.add(new BilanRow(r.code, r.label, r.from, r.to, gross, amort, net, prior));
                g = g.add(gross);
                am = am.add(amort);
                n = n.add(net);
                np = np.add(prior);
            }
            if (rows.isEmpty() && (d.key().endsWith("99"))) continue;
            out.add(new BilanSection(d.key(), d.label(), rows, g, am, n, np));
        }
        return out;
    }

    // CPC

    private record CpcDef(String key, String label, String type) {}

    private static final List<CpcDef> CPC = List.of(
            new CpcDef("I", "Produits d'exploitation", "PRODUCTS"),
            new CpcDef("II", "Charges d'exploitation", "CHARGES"),
            new CpcDef("III", "Résultat d'exploitation (I - II)", "RESULT"),
            new CpcDef("IV", "Produits financiers", "PRODUCTS"),
            new CpcDef("V", "Charges financières", "CHARGES"),
            new CpcDef("VI", "Résultat financier (IV - V)", "RESULT"),
            new CpcDef("VII", "Résultat courant (III + VI)", "RESULT"),
            new CpcDef("VIII", "Produits non courants", "PRODUCTS"),
            new CpcDef("IX", "Charges non courantes", "CHARGES"),
            new CpcDef("X", "Résultat non courant (VIII - IX)", "RESULT"),
            new CpcDef("XI", "Résultat avant impôts (VII + X)", "RESULT"),
            new CpcDef("XII", "Impôts sur les résultats", "CHARGES"),
            new CpcDef("XIII", "Résultat net (XI - XII)", "RESULT"));

    /** The CPC section of an income or expense account (classes 6, 7 and 8). */
    static String cpcSection(String code, int accountClass) {
        int d = code.length() > 1 ? code.charAt(1) - '0' : 0;
        if (accountClass == 7) {
            return d <= 2 ? "I" : d <= 4 ? "IV" : "VIII";
        }
        if (accountClass == 6) {
            if (d == 7) return "XII";
            return d <= 2 ? "II" : d <= 4 ? "V" : "IX";
        }
        return "VIII";
    }

    private Map<String, BigDecimal> cpcBalances(UUID orgId, LocalDate from, LocalDate to, Map<String, String> names,
                                                Map<String, String> classOf) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.code, a.name, a.account_class, SUM(l.debit - l.credit)
                FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id AND a.organization_id = l.organization_id
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                  AND a.account_class >= 6 AND COALESCE(e.source_type, '') <> 'YEAR_END'
                GROUP BY a.code, a.name, a.account_class
                ORDER BY a.code
                """, rs -> {
            String code = rs.getString(1);
            names.putIfAbsent(code3(code), rs.getString(2));
            classOf.put(code, String.valueOf(rs.getInt(3)));
            out.put(code, rs.getBigDecimal(4));
        }, orgId, from, to);
        return out;
    }

    @Transactional
    public Cpc cpc(Integer year, LocalDate from, LocalDate to, boolean comparative) {
        UUID orgId = org();
        LocalDate f;
        LocalDate t;
        if (from == null && to == null) {
            Year fy = year(orgId, year != null ? year : closing.yearOf(orgId, LocalDate.now()));
            f = fy.start();
            t = fy.end();
        } else {
            f = from != null ? from : yearOf(orgId, to).start();
            t = to != null ? to : yearOf(orgId, f).end();
        }
        if (f.isAfter(t)) throw new IllegalArgumentException("'from' must be on or before 'to'");
        LocalDate pf = comparative ? f.minusYears(1) : null;
        LocalDate pt = comparative ? t.minusYears(1) : null;

        Map<String, String> names = new HashMap<>();
        Map<String, String> classOf = new HashMap<>();
        Map<String, BigDecimal> cur = cpcBalances(orgId, f, t, names, classOf);
        Map<String, BigDecimal> prior = comparative ? cpcBalances(orgId, pf, pt, names, classOf) : Map.of();

        Map<String, Map<String, BigDecimal[]>> rows = new LinkedHashMap<>();
        for (CpcDef d : CPC) rows.put(d.key(), new TreeMap<>());
        for (int pass = 0; pass < 2; pass++) {
            Map<String, BigDecimal> src = pass == 0 ? cur : prior;
            for (Map.Entry<String, BigDecimal> e : src.entrySet()) {
                String code = e.getKey();
                int cls = Integer.parseInt(classOf.get(code));
                String section = cpcSection(code, cls);
                String c3 = code3(code);
                if (c3.equals("614")) c3 = "613";
                if (c3.startsWith("67")) c3 = "670";
                boolean product = cls != 6;
                BigDecimal v = product ? e.getValue().negate() : e.getValue();
                BigDecimal[] acc = rows.get(section).computeIfAbsent(c3, k -> new BigDecimal[]{ZERO, ZERO});
                acc[pass] = acc[pass].add(v);
            }
        }

        Map<String, BigDecimal[]> totals = new HashMap<>();
        List<CpcSection> sections = new ArrayList<>();
        for (CpcDef d : CPC) {
            if (d.type().equals("RESULT")) {
                BigDecimal[] r = result(d.key(), totals);
                totals.put(d.key(), r);
                sections.add(new CpcSection(d.key(), d.label(), d.type(), List.of(), money(r[0]),
                        comparative ? money(r[1]) : null));
                continue;
            }
            List<CpcRow> list = new ArrayList<>();
            BigDecimal tc = ZERO, tp = ZERO;
            for (Map.Entry<String, BigDecimal[]> e : rows.get(d.key()).entrySet()) {
                String c3 = e.getKey();
                BigDecimal c = money(e.getValue()[0]);
                BigDecimal p = money(e.getValue()[1]);
                if (c.signum() == 0 && p.signum() == 0) continue;
                String accountFrom = c3.equals("670") ? "67" : c3;
                String accountTo = c3.equals("613") ? "614" : accountFrom;
                list.add(new CpcRow(c3, label(c3, names), accountFrom, accountTo, c, comparative ? p : null));
                tc = tc.add(c);
                tp = tp.add(p);
            }
            totals.put(d.key(), new BigDecimal[]{tc, tp});
            sections.add(new CpcSection(d.key(), d.label(), d.type(), list, tc, comparative ? tp : null));
        }
        BigDecimal[] net = totals.get("XIII");
        return new Cpc(closing.yearOf(orgId, t), f, t, pf, pt, sections, money(net[0]), comparative ? money(net[1]) : null);
    }

    private static BigDecimal[] result(String key, Map<String, BigDecimal[]> t) {
        return switch (key) {
            case "III" -> minus(t.get("I"), t.get("II"));
            case "VI" -> minus(t.get("IV"), t.get("V"));
            case "VII" -> plus(t.get("III"), t.get("VI"));
            case "X" -> minus(t.get("VIII"), t.get("IX"));
            case "XI" -> plus(t.get("VII"), t.get("X"));
            case "XIII" -> minus(t.get("XI"), t.get("XII"));
            default -> throw new IllegalStateException(key);
        };
    }

    private static BigDecimal[] minus(BigDecimal[] a, BigDecimal[] b) {
        return new BigDecimal[]{a[0].subtract(b[0]), a[1].subtract(b[1])};
    }

    private static BigDecimal[] plus(BigDecimal[] a, BigDecimal[] b) {
        return new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])};
    }
}
