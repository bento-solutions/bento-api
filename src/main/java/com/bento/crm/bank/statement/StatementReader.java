package com.bento.crm.bank.statement;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/** Picks the reader for a statement file: CSV and Excel are read directly, PDF and photos by the AI. */
@Component
@RequiredArgsConstructor
public class StatementReader {

    private final PdfStatementReader pdf;

    public enum Format { CSV, EXCEL, PDF, IMAGE }

    public static Format format(String contentType, String fileName) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (type.equals("application/pdf") || name.endsWith(".pdf")) return Format.PDF;
        if (type.startsWith("image/")) return Format.IMAGE;
        if (type.contains("spreadsheet") || type.contains("excel") || name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return Format.EXCEL;
        }
        if (type.startsWith("text/") || name.endsWith(".csv") || name.endsWith(".txt")) return Format.CSV;
        throw new IllegalArgumentException("Send a CSV, an Excel file, a PDF or a picture of the statement");
    }

    /** True when this file needs the AI to be read. */
    public static boolean needsAi(Format f) {
        return f == Format.PDF || f == Format.IMAGE;
    }

    public boolean aiAvailable() {
        return pdf.available();
    }

    /** Throws IllegalArgumentException when the file cannot be read as a statement. */
    public ParsedStatement read(byte[] content, String contentType, String fileName) {
        Format f = format(contentType, fileName);
        return switch (f) {
            case CSV -> TabularStatementParser.csv(content);
            case EXCEL -> {
                try {
                    yield TabularStatementParser.excel(content);
                } catch (IOException | RuntimeException e) {
                    if (e instanceof IllegalArgumentException iae) throw iae;
                    throw new IllegalArgumentException("This Excel file could not be opened");
                }
            }
            case PDF, IMAGE -> {
                if (!pdf.available()) {
                    throw new IllegalStateException("AI reading is not set up: export the statement as CSV or Excel instead");
                }
                yield pdf.read(content, f == Format.PDF ? "application/pdf" : contentType);
            }
        };
    }
}
