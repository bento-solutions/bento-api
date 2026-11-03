package com.bento.crm.ledger.service;

import com.bento.crm.ledger.dto.LedgerClosingDtos;
import com.bento.crm.ledger.dto.LedgerDtos;
import com.bento.crm.ledger.dto.LedgerStatementDtos.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Formatted sheets of the ledger reports (Apache POI): money with two decimals, totals in bold. */
final class LedgerXlsx {

    final Workbook wb;
    final CellStyle header;
    final CellStyle bold;
    final CellStyle money;
    final CellStyle moneyBold;
    final CellStyle date;
    final CellStyle title;

    LedgerXlsx(Workbook wb) {
        this.wb = wb;
        DataFormat df = wb.createDataFormat();
        Font boldFont = wb.createFont();
        boldFont.setBold(true);
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);

        header = wb.createCellStyle();
        header.setFont(boldFont);
        header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.THIN);

        bold = wb.createCellStyle();
        bold.setFont(boldFont);

        money = wb.createCellStyle();
        money.setDataFormat(df.getFormat("#,##0.00"));

        moneyBold = wb.createCellStyle();
        moneyBold.setDataFormat(df.getFormat("#,##0.00"));
        moneyBold.setFont(boldFont);

        date = wb.createCellStyle();
        date.setDataFormat(df.getFormat("dd/mm/yyyy"));

        title = wb.createCellStyle();
        title.setFont(titleFont);
    }

    static byte[] bytes(XSSFWorkbook wb) {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Cells

    void text(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    void amount(Row row, int col, BigDecimal value, boolean isBold) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value.doubleValue());
        c.setCellStyle(isBold ? moneyBold : money);
    }

    void day(Row row, int col, LocalDate value) {
        Cell c = row.createCell(col);
        if (value != null) {
            c.setCellValue(value);
            c.setCellStyle(date);
        }
    }

    void headers(Sheet sheet, int rowIdx, String... names) {
        Row r = sheet.createRow(rowIdx);
        for (int i = 0; i < names.length; i++) text(r, i, names[i], header);
    }

    static void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private static String fr(LocalDate d) {
        return d == null ? "" : String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    // Bilan

    void bilan(Bilan b, String sheetName) {
        Sheet s = wb.createSheet(sheetName);
        widths(s, 10, 55, 16, 16, 16, 16);
        int r = 0;
        text(s.createRow(r++), 0, "BILAN au " + fr(b.asOf()), title);
        Row info = s.createRow(r++);
        text(info, 0, "Exercice " + b.fiscalYear() + (b.balanced() ? "" : " - bilan déséquilibré"), null);
        r++;
        boolean prior = b.priorAsOf() != null;

        headers(s, r++, "Code", "ACTIF", "Brut", "Amort. et prov.", "Net", prior ? "Net N-1" : "");
        for (BilanSection sec : b.actif()) {
            r = section(s, r, sec, true, prior);
        }
        Row ta = s.createRow(r++);
        text(ta, 1, "TOTAL ACTIF", bold);
        amount(ta, 4, b.totalActif(), true);
        if (prior) amount(ta, 5, b.totalActifPrior(), true);
        r++;

        headers(s, r++, "Code", "PASSIF", "", "", "Net", prior ? "Net N-1" : "");
        for (BilanSection sec : b.passif()) {
            r = section(s, r, sec, false, prior);
        }
        Row tp = s.createRow(r++);
        text(tp, 1, "TOTAL PASSIF", bold);
        amount(tp, 4, b.totalPassif(), true);
        if (prior) amount(tp, 5, b.totalPassifPrior(), true);

        if (!b.warnings().isEmpty()) {
            r++;
            text(s.createRow(r++), 0, "Avertissements", bold);
            for (BilanWarning w : b.warnings()) {
                Row row = s.createRow(r++);
                text(row, 0, w.accountCode(), null);
                text(row, 1, w.accountName() + " : " + w.message(), null);
                amount(row, 4, w.balance(), false);
            }
        }
    }

    private int section(Sheet s, int r, BilanSection sec, boolean actif, boolean prior) {
        Row h = s.createRow(r++);
        text(h, 1, sec.label(), bold);
        for (BilanRow row : sec.rows()) {
            Row x = s.createRow(r++);
            text(x, 0, row.code(), null);
            text(x, 1, "   " + row.label(), null);
            if (actif) {
                amount(x, 2, row.gross(), false);
                amount(x, 3, row.amortProv(), false);
            }
            amount(x, 4, row.net(), false);
            if (prior) amount(x, 5, row.netPrior(), false);
        }
        Row t = s.createRow(r++);
        text(t, 1, "Total " + sec.label(), bold);
        if (actif) {
            amount(t, 2, sec.totalGross(), true);
            amount(t, 3, sec.totalAmortProv(), true);
        }
        amount(t, 4, sec.totalNet(), true);
        if (prior) amount(t, 5, sec.totalNetPrior(), true);
        return r;
    }

    // CPC

    void cpc(Cpc c, String sheetName) {
        Sheet s = wb.createSheet(sheetName);
        widths(s, 10, 60, 18, 18);
        int r = 0;
        text(s.createRow(r++), 0, "COMPTE DE PRODUITS ET CHARGES", title);
        text(s.createRow(r++), 0, "Du " + fr(c.from()) + " au " + fr(c.to()), null);
        r++;
        boolean prior = c.priorFrom() != null;
        headers(s, r++, "Code", "Désignation", "Exercice", prior ? "Exercice N-1" : "");
        for (CpcSection sec : c.sections()) {
            boolean result = "RESULT".equals(sec.type());
            if (!result) {
                Row h = s.createRow(r++);
                text(h, 0, sec.key(), bold);
                text(h, 1, sec.label(), bold);
                for (CpcRow row : sec.rows()) {
                    Row x = s.createRow(r++);
                    text(x, 0, row.code(), null);
                    text(x, 1, "   " + row.label(), null);
                    amount(x, 2, row.current(), false);
                    if (prior) amount(x, 3, row.prior(), false);
                }
            }
            Row t = s.createRow(r++);
            text(t, 0, result ? sec.key() : "", bold);
            text(t, 1, result ? sec.label() : "Total " + sec.key(), bold);
            amount(t, 2, sec.total(), true);
            if (prior) amount(t, 3, sec.totalPrior(), true);
        }
    }

    // Partner balances

    void balances(PartnerBalances b, String sheetName) {
        Sheet s = wb.createSheet(sheetName);
        widths(s, 40, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15);
        int r = 0;
        text(s.createRow(r++), 0, ("CUSTOMER".equals(b.type()) ? "Balance clients" : "Balance fournisseurs")
                + " du " + fr(b.from()) + " au " + fr(b.to()), title);
        r++;
        headers(s, r++, "Tiers", "Solde initial", "Débit", "Crédit", "Solde final", "Avances", "Échu",
                "Non échu", "1-30 j", "31-60 j", "61-90 j", "+90 j");
        for (PartnerBalance x : b.items()) {
            Row row = s.createRow(r++);
            text(row, 0, x.partnerName(), null);
            values(row, 1, false, x.opening(), x.debit(), x.credit(), x.closing(), x.advances(), x.overdue(),
                    x.aging().current(), x.aging().d30(), x.aging().d60(), x.aging().d90(), x.aging().over90());
        }
        PartnerBalanceTotals t = b.totals();
        Row row = s.createRow(r);
        text(row, 0, "Total", bold);
        values(row, 1, true, t.opening(), t.debit(), t.credit(), t.closing(), t.advances(), t.overdue(),
                t.aging().current(), t.aging().d30(), t.aging().d60(), t.aging().d90(), t.aging().over90());
    }

    private void values(Row row, int start, boolean isBold, BigDecimal... v) {
        for (int i = 0; i < v.length; i++) amount(row, start + i, v[i], isBold);
    }

    // Trial balance

    void trialBalance(LedgerDtos.TrialBalance tb, String sheetName) {
        Sheet s = wb.createSheet(sheetName);
        widths(s, 12, 45, 16, 16, 16, 16, 16, 16);
        int r = 0;
        text(s.createRow(r++), 0, "Balance générale du " + fr(tb.from()) + " au " + fr(tb.to()), title);
        r++;
        headers(s, r++, "Compte", "Intitulé", "Ouverture débit", "Ouverture crédit", "Mouvements débit",
                "Mouvements crédit", "Solde débit", "Solde crédit");
        for (LedgerDtos.TrialBalanceRow x : tb.rows()) {
            Row row = s.createRow(r++);
            text(row, 0, x.code(), null);
            text(row, 1, x.name(), null);
            values(row, 2, false, x.openingDebit(), x.openingCredit(), x.periodDebit(), x.periodCredit(),
                    x.closingDebit(), x.closingCredit());
        }
        LedgerDtos.TrialBalanceTotals t = tb.totals();
        Row row = s.createRow(r);
        text(row, 1, "Total", bold);
        values(row, 2, true, t.openingDebit(), t.openingCredit(), t.periodDebit(), t.periodCredit(),
                t.closingDebit(), t.closingCredit());
    }

    // VAT

    void vat(LedgerClosingDtos.VatSummary v, String sheetName) {
        Sheet s = wb.createSheet(sheetName);
        widths(s, 25, 12, 18, 18);
        int r = 0;
        text(s.createRow(r++), 0, "TVA du " + fr(v.from()) + " au " + fr(v.to()) + " (" + v.regime() + ")", title);
        r++;
        r = vatLines(s, r, "TVA collectée", v.collected(), v.totalCollected());
        r++;
        r = vatLines(s, r, "TVA récupérable", v.recoverable(), v.totalRecoverable());
        r++;
        Row a = s.createRow(r++);
        text(a, 0, "Crédit reporté", null);
        amount(a, 3, v.creditCarriedIn(), false);
        Row d = s.createRow(r++);
        text(d, 0, "TVA due", bold);
        amount(d, 3, v.due(), true);
        Row c = s.createRow(r);
        text(c, 0, "Crédit de TVA", bold);
        amount(c, 3, v.credit(), true);
    }

    private int vatLines(Sheet s, int r, String title, java.util.List<LedgerClosingDtos.VatLine> lines, BigDecimal total) {
        headers(s, r++, title, "Taux %", "Base", "TVA");
        for (LedgerClosingDtos.VatLine l : lines) {
            Row row = s.createRow(r++);
            text(row, 0, l.vatCode(), null);
            amount(row, 1, l.rate(), false);
            amount(row, 2, l.base(), false);
            amount(row, 3, l.vat(), false);
        }
        Row t = s.createRow(r++);
        text(t, 0, "Total", bold);
        amount(t, 3, total, true);
        return r;
    }
}
