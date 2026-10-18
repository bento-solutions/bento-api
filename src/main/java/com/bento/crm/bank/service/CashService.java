package com.bento.crm.bank.service;

import com.bento.crm.bank.dto.BankDtos;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.invoice.model.RecurringInvoice;
import com.bento.crm.invoice.repository.RecurringInvoiceRepository;
import com.bento.crm.invoice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cash position: what is in the bank now, where it went, and what the next 90 days look like
 * from open invoices, bills to pay, recurring invoices and the costs that come back every month.
 * The forecast is an estimate from those sources; it does not book anything.
 */
@Service
@RequiredArgsConstructor
public class CashService {

    static final int WEEKS = 13;
    private static final int[] HORIZONS = {30, 60, 90};

    private final BankService bankService;
    private final RecurringInvoiceRepository recurringRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public BankDtos.Dashboard dashboard() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        LocalDate today = LocalDate.now();
        List<BankDtos.Account> accounts = bankService.accounts();
        BigDecimal total = accounts.stream().filter(BankDtos.Account::active).map(BankDtos.Account::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long unmatched = count("SELECT count(*) FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL AND status = 'UNMATCHED'", orgId);
        long uncategorized = count("SELECT count(*) FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL AND category IS NULL AND status <> 'IGNORED'", orgId);

        LocalDate since = YearMonth.from(today).minusMonths(5).atDay(1);
        Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) byMonth.put(YearMonth.from(today).minusMonths(i).toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        // Moves between the company's own accounts are neither income nor spending.
        jdbc.query("""
                SELECT to_char(booking_date, 'YYYY-MM') AS m,
                       COALESCE(SUM(amount) FILTER (WHERE amount > 0), 0) AS inflow,
                       COALESCE(-SUM(amount) FILTER (WHERE amount < 0), 0) AS outflow
                FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL AND status <> 'IGNORED'
                  AND COALESCE(category, '') <> 'TRANSFER' AND booking_date >= ?
                GROUP BY 1
                """, rs -> {
            BigDecimal[] v = byMonth.get(rs.getString("m"));
            if (v != null) {
                v[0] = rs.getBigDecimal("inflow");
                v[1] = rs.getBigDecimal("outflow");
            }
        }, orgId, since);
        List<BankDtos.Month> months = byMonth.entrySet().stream()
                .map(e -> new BankDtos.Month(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();

        List<BankDtos.CategoryTotal> byCategory = jdbc.query("""
                SELECT COALESCE(category, 'NONE') AS c, -SUM(amount) AS total
                FROM bank_transaction WHERE organization_id = ? AND deleted_at IS NULL AND status <> 'IGNORED'
                  AND amount < 0 AND COALESCE(category, '') <> 'TRANSFER' AND booking_date >= ?
                GROUP BY 1 ORDER BY 2 DESC
                """, (rs, i) -> new BankDtos.CategoryTotal(rs.getString("c"), rs.getBigDecimal("total")), orgId, today.minusDays(90));

        return new BankDtos.Dashboard(total, accounts, unmatched, uncategorized, months, byCategory, forecast(orgId, total, today));
    }

    BankDtos.Forecast forecast(UUID orgId, BigDecimal start, LocalDate today) {
        LocalDate end = today.plusDays(90);
        List<BankDtos.ForecastItem> items = new ArrayList<>();

        // Open invoices and bills, on the date they are expected to be paid. Late ones count as this week.
        jdbc.query("""
                SELECT * FROM (
                  SELECT i.id, i.type, i.invoice_number, COALESCE(pa.name, i.customer_name) AS partner,
                         COALESCE(i.scheduled_payment_date, i.due_date, i.invoice_date + 30) AS expected,
                         i.total - COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.invoice_id = i.id AND p.deleted_at IS NULL), 0) AS outstanding
                  FROM invoice i LEFT JOIN partner pa ON pa.id = i.partner_id
                  WHERE i.organization_id = ? AND i.deleted_at IS NULL AND i.total IS NOT NULL
                    AND i.status IN ('SENT', 'PARTIALLY_PAID', 'OVERDUE')
                ) x WHERE outstanding > 0
                """, rs -> {
            Date d = rs.getDate("expected");
            LocalDate date = d == null ? today : d.toLocalDate();
            if (date.isBefore(today)) date = today;
            if (date.isAfter(end)) return;
            boolean customer = "CUSTOMER".equals(rs.getString("type"));
            String who = rs.getString("partner");
            String label = (rs.getString("invoice_number") == null ? "" : rs.getString("invoice_number") + " ")
                    + (who == null ? "" : who);
            BigDecimal amount = rs.getBigDecimal("outstanding");
            items.add(new BankDtos.ForecastItem(date, customer ? "RECEIVABLE" : "PAYABLE", label.trim(),
                    customer ? amount : amount.negate(), rs.getObject("id", UUID.class)));
        }, orgId);

        // Recurring invoices still to be issued: cash comes in after their payment terms.
        for (RecurringInvoice r : recurringRepository.findByOrganizationId(orgId, org.springframework.data.domain.Pageable.unpaged())) {
            if (r.getStatus() != RecurringInvoice.Status.ACTIVE || r.getNextRunDate() == null) continue;
            BigDecimal gross = InvoiceService.grossOfLines(r.getLines());
            if (gross == null || gross.signum() <= 0) continue;
            int terms = r.getPaymentTermsDays() == null ? 30 : r.getPaymentTermsDays();
            LocalDate run = r.getNextRunDate();
            for (int guard = 0; guard < 200 && !run.isAfter(end); guard++) {
                if (r.getEndDate() != null && run.isAfter(r.getEndDate())) break;
                LocalDate cash = run.plusDays(terms);
                if (cash.isBefore(today)) cash = today;
                if (!cash.isAfter(end)) {
                    items.add(new BankDtos.ForecastItem(cash, "RECURRING_INVOICE", r.getName(), gross, null));
                }
                run = next(run, r.getFrequency(), r.getIntervalCount() == null ? 1 : Math.max(1, r.getIntervalCount()));
            }
        }

        // Costs that come back every month (salaries, CNSS, rent...): their average per month over
        // the months they were seen in the last three full months (a company's first statements
        // often cover only two), on the day of the month they usually leave.
        LocalDate from = YearMonth.from(today).minusMonths(3).atDay(1);
        LocalDate to = YearMonth.from(today).atDay(1).minusDays(1);
        Map<String, BigDecimal> totals = new HashMap<>();
        Map<String, Set<String>> monthsSeen = new HashMap<>();
        Map<String, Integer> lastDay = new HashMap<>();
        jdbc.query("""
                SELECT category, booking_date, -amount AS out FROM bank_transaction
                WHERE organization_id = ? AND deleted_at IS NULL AND status <> 'IGNORED' AND amount < 0
                  AND category = ANY(?) AND booking_date BETWEEN ? AND ? ORDER BY booking_date
                """, rs -> {
            String c = rs.getString("category");
            LocalDate d = rs.getDate("booking_date").toLocalDate();
            totals.merge(c, rs.getBigDecimal("out"), BigDecimal::add);
            monthsSeen.computeIfAbsent(c, k -> new HashSet<>()).add(YearMonth.from(d).toString());
            lastDay.put(c, d.getDayOfMonth());
        }, orgId, BankCategories.RECURRING_OUT.toArray(String[]::new), from, to);
        List<BankDtos.CategoryTotal> recurringMonthly = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            if (monthsSeen.get(e.getKey()).size() < 2) continue; // seen once: not a monthly cost
            BigDecimal monthly = e.getValue().divide(BigDecimal.valueOf(monthsSeen.get(e.getKey()).size()), 2, RoundingMode.HALF_UP);
            recurringMonthly.add(new BankDtos.CategoryTotal(e.getKey(), monthly));
            int day = lastDay.get(e.getKey());
            for (YearMonth m = YearMonth.from(today); !m.atDay(1).isAfter(end); m = m.plusMonths(1)) {
                LocalDate d = m.atDay(Math.min(day, m.lengthOfMonth()));
                if (d.isBefore(today) || d.isAfter(end)) continue;
                items.add(new BankDtos.ForecastItem(d, "RECURRING_COST", e.getKey(), monthly.negate(), null));
            }
        }
        recurringMonthly.sort(Comparator.comparing(BankDtos.CategoryTotal::amount).reversed());
        items.sort(Comparator.comparing(BankDtos.ForecastItem::date));

        List<BankDtos.Horizon> horizons = new ArrayList<>();
        for (int days : HORIZONS) {
            LocalDate h = today.plusDays(days);
            BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
            for (BankDtos.ForecastItem it : items) {
                if (it.date().isAfter(h)) continue;
                if (it.amount().signum() > 0) in = in.add(it.amount());
                else out = out.add(it.amount().negate());
            }
            horizons.add(new BankDtos.Horizon(days, in, out, start.add(in).subtract(out)));
        }

        LocalDate week0 = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        BigDecimal[] in = new BigDecimal[WEEKS], out = new BigDecimal[WEEKS];
        for (int w = 0; w < WEEKS; w++) { in[w] = BigDecimal.ZERO; out[w] = BigDecimal.ZERO; }
        for (BankDtos.ForecastItem it : items) {
            int w = (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(week0, it.date()) / 7);
            if (w >= WEEKS) continue;
            if (it.amount().signum() > 0) in[w] = in[w].add(it.amount());
            else out[w] = out[w].add(it.amount().negate());
        }
        List<BankDtos.Week> weeks = new ArrayList<>();
        BigDecimal running = start, lowest = start;
        LocalDate lowestWeek = week0;
        for (int w = 0; w < WEEKS; w++) {
            running = running.add(in[w]).subtract(out[w]);
            weeks.add(new BankDtos.Week(week0.plusWeeks(w), in[w], out[w], running));
            if (running.compareTo(lowest) < 0) {
                lowest = running;
                lowestWeek = week0.plusWeeks(w);
            }
        }
        return new BankDtos.Forecast(today, start, horizons, weeks, lowest, lowestWeek,
                items.size() > 100 ? items.subList(0, 100) : items, recurringMonthly);
    }

    static LocalDate next(LocalDate d, RecurringInvoice.Frequency f, int n) {
        return switch (f) {
            case WEEKLY -> d.plusWeeks(n);
            case MONTHLY -> d.plusMonths(n);
            case QUARTERLY -> d.plusMonths(3L * n);
            case YEARLY -> d.plusYears(n);
        };
    }

    private long count(String sql, UUID orgId) {
        Long n = jdbc.queryForObject(sql, Long.class, orgId);
        return n == null ? 0 : n;
    }
}
