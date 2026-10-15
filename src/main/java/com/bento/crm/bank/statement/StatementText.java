package com.bento.crm.bank.statement;

import com.bento.crm.bank.model.BankAccount;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading the dates, amounts, account numbers and bank names that Moroccan statements contain. */
public final class StatementText {

    private StatementText() {}

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"), DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("d/M/uu"), DateTimeFormatter.ofPattern("d-M-uu"), DateTimeFormatter.ofPattern("d.M.uu"));

    /** Bank names as they appear on statements. */
    private static final Map<String, BankAccount.Bank> NAMES = Map.ofEntries(
            Map.entry("attijariwafa", BankAccount.Bank.ATTIJARIWAFA), Map.entry("attijari", BankAccount.Bank.ATTIJARIWAFA),
            Map.entry("bank of africa", BankAccount.Bank.BANK_OF_AFRICA), Map.entry("bmce", BankAccount.Bank.BANK_OF_AFRICA),
            Map.entry("cih bank", BankAccount.Bank.CIH), Map.entry("credit immobilier et hotelier", BankAccount.Bank.CIH),
            Map.entry("banque populaire", BankAccount.Bank.BANQUE_POPULAIRE), Map.entry("chaabi", BankAccount.Bank.BANQUE_POPULAIRE),
            Map.entry("societe generale", BankAccount.Bank.SOCIETE_GENERALE), Map.entry("bmci", BankAccount.Bank.BMCI),
            Map.entry("credit agricole", BankAccount.Bank.CREDIT_AGRICOLE), Map.entry("credit du maroc", BankAccount.Bank.CREDIT_DU_MAROC),
            Map.entry("al barid", BankAccount.Bank.AL_BARID));

    /** The first three digits of a Moroccan RIB name the bank. */
    private static final Map<String, BankAccount.Bank> RIB_CODES = Map.ofEntries(
            Map.entry("007", BankAccount.Bank.ATTIJARIWAFA), Map.entry("011", BankAccount.Bank.BANK_OF_AFRICA),
            Map.entry("230", BankAccount.Bank.CIH), Map.entry("022", BankAccount.Bank.SOCIETE_GENERALE),
            Map.entry("013", BankAccount.Bank.BMCI), Map.entry("225", BankAccount.Bank.CREDIT_AGRICOLE),
            Map.entry("021", BankAccount.Bank.CREDIT_DU_MAROC), Map.entry("350", BankAccount.Bank.AL_BARID));

    private static final Pattern RIB = Pattern.compile("(?<!\\d)(\\d[\\d ]{22,34}\\d)(?!\\d)");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)(?:compte|account|n°\\s*compte|rib)[^\\d]{0,20}(\\d[\\d ]{8,30}\\d)");

    /** Lower case, no accents, single spaces. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    public static LocalDate date(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 10 && t.charAt(4) == '-' && t.contains("T")) t = t.substring(0, 10);
        if (t.isEmpty() || t.length() > 10) return null;
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate d = LocalDate.parse(t, f);
                if (d.getYear() < 100) d = d.plusYears(2000);
                if (d.getYear() >= 2000 && d.getYear() <= 2100) return d;
            } catch (Exception ignored) {
                // next format
            }
        }
        return null;
    }

    /**
     * An amount as banks print it: "1 234,56", "1.234,56", "1,234.56", "-50", "(50,00)", "120,00 DH".
     * Returns null for anything else.
     */
    public static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = s.replace(' ', ' ').replace(' ', ' ').trim();
        if (t.isEmpty()) return null;
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replaceAll("(?i)(mad|dhs?|dirhams?)", "").trim();
        if (t.endsWith("-")) {
            negative = true;
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.startsWith("-")) {
            negative = !negative;
            t = t.substring(1).trim();
        } else if (t.startsWith("+")) {
            t = t.substring(1).trim();
        }
        t = t.replace(" ", "");
        if (!t.matches("[\\d.,']+") || !t.matches(".*\\d.*")) return null;
        t = t.replace("'", "");
        int comma = t.lastIndexOf(','), dot = t.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            t = comma > dot ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        } else if (comma >= 0) {
            // "1,234" with exactly three digits after a single comma is ambiguous; banks here use the comma as decimal mark.
            t = t.chars().filter(c -> c == ',').count() > 1 ? t.replace(",", "") : t.replace(',', '.');
        } else if (dot >= 0 && t.chars().filter(c -> c == '.').count() > 1) {
            t = t.replace(".", "");
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The account number printed in the statement's heading, as digits. */
    public static String accountNumber(String headerText) {
        if (headerText == null) return null;
        Matcher rib = RIB.matcher(headerText);
        while (rib.find()) {
            String d = rib.group(1).replace(" ", "");
            if (d.length() == 24) return d;
        }
        Matcher m = ACCOUNT.matcher(headerText);
        if (m.find()) return m.group(1).replace(" ", "");
        return null;
    }

    public static BankAccount.Bank bank(String headerText, String accountNumber) {
        String n = norm(headerText);
        for (Map.Entry<String, BankAccount.Bank> e : NAMES.entrySet()) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        if (accountNumber != null && accountNumber.length() == 24) {
            BankAccount.Bank b = RIB_CODES.get(accountNumber.substring(0, 3));
            if (b != null) return b;
            // Banque Populaire's regional banks use codes 101 to 190.
            int code = Integer.parseInt(accountNumber.substring(0, 3));
            if (code >= 101 && code <= 190) return BankAccount.Bank.BANQUE_POPULAIRE;
        }
        return null;
    }
}
