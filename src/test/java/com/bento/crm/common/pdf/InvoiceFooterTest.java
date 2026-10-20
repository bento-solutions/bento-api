package com.bento.crm.common.pdf;

import com.bento.crm.invoice.model.Invoice;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceFooterTest {

    private final PdfDocumentService pdf = new PdfDocumentService();

    private static Invoice invoice() {
        Invoice i = new Invoice();
        i.setId(UUID.randomUUID());
        i.setInvoiceNumber("FA-2026-0042");
        i.setDueDate(LocalDate.of(2026, 11, 30));
        return i;
    }

    private static String page1(byte[] bytes) throws Exception {
        return new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(1);
    }

    @Test
    void legalIdentifiersAreInTheFooterOfEveryInvoice() throws Exception {
        InvoiceBranding b = new InvoiceBranding("Atlas Conseil SARL", "12 rue Ibn Batouta, Casablanca", "+212 522 00 00 00", null, null,
                "001234567000089", "1234567", "Casablanca 98765", null, null, "100 000 MAD", "Attijariwafa Bank",
                "007 780 0001234567890123 45", null, null, null, "Merci pour votre confiance");
        String text = page1(pdf.generateInvoicePdf(invoice(), null, null, b));
        assertThat(text).contains("ICE : 001234567000089", "IF : 1234567", "RC : Casablanca 98765",
                "au capital de 100 000 MAD", "Siège : 12 rue Ibn Batouta", "Tél : +212 522 00 00 00",
                "Banque : Attijariwafa Bank · RIB : 007 780 0001234567890123 45", "Merci pour votre confiance", "Page 1");
    }

    @Test
    void identifiersPrintEvenWithoutAnIce() throws Exception {
        InvoiceBranding b = new InvoiceBranding("Atlas Conseil SARL", null, null, null, null,
                null, "1234567", null, null, null, null, null, null, null, null, null, null);
        assertThat(page1(pdf.generateInvoicePdf(invoice(), null, null, b))).contains("IF : 1234567");
    }

    @Test
    void nothingIsInventedWhenNothingIsFilledIn() throws Exception {
        String text = page1(pdf.generateInvoicePdf(invoice(), null, null, null));
        assertThat(text).contains("Document émis par Bento CRM.").doesNotContain("ICE :", "RIB :");
    }
}
