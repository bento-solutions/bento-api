package com.bento.crm.tax.service;

import com.bento.crm.tax.model.TaxDeadline;
import com.bento.crm.tax.model.TaxProfile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * The Moroccan filing deadlines this calendar generates. These are the general rules of the Code
 * Général des Impôts and the CNSS; special regimes (new companies, exemptions, prorated first
 * years) are not modelled, so the calendar is a reminder, not tax advice.
 *
 * <ul>
 *   <li>TVA, monthly regime: before the end of the month following the month (CGI art. 110).</li>
 *   <li>TVA, quarterly regime: before the end of the month following the quarter.</li>
 *   <li>IS instalments: four, before the end of the 3rd, 6th, 9th and 12th month of the
 *       financial year (art. 170).</li>
 *   <li>IS return: within three months of the financial year end (art. 20).</li>
 *   <li>IR withheld on salaries: within the month following payment (art. 174).</li>
 *   <li>CNSS: declaration and payment before the 10th of the following month.</li>
 *   <li>Annual salary declaration (état 9421): before 1 March.</li>
 * </ul>
 * A deadline that falls on a weekend moves to the next Monday.
 */
public final class TaxRules {

    private TaxRules() {}

    public record Deadline(TaxDeadline.Kind kind, String periodLabel, LocalDate periodStart, LocalDate periodEnd,
                           LocalDate dueDate) {}

    /** Every deadline of the profile whose due date falls between {@code from} and {@code to}. */
    public static List<Deadline> between(TaxProfile p, LocalDate from, LocalDate to) {
        List<Deadline> out = new ArrayList<>();
        // Periods that end up to two years before "to" can still be due inside the window.
        YearMonth first = YearMonth.from(from).minusMonths(15), last = YearMonth.from(to);
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            String label = m.toString();
            if (p.getVatRegime() == TaxProfile.VatRegime.MONTHLY) {
                add(out, from, to, TaxDeadline.Kind.TVA, label, m.atDay(1), m.atEndOfMonth(), m.plusMonths(1).atEndOfMonth());
            }
            if (p.getVatRegime() == TaxProfile.VatRegime.QUARTERLY && m.getMonthValue() % 3 == 0) {
                YearMonth start = m.minusMonths(2);
                add(out, from, to, TaxDeadline.Kind.TVA, m.getYear() + "-T" + (m.getMonthValue() / 3), start.atDay(1),
                        m.atEndOfMonth(), m.plusMonths(1).atEndOfMonth());
            }
            if (p.isHasEmployees()) {
                add(out, from, to, TaxDeadline.Kind.IR_SALARIES, label, m.atDay(1), m.atEndOfMonth(), m.plusMonths(1).atEndOfMonth());
                add(out, from, to, TaxDeadline.Kind.CNSS, label, m.atDay(1), m.atEndOfMonth(), m.plusMonths(1).atDay(10));
            }
            // Financial years are labelled by the year they end in.
            if (p.isCorporateTax() && m.getMonthValue() == p.getFiscalYearEndMonth()) {
                YearMonth fyStart = m.minusMonths(11);
                String fy = "FY" + m.getYear();
                add(out, from, to, TaxDeadline.Kind.IS_RETURN, fy, fyStart.atDay(1), m.atEndOfMonth(), m.plusMonths(3).atEndOfMonth());
                // The instalments of the year that ends in m.
                for (int i = 1; i <= 4; i++) {
                    add(out, from, to, TaxDeadline.Kind.IS_INSTALLMENT, fy + "-A" + i, fyStart.atDay(1), m.atEndOfMonth(),
                            fyStart.plusMonths(3L * i - 1).atEndOfMonth());
                }
            }
            if (p.isHasEmployees() && m.getMonthValue() == 12) {
                add(out, from, to, TaxDeadline.Kind.IR_ANNUAL_SALARIES, String.valueOf(m.getYear()), m.minusMonths(11).atDay(1),
                        m.atEndOfMonth(), YearMonth.of(m.getYear() + 1, 2).atEndOfMonth());
            }
        }
        return out;
    }

    private static void add(List<Deadline> out, LocalDate from, LocalDate to, TaxDeadline.Kind kind, String label,
                            LocalDate start, LocalDate end, LocalDate due) {
        LocalDate d = workingDay(due);
        if (!d.isBefore(from) && !d.isAfter(to)) {
            out.add(new Deadline(kind, label, start, end, d));
        }
    }

    static LocalDate workingDay(LocalDate d) {
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) return d.plusDays(2);
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) return d.plusDays(1);
        return d;
    }
}
