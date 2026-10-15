package com.bento.crm.common.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Writes an amount out in French, as Moroccan invoices state their total ("Arrêtée la présente
 * facture à la somme de ..."): {@code 1250.50} becomes "mille deux cent cinquante dirhams et
 * cinquante centimes".
 */
public final class AmountInWords {

    private static final String[] UNITS = {
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] TENS = {
            "", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante", "quatre-vingt", "quatre-vingt"};

    private AmountInWords() {
    }

    public static String dirhams(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount.abs()).setScale(2, RoundingMode.HALF_UP);
        long whole = value.longValue();
        int cents = value.subtract(BigDecimal.valueOf(whole)).movePointRight(2).intValue();
        StringBuilder sb = new StringBuilder(words(whole)).append(whole > 1 ? " dirhams" : " dirham");
        if (cents > 0) {
            sb.append(" et ").append(words(cents)).append(cents > 1 ? " centimes" : " centime");
        }
        return sb.toString();
    }

    static String words(long n) {
        if (n < 0) {
            return "moins " + words(-n);
        }
        if (n < 1000) {
            return belowThousand((int) n, true);
        }
        StringBuilder sb = new StringBuilder();
        long billions = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long thousands = (n / 1000L) % 1000;
        int rest = (int) (n % 1000);
        if (billions > 0) {
            sb.append(words(billions)).append(billions > 1 ? " milliards" : " milliard");
        }
        if (millions > 0) {
            space(sb).append(belowThousand((int) millions, false)).append(millions > 1 ? " millions" : " million");
        }
        if (thousands > 0) {
            space(sb).append(thousands == 1 ? "mille" : belowThousand((int) thousands, false) + " mille");
        }
        if (rest > 0) {
            space(sb).append(belowThousand(rest, true));
        }
        return sb.toString();
    }

    /** 0–999. {@code last} is false before "mille"/"millions", where "cents" and "quatre-vingts" lose their s. */
    private static String belowThousand(int n, boolean last) {
        if (n == 0) {
            return UNITS[0];
        }
        int hundreds = n / 100;
        int rest = n % 100;
        StringBuilder sb = new StringBuilder();
        if (hundreds > 0) {
            sb.append(hundreds == 1 ? "cent" : UNITS[hundreds] + " cent");
            if (hundreds > 1 && rest == 0 && last) {
                sb.append('s');
            }
        }
        if (rest > 0) {
            space(sb).append(belowHundred(rest, last));
        }
        return sb.toString();
    }

    private static String belowHundred(int n, boolean last) {
        if (n < 20) {
            return UNITS[n];
        }
        int ten = n / 10;
        int unit = n % 10;
        if (ten == 7 || ten == 9) {
            int r = 10 + unit;
            return TENS[ten] + (ten == 7 && unit == 1 ? " et " : "-") + UNITS[r];
        }
        if (unit == 0) {
            return TENS[ten] + (ten == 8 && last ? "s" : "");
        }
        if (unit == 1 && ten != 8) {
            return TENS[ten] + " et un";
        }
        return TENS[ten] + "-" + UNITS[unit];
    }

    private static StringBuilder space(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        return sb;
    }
}
