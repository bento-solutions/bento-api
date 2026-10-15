package com.bento.crm.bank.service;

import com.bento.crm.bank.statement.StatementText;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The categories a bank line can have, and the words on Moroccan statements that give them away. */
public final class BankCategories {

    private BankCategories() {}

    public static final List<String> ALL = List.of(
            "SALES", "SUPPLIERS", "SALARIES", "SOCIAL_CHARGES", "TAXES", "RENT", "UTILITIES", "TELECOM", "FUEL",
            "TRAVEL", "MEALS", "OFFICE", "SOFTWARE", "MARKETING", "FEES", "BANK_FEES", "LOAN", "TRANSFER", "CASH",
            "OTHER");

    /** Outflows that come back every month; the forecast repeats their recent average. */
    public static final Set<String> RECURRING_OUT = Set.of(
            "SALARIES", "SOCIAL_CHARGES", "RENT", "UTILITIES", "TELECOM", "SOFTWARE", "LOAN", "BANK_FEES");

    private record Keyword(Pattern pattern, String category, int direction) {}

    private static Keyword k(String regex, String category, int direction) {
        return new Keyword(Pattern.compile("\\b(" + regex + ")\\b"), category, direction);
    }

    /** Checked in order; direction 1 = money in only, -1 = money out only, 0 = either. */
    private static final List<Keyword> KEYWORDS = List.of(
            k("cnss|amo|cimr", "SOCIAL_CHARGES", -1),
            k("dgi|tgr|impots?|tva|is acompte|acompte is|simpl", "TAXES", -1),
            k("salaires?|paie|virement de masse|vir masse", "SALARIES", -1),
            k("lydec|redal|amendis|onee|onep|radeema|radeef|radeec|ramsa", "UTILITIES", -1),
            k("iam|maroc telecom|inwi|orange|wana", "TELECOM", -1),
            k("loyers?", "RENT", -1),
            k("carburant|afriquia|shell|totalenergies|total energies|winxo|petrom|ziz", "FUEL", -1),
            k("agios|frais|commissions?|com\\.?|tenue de compte|cotisation carte|abonnement pack|interets debiteurs", "BANK_FEES", -1),
            k("echeance pret|remboursement pret|ech pret|echeance credit|leasing", "LOAN", -1),
            k("retrait gab|retrait dab|retrait especes|retrait", "CASH", -1),
            k("virement interne|vir interne|compte a compte", "TRANSFER", 0),
            k("remise cheques?|remise chq|encaissement|vir recu|virement recu|vir\\.? recu|versement", "SALES", 1));

    /** The built-in guess for a line, or null. */
    public static String keyword(String description, BigDecimal amount) {
        String n = StatementText.norm(description).replaceAll("[^a-z0-9 .]", " ");
        int sign = amount == null ? 0 : amount.signum();
        for (Keyword kw : KEYWORDS) {
            if (kw.direction() != 0 && kw.direction() != sign) continue;
            if (kw.pattern().matcher(n).find()) return kw.category();
        }
        return null;
    }

    public static String validate(String category) {
        if (category == null || category.isBlank()) return null;
        String c = category.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALL.contains(c)) {
            throw new IllegalArgumentException("Unknown category: " + category);
        }
        return c;
    }
}
