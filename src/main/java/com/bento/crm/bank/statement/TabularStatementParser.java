package com.bento.crm.bank.statement;

import com.bento.crm.bank.model.BankAccount;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads CSV and Excel statement exports. Moroccan banks (Attijariwafa, Bank of Africa, CIH,
 * Banque Populaire and others) all export a table with a date, a label, and either debit and
 * credit columns or one signed amount, usually below a few lines naming the account. Rather than
 * one fixed layout per bank, the header row is found by its column names (French or English),
 * which also copes with the small changes banks make to their exports over time.
 */
public final class TabularStatementParser {

    private TabularStatementParser() {}

    private static final int MAX_ROWS = 20000;
    private static final Pattern OPENING = Pattern.compile("solde (initial|precedent|d'ouverture|de debut|debut|anterieur)|ancien solde|opening balance");
    private static final Pattern CLOSING = Pattern.compile("solde (final|de cloture|fin|au \\d)|nouveau solde|closing balance");

    static final List<String> DATE = List.of("date operation", "date d'operation", "date oper", "date ope", "date comptable", "date compta", "date de l'operation", "booking date", "transaction date", "date");
    static final List<String> VALUE_DATE = List.of("date valeur", "date de valeur", "valeur", "value date");
    static final List<String> LABEL = List.of("libelle", "libelle operation", "libelle de l'operation", "designation", "description", "nature de l'operation", "nature", "operation", "motif", "details", "label");
    static final List<String> REFERENCE = List.of("reference", "ref", "ref.", "n° piece", "piece", "numero", "cheque");
    static final List<String> DEBIT = List.of("debit", "debits", "montant debit", "debit (mad)", "debit mad", "sortie", "retrait", "withdrawal");
    static final List<String> CREDIT = List.of("credit", "credits", "montant credit", "credit (mad)", "credit mad", "entree", "versement", "deposit");
    static final List<String> AMOUNT = List.of("montant", "montant (mad)", "amount", "montant mad");
    static final List<String> BALANCE = List.of("solde", "solde (mad)", "balance", "solde mad");

    public static ParsedStatement csv(byte[] bytes) {
        return table(splitCsv(decode(bytes)), "csv");
    }

