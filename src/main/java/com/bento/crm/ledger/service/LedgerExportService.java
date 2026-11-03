package com.bento.crm.ledger.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.ledger.dto.LedgerStatementDtos;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The accountant export: the entry lines of a period in FEC-style columns, as a workbook with the
 * reports beside them (trial balance, partner balances, bilan, CPC, VAT, chart of accounts) or
 * as a CSV of the entries alone. The entries are streamed (SXSSF, a database cursor), never held
 * in memory as a whole. Also the formatted Excel files of the bilan, the CPC and the partner balances.
 */
@Service
@RequiredArgsConstructor
public class LedgerExportService {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String[] COLUMNS = {"JournalCode", "JournalLib", "EcritureNum", "EcritureDate", "CompteNum",
            "CompteLib", "CompAuxNum", "CompAuxLib", "PieceRef", "PieceDate", "EcritureLib", "Debit", "Credit",
            "SectionAnalytique", "SourceType"};
    private static final DateTimeFormatter FEC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LedgerSetupService setup;
    private final LedgerClosingService closing;
    private final LedgerReportService reports;
    private final LedgerStatementsService statements;
    private final LedgerPartnerService partners;
    private final LedgerVatService vat;
    private final JdbcTemplate jdbc;

    private UUID org() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        setup.ensure(orgId);
        return orgId;
    }

    public record Period(LocalDate from, LocalDate to) {
        public String filename(String ext) {
            return "bento-export-" + from + "-" + to + "." + ext;
        }
    }

    /** The export period: the given dates, by default the current fiscal year. */
    @Transactional
    public Period period(LocalDate from, LocalDate to) {
        UUID orgId = org();
        LocalDate f = from;
        LocalDate t = to;
        if (f == null || t == null) {
            int y = closing.yearOf(orgId, f != null ? f : t != null ? t : LocalDate.now());
            LocalDate start = closing.yearStart(orgId, y);
            if (f == null) f = start;
            if (t == null) t = start.plusYears(1).minusDays(1);
        }
        if (f.isAfter(t)) throw new IllegalArgumentException("'from' must be on or before 'to'");
        return new Period(f, t);
    }

    public static String format(String format) {
        String f = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        if (!f.equals("xlsx") && !f.equals("csv")) throw new IllegalArgumentException("format is xlsx or csv");
        return f;
    }

    // Report files

    @Transactional
    public byte[] bilanXlsx(Integer year, LocalDate date, boolean comparative) {
        XSSFWorkbook wb = new XSSFWorkbook();
        new LedgerXlsx(wb).bilan(statements.bilan(year, date, comparative), "Bilan");
        return LedgerXlsx.bytes(wb);
    }

    @Transactional
    public byte[] cpcXlsx(Integer year, LocalDate from, LocalDate to, boolean comparative) {
        XSSFWorkbook wb = new XSSFWorkbook();
        new LedgerXlsx(wb).cpc(statements.cpc(year, from, to, comparative), "CPC");
        return LedgerXlsx.bytes(wb);
    }

    @Transactional
    public byte[] balancesXlsx(String type, LocalDate from, LocalDate to, String search, boolean onlyOpen) {
        LedgerStatementDtos.PartnerBalances b = partners.allBalances(type, from, to, search, onlyOpen);
        XSSFWorkbook wb = new XSSFWorkbook();
        new LedgerXlsx(wb).balances(b, LedgerPartnerService.CUSTOMER.equals(b.type()) ? "Balance clients" : "Balance fournisseurs");
        return LedgerXlsx.bytes(wb);
    }

    // The accountant export

    /** One entry line in the FEC-style columns. */
    private interface LineSink {
        void line(String journalCode, String journalLib, String number, LocalDate date, String account,
                  String accountLib, String auxNum, String auxLib, String pieceRef, LocalDate pieceDate, String label,
                  BigDecimal debit, BigDecimal credit, String costCenter, String sourceType) throws IOException;
    }

    private void entries(UUID orgId, Period p, LineSink sink) {
        JdbcTemplate cursor = new JdbcTemplate(jdbc.getDataSource());
        cursor.setFetchSize(1000);
        cursor.query("""
                SELECT j.code, j.name, e.number, e.entry_date, a.code, a.name, l.partner_id, pt.name,
                       COALESCE(i.invoice_number, e.source_label, e.number), COALESCE(i.invoice_date, e.entry_date),
                       COALESCE(l.label, e.description), l.debit, l.credit, cc.code, e.source_type
                FROM ledger_entry_line l
                JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL
                JOIN ledger_account a ON a.id = l.account_id AND a.organization_id = l.organization_id
                JOIN ledger_journal j ON j.id = e.journal_id
                LEFT JOIN partner pt ON pt.id = l.partner_id
                LEFT JOIN ledger_cost_center cc ON cc.id = l.cost_center_id
                LEFT JOIN invoice i ON i.id = e.source_id AND i.organization_id = e.organization_id
                     AND e.source_type IN ('INVOICE', 'BILL', 'ADVANCE', 'CREDIT_NOTE')
                WHERE l.organization_id = ? AND l.deleted_at IS NULL AND e.entry_date >= ? AND e.entry_date <= ?
                ORDER BY e.entry_date, j.code, e.number, l.line_no
                """, rs -> {
            UUID partnerId = rs.getObject(7, UUID.class);
            try {
                sink.line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getString(5), rs.getString(6),
                        partnerId == null ? null : partnerId.toString().substring(0, 8).toUpperCase(Locale.ROOT),
                        rs.getString(8), rs.getString(9), rs.getObject(10, LocalDate.class), rs.getString(11),
                        rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getString(14), rs.getString(15));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, orgId, p.from(), p.to());
    }

    @Transactional(readOnly = true)
    public void csv(Period p, OutputStream out) throws IOException {
        UUID orgId = org();
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.write('﻿');
        w.write(String.join(";", COLUMNS));
        w.write("\r\n");
        entries(orgId, p, (jc, jl, num, date, acc, accLib, auxNum, auxLib, piece, pieceDate, label, debit, credit, cc, st) -> {
            String[] v = {jc, jl, num, date == null ? "" : date.format(FEC_DATE), acc, accLib, auxNum, auxLib, piece,
                    pieceDate == null ? "" : pieceDate.format(FEC_DATE), label, plain(debit), plain(credit), cc, st};
            for (int i = 0; i < v.length; i++) {
                if (i > 0) w.write(';');
                w.write(csvField(v[i]));
            }
            w.write("\r\n");
        });
        w.flush();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String csvField(String v) {
        if (v == null) return "";
        if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    @Transactional
    public void xlsx(Period p, OutputStream out) throws IOException {
        UUID orgId = org();
        SXSSFWorkbook wb = new SXSSFWorkbook(200);
        try {
            LedgerXlsx x = new LedgerXlsx(wb);
            Sheet s = wb.createSheet("Écritures");
            LedgerXlsx.widths(s, 8, 20, 14, 12, 12, 35, 12, 30, 18, 12, 40, 14, 14, 12, 14);
            x.headers(s, 0, COLUMNS);
            int[] r = {1};
            entries(orgId, p, (jc, jl, num, date, acc, accLib, auxNum, auxLib, piece, pieceDate, label, debit, credit, cc, st) -> {
                Row row = s.createRow(r[0]++);
                x.text(row, 0, jc, null);
                x.text(row, 1, jl, null);
                x.text(row, 2, num, null);
                x.day(row, 3, date);
                x.text(row, 4, acc, null);
                x.text(row, 5, accLib, null);
                x.text(row, 6, auxNum, null);
                x.text(row, 7, auxLib, null);
                x.text(row, 8, piece, null);
                x.day(row, 9, pieceDate);
                x.text(row, 10, label, null);
                x.amount(row, 11, debit, false);
                x.amount(row, 12, credit, false);
                x.text(row, 13, cc, null);
                x.text(row, 14, st, null);
            });

            x.trialBalance(reports.trialBalance(p.from(), p.to(), null), "Balance générale");
            x.balances(partners.allBalances(LedgerPartnerService.CUSTOMER, p.from(), p.to(), null, false), "Balance clients");
            x.balances(partners.allBalances(LedgerPartnerService.SUPPLIER, p.from(), p.to(), null, false), "Balance fournisseurs");
            int year = closing.yearOf(orgId, p.to());
            x.bilan(statements.bilan(year, null, true), "Bilan");
            x.cpc(statements.cpc(year, null, null, true), "CPC");
            x.vat(vat.summary(p.from(), p.to()), "TVA");
            chart(x, orgId, p);
            wb.write(out);
            out.flush();
        } finally {
            wb.close();
            wb.dispose();
        }
    }

    /** The accounts that have movements in the period. */
    private void chart(LedgerXlsx x, UUID orgId, Period p) {
        Sheet s = x.wb.createSheet("Plan comptable");
        LedgerXlsx.widths(s, 14, 50, 8, 12);
        x.headers(s, 0, "CompteNum", "CompteLib", "Classe", "Nature");
        int[] r = {1};
        jdbc.query("""
                SELECT a.code, a.name, a.account_class, a.kind FROM ledger_account a
                WHERE a.organization_id = ? AND EXISTS (
                    SELECT 1 FROM ledger_entry_line l
                    JOIN ledger_entry e ON e.id = l.entry_id AND e.organization_id = l.organization_id AND e.deleted_at IS NULL
                    WHERE l.organization_id = a.organization_id AND l.account_id = a.id AND l.deleted_at IS NULL
                      AND e.entry_date >= ? AND e.entry_date <= ?)
                ORDER BY a.code
                """, rs -> {
            Row row = s.createRow(r[0]++);
            x.text(row, 0, rs.getString(1), null);
            x.text(row, 1, rs.getString(2), null);
            row.createCell(2).setCellValue(rs.getInt(3));
            x.text(row, 3, rs.getString(4), null);
        }, orgId, p.from(), p.to());
    }
}