    public static ParsedStatement excel(byte[] bytes) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter fmt = new DataFormatter(java.util.Locale.FRANCE);
            List<List<String>> rows = new ArrayList<>();
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                if (rows.size() >= MAX_ROWS) break;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(r.getLastCellNum(), 0); c++) {
                    Cell cell = r.getCell(c);
                    if (cell == null) {
                        cells.add("");
                    } else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    } else if (cell.getCellType() == CellType.NUMERIC
                            || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
                        cells.add(BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString());
                    } else {
                        cells.add(fmt.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return table(rows, "excel");
        }
    }

    /** Finds the header, maps its columns, and reads every dated row below it. */
    static ParsedStatement table(List<List<String>> rows, String format) {
        int header = -1;
        int[] cols = null;
        for (int i = 0; i < Math.min(rows.size(), 60) && header < 0; i++) {
            int[] c = columns(rows.get(i));
            if (c[0] >= 0 && (c[2] >= 0) && (c[4] >= 0 || c[5] >= 0 || c[6] >= 0)) {
                header = i;
                cols = c;
            }
        }
        if (header < 0) {
            throw new IllegalArgumentException("No table with a date, a label and amounts was found in this file");
        }
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < header; i++) top.append(String.join(" ", rows.get(i))).append('\n');
        String account = StatementText.accountNumber(top.toString());
        BankAccount.Bank bank = StatementText.bank(top.toString(), account);

        BigDecimal opening = null, closing = null;
        List<ParsedStatement.Row> out = new ArrayList<>();
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String label = cell(r, cols[2]).replaceAll("\\s+", " ").trim();
            LocalDate date = StatementText.date(cell(r, cols[0]));
            BigDecimal debit = StatementText.amount(cell(r, cols[4]));
            BigDecimal credit = StatementText.amount(cell(r, cols[5]));
            BigDecimal signed = StatementText.amount(cell(r, cols[6]));
            BigDecimal balance = StatementText.amount(cell(r, cols[7]));
            String n = StatementText.norm(label + " " + String.join(" ", r));
            if (OPENING.matcher(n).find()) {
                opening = balance != null ? balance : net(debit, credit, signed);
                continue;
            }
            if (CLOSING.matcher(n).find()) {
                closing = balance != null ? balance : net(debit, credit, signed);
                continue;
            }
            BigDecimal amount = net(debit, credit, signed);
            if (date == null) {
                // A label running over two lines: the second has no date and no amount.
                if (amount == null && !label.isEmpty() && !out.isEmpty()) {
                    ParsedStatement.Row prev = out.remove(out.size() - 1);
                    out.add(new ParsedStatement.Row(prev.bookingDate(), prev.valueDate(), trim(prev.description() + " " + label),
                            prev.reference(), prev.amount(), prev.balance()));
                }
                continue;
            }
            if (amount == null || amount.signum() == 0) continue;
            out.add(new ParsedStatement.Row(date, StatementText.date(cell(r, cols[1])), trim(label.isEmpty() ? "-" : label),
                    emptyToNull(cell(r, cols[3])), amount, balance));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The file has no movements");
        }
        LocalDate start = out.stream().map(ParsedStatement.Row::bookingDate).min(LocalDate::compareTo).orElse(null);
        LocalDate end = out.stream().map(ParsedStatement.Row::bookingDate).max(LocalDate::compareTo).orElse(null);
        return new ParsedStatement(bank, account, start, end, opening, closing, out,
                format + (bank == null ? "" : ":" + bank.name().toLowerCase()));
    }

    /** Debit is money out whatever its printed sign; credit is money in. */
    private static BigDecimal net(BigDecimal debit, BigDecimal credit, BigDecimal signed) {
        if (debit != null || credit != null) {
            BigDecimal d = debit == null ? BigDecimal.ZERO : debit.abs();
            BigDecimal c = credit == null ? BigDecimal.ZERO : credit.abs();
            return c.subtract(d);
        }
        return signed;
    }

    /** Column positions: date, value date, label, reference, debit, credit, amount, balance (-1 when absent). */
    static int[] columns(List<String> header) {
        int[] c = {-1, -1, -1, -1, -1, -1, -1, -1};
        List<List<String>> names = List.of(VALUE_DATE, DATE, LABEL, REFERENCE, DEBIT, CREDIT, AMOUNT, BALANCE);
        int[] slot = {1, 0, 2, 3, 4, 5, 6, 7};
        // Exact names first, then names that start with an alias ("Débit (MAD)"), never reusing a column.
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < names.size(); k++) {
                if (c[slot[k]] >= 0) continue;
                for (int i = 0; i < header.size(); i++) {
                    if (used(c, i)) continue;
                    String h = StatementText.norm(header.get(i)).replaceAll("[:*]", "").trim();
                    if (h.isEmpty()) continue;
                    String hh = h;
                    boolean hit = pass == 0 ? names.get(k).contains(h)
                            : names.get(k).stream().anyMatch(a -> hh.startsWith(a + " ") || hh.startsWith(a + "("));
                    if (hit) {
                        c[slot[k]] = i;
                        break;
                    }
                }
            }
        }
        return c;
    }

    private static boolean used(int[] c, int i) {
        for (int v : c) if (v == i) return true;
        return false;
    }

    private static String cell(List<String> r, int i) {
        return i < 0 || i >= r.size() || r.get(i) == null ? "" : r.get(i).trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : trim(s);
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    static String decode(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer buf = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString();
        } catch (CharacterCodingException e) {
            // Excel on Windows saves CSV in Windows-1252.
            return new String(bytes, start, bytes.length - start, Charset.forName("windows-1252"));
        }
    }

    /** Splits CSV text, guessing the separator (; , or tab) and honouring quotes. */
    static List<List<String>> splitCsv(String text) {
        String[] lines = text.split("\r\n|\n|\r", -1);
        char sep = ';';
        int best = -1;
        for (char cand : new char[]{';', '\t', ','}) {
            int count = 0;
            for (int i = 0; i < Math.min(lines.length, 30); i++) count += lines[i].chars().filter(ch -> ch == cand).count();
            if (count > best) {
                best = count;
                sep = cand;
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            if (rows.size() >= MAX_ROWS) break;
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (ch == sep && !quoted) {
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            cells.add(cur.toString());
            rows.add(cells);
        }
        return rows;
    }
}
