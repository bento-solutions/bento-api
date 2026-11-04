package com.bento.crm.common.pdf;

import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.proposal.model.Proposal;
import com.bento.crm.purchaseorder.model.PurchaseOrder;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
@Slf4j
public class PdfDocumentService {

    private static final Color PRIMARY_COLOR = new Color(15, 23, 42); // slate-900
    private static final Color HEADER_BG = new Color(248, 250, 252);   // slate-50
    private static final Color BORDER_COLOR = new Color(226, 232, 240);// slate-200
    private static final Color MUTED_TEXT = new Color(100, 116, 139);  // slate-500

    private static final Font FONT_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, PRIMARY_COLOR);
    private static final Font FONT_SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, PRIMARY_COLOR);
    private static final Font FONT_SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, PRIMARY_COLOR);
    private static final Font FONT_BODY = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private static final Font FONT_BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, PRIMARY_COLOR);
    private static final Font FONT_MUTED = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED_TEXT);
    private static final Font FONT_TABLE_HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, PRIMARY_COLOR);

    public byte[] generateInvoicePdf(Invoice invoice, Partner partner, Organization org) {
        return generateInvoicePdf(invoice, partner, org, null);
    }

    /**
     * The invoice PDF, dressed with the organization's template or the customer's brand: logo,
     * accent colour, the issuer's legal identifiers (ICE, IF, RC, patente, CNSS), a VAT breakdown
     * per rate, the amount in words, bank details and a footer.
     */
    public byte[] generateInvoicePdf(Invoice invoice, Partner partner, Organization org, InvoiceBranding branding) {
        return generateInvoicePdf(invoice, partner, org, branding, null);
    }

    /**
     * With the invoice's VAT breakdown per code (phase 3): one VAT row per rate, the exemption
     * mentions under the totals, and the advances a final invoice deducts.
     */
    public byte[] generateInvoicePdf(Invoice invoice, Partner partner, Organization org, InvoiceBranding branding,
                                     List<com.bento.crm.invoice.dto.InvoiceResponse.VatBreakdownRow> vatRows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String issuer = branding != null && branding.legalName() != null ? branding.legalName()
                    : (org != null && org.getName() != null ? org.getName() : "Bento CRM");
            List<String> footerLines = footerLines(issuer, branding);
            // Leave room at the bottom of every page for the legal footer.
            Document document = new Document(PageSize.A4, 36, 36, 36, 36 + 11 * footerLines.size() + 14);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new LegalFooter(footerLines));
            document.open();

            Color accent = branding != null && branding.accent() != null ? branding.accent() : PRIMARY_COLOR;
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, accent);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, accent);
            Color headerBg = tint(accent);
            String issuerName = branding != null && branding.legalName() != null ? branding.legalName()
                    : (org != null && org.getName() != null ? org.getName() : "Bento CRM");

            // Header: logo and issuer on the left, document meta on the right
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            Image logo = logo(branding);
            if (logo != null) {
                leftCell.addElement(logo);
            }
            leftCell.addElement(new Paragraph(issuerName, logo != null ? FONT_SUBTITLE : titleFont));
            if (branding != null) {
                addLine(leftCell, branding.address(), FONT_BODY);
                addLine(leftCell, join(" · ", prefixed("Tél : ", branding.phone()), branding.email()), FONT_BODY);
                addLine(leftCell, branding.website(), FONT_BODY);
            } else if (org != null && org.getIndustry() != null) {
                leftCell.addElement(new Paragraph(org.getIndustry(), FONT_MUTED));
            }
            headerTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

            boolean vendorDoc = invoice.getType() == Invoice.InvoiceType.VENDOR;
            String title = invoice.getKind() == Invoice.Kind.ADVANCE ? (vendorDoc ? "FACTURE D'ACOMPTE FOURNISSEUR" : "FACTURE D'ACOMPTE")
                    : invoice.getKind() == Invoice.Kind.CREDIT_NOTE ? (vendorDoc ? "AVOIR FOURNISSEUR" : "AVOIR")
                    : vendorDoc ? "FACTURE FOURNISSEUR" : "FACTURE";
            Paragraph docType = new Paragraph(title, titleFont);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);

            String invNum = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId().toString().substring(0, 8);
            addRight(rightCell, "N° " + invNum, FONT_SUBTITLE);
            addRight(rightCell, "Date : " + (invoice.getInvoiceDate() != null ? formatDate(invoice.getInvoiceDate()) : (invoice.getCreatedAt() != null ? formatDate(invoice.getCreatedAt().atZone(java.time.ZoneId.of("Africa/Casablanca")).toLocalDate()) : "-")), FONT_BODY);
            addRight(rightCell, "Échéance : " + formatDate(invoice.getDueDate()), FONT_BODY_BOLD);
            headerTable.addCell(rightCell);
            document.add(headerTable);

            document.add(new Paragraph(" "));

            // Client box
            PdfPTable clientTable = new PdfPTable(1);
            clientTable.setWidthPercentage(100);
            PdfPCell clientCell = new PdfPCell();
            clientCell.setBackgroundColor(headerBg);
            clientCell.setBorderColor(BORDER_COLOR);
            clientCell.setPadding(10);

            clientCell.addElement(new Paragraph(invoice.getType() == Invoice.InvoiceType.VENDOR ? "FOURNISSEUR :" : "FACTURÉ À :", sectionFont));
            String clientName = partner != null && partner.getName() != null ? partner.getName() : (invoice.getCustomerName() != null ? invoice.getCustomerName() : "Client");
            clientCell.addElement(new Paragraph(clientName, FONT_SUBTITLE));
            if (partner != null) {
                if (partner.getCompanyName() != null && !partner.getCompanyName().isBlank()) {
                    clientCell.addElement(new Paragraph(partner.getCompanyName(), FONT_BODY));
                }
                if (invoice.getDeliveryAddress() != null && !invoice.getDeliveryAddress().isBlank()) {
                    clientCell.addElement(new Paragraph(invoice.getDeliveryAddress(), FONT_BODY));
                } else if (partner.getCity() != null) {
                    clientCell.addElement(new Paragraph(partner.getCity() + (partner.getCountry() != null ? ", " + partner.getCountry() : ""), FONT_BODY));
                }
                Object ice = partner.getCompany() != null ? partner.getCompany().get("ice") : null;
                if (ice == null && invoice.getVatNumber() != null && !invoice.getVatNumber().isBlank()) {
                    ice = invoice.getVatNumber();
                }
                if (ice != null) clientCell.addElement(new Paragraph("ICE : " + ice, FONT_BODY));
                if (partner.getEmail() != null) clientCell.addElement(new Paragraph("Email : " + partner.getEmail(), FONT_BODY));
                if (partner.getPhone() != null) clientCell.addElement(new Paragraph("Tél : " + partner.getPhone(), FONT_BODY));
            } else if (invoice.getDeliveryAddress() != null && !invoice.getDeliveryAddress().isBlank()) {
                clientCell.addElement(new Paragraph(invoice.getDeliveryAddress(), FONT_BODY));
            }
            clientTable.addCell(clientCell);
            document.add(clientTable);

            document.add(new Paragraph(" "));

            // Line items, collecting the base per VAT rate for the breakdown
            PdfPTable itemsTable = new PdfPTable(5);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{40, 15, 15, 15, 15});

            addTableHeader(itemsTable, "Désignation", Element.ALIGN_LEFT, headerBg);
            addTableHeader(itemsTable, "Qté", Element.ALIGN_CENTER, headerBg);
            addTableHeader(itemsTable, "Prix Unit. HT (MAD)", Element.ALIGN_RIGHT, headerBg);
            addTableHeader(itemsTable, "TVA", Element.ALIGN_CENTER, headerBg);
            addTableHeader(itemsTable, "Total HT (MAD)", Element.ALIGN_RIGHT, headerBg);

            Map<BigDecimal, BigDecimal> baseByRate = new TreeMap<>();
            List<Map<String, Object>> lines = invoice.getLines();
            if (lines != null && !lines.isEmpty()) {
                for (Map<String, Object> line : lines) {
                    String item = String.valueOf(line.getOrDefault("item", line.getOrDefault("description", "Article")));
                    BigDecimal qty = toBigDecimal(line.get("qty"), BigDecimal.ONE);
                    BigDecimal unitPrice = toBigDecimal(line.get("unitPrice"), BigDecimal.ZERO);
                    BigDecimal vatRate = vatRate(line);
                    BigDecimal lineTotal = qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
                    baseByRate.merge(vatRate, lineTotal, BigDecimal::add);

                    addTableCell(itemsTable, item, Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(itemsTable, qty.stripTrailingZeros().toPlainString(), Element.ALIGN_CENTER, FONT_BODY);
                    addTableCell(itemsTable, formatNumber(unitPrice), Element.ALIGN_RIGHT, FONT_BODY);
                    addTableCell(itemsTable, percent(vatRate), Element.ALIGN_CENTER, FONT_BODY);
                    addTableCell(itemsTable, formatNumber(lineTotal), Element.ALIGN_RIGHT, FONT_BODY);
                }
            } else {
                BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;
                BigDecimal subtotal = invoice.getSubtotal() != null ? invoice.getSubtotal() : total.divide(new BigDecimal("1.20"), 2, RoundingMode.HALF_UP);
                addTableCell(itemsTable, "Prestation de services / Vente de produits", Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(itemsTable, "1", Element.ALIGN_CENTER, FONT_BODY);
                addTableCell(itemsTable, formatNumber(subtotal), Element.ALIGN_RIGHT, FONT_BODY);
                addTableCell(itemsTable, "20%", Element.ALIGN_CENTER, FONT_BODY);
                addTableCell(itemsTable, formatNumber(subtotal), Element.ALIGN_RIGHT, FONT_BODY);
            }
            document.add(itemsTable);

            document.add(new Paragraph(" "));

            BigDecimal subtotal = invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO;
            BigDecimal tax = invoice.getTax() != null ? invoice.getTax() : BigDecimal.ZERO;
            BigDecimal grandTotal = invoice.getTotal() != null ? invoice.getTotal() : subtotal.add(tax);

            // VAT breakdown on the left, totals on the right
            PdfPTable bottom = new PdfPTable(2);
            bottom.setWidthPercentage(100);
            bottom.setWidths(new float[]{55, 45});

            PdfPCell vatCell = new PdfPCell();
            vatCell.setBorder(Rectangle.NO_BORDER);
            List<String> mentions = new java.util.ArrayList<>();
            if (vatRows != null && !vatRows.isEmpty()) {
                PdfPTable vatTable = new PdfPTable(3);
                vatTable.setWidthPercentage(95);
                vatTable.setHorizontalAlignment(Element.ALIGN_LEFT);
                addTableHeader(vatTable, "Taux TVA", Element.ALIGN_LEFT, headerBg);
                addTableHeader(vatTable, "Base HT", Element.ALIGN_RIGHT, headerBg);
                addTableHeader(vatTable, "Montant TVA", Element.ALIGN_RIGHT, headerBg);
                for (var r : vatRows) {
                    addTableCell(vatTable, r.getRate().stripTrailingZeros().toPlainString() + "%", Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(vatTable, formatNumber(r.getBase()), Element.ALIGN_RIGHT, FONT_BODY);
                    addTableCell(vatTable, formatNumber(r.getVat()), Element.ALIGN_RIGHT, FONT_BODY);
                    if (r.getMention() != null && !mentions.contains(r.getMention())) mentions.add(r.getMention());
                }
                vatCell.addElement(vatTable);
            } else if (!baseByRate.isEmpty()) {
                PdfPTable vatTable = new PdfPTable(3);
                vatTable.setWidthPercentage(95);
                vatTable.setHorizontalAlignment(Element.ALIGN_LEFT);
                addTableHeader(vatTable, "Taux TVA", Element.ALIGN_LEFT, headerBg);
                addTableHeader(vatTable, "Base HT", Element.ALIGN_RIGHT, headerBg);
                addTableHeader(vatTable, "Montant TVA", Element.ALIGN_RIGHT, headerBg);
                for (Map.Entry<BigDecimal, BigDecimal> e : baseByRate.entrySet()) {
                    addTableCell(vatTable, percent(e.getKey()), Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(vatTable, formatNumber(e.getValue()), Element.ALIGN_RIGHT, FONT_BODY);
                    addTableCell(vatTable, formatNumber(e.getValue().multiply(e.getKey()).setScale(2, RoundingMode.HALF_UP)), Element.ALIGN_RIGHT, FONT_BODY);
                }
                vatCell.addElement(vatTable);
            }
            bottom.addCell(vatCell);

            PdfPCell totalsCell = new PdfPCell();
            totalsCell.setBorder(Rectangle.NO_BORDER);
            PdfPTable totalsTable = new PdfPTable(2);
            totalsTable.setWidthPercentage(100);
            List<Map<String, Object>> deductions = invoice.getAdvanceDeductions();
            if (deductions != null && !deductions.isEmpty()) {
                BigDecimal linesHt = subtotal;
                for (Map<String, Object> d : deductions) linesHt = linesHt.add(toBigDecimal(d.get("base"), BigDecimal.ZERO));
                addTotalRow(totalsTable, "Total HT :", formatNumber(linesHt) + " MAD", false);
                for (Map<String, Object> d : deductions) {
                    String when = d.get("date") == null ? "" : " du " + formatDate(java.time.LocalDate.parse(d.get("date").toString()));
                    addTotalRow(totalsTable, "Acompte n° " + d.get("number") + when + " HT :",
                            "-" + formatNumber(toBigDecimal(d.get("base"), BigDecimal.ZERO)) + " MAD", false);
                    addTotalRow(totalsTable, "Acompte n° " + d.get("number") + " TVA :",
                            "-" + formatNumber(toBigDecimal(d.get("vat"), BigDecimal.ZERO)) + " MAD", false);
                }
                addTotalRow(totalsTable, "Net HT :", formatNumber(subtotal) + " MAD", false);
                addTotalRow(totalsTable, "Total TVA :", formatNumber(tax) + " MAD", false);
                addTotalRow(totalsTable, "Net à payer TTC :", formatNumber(grandTotal) + " MAD", true);
            } else {
                addTotalRow(totalsTable, "Total HT :", formatNumber(subtotal) + " MAD", false);
                addTotalRow(totalsTable, "Total TVA :", formatNumber(tax) + " MAD", false);
                addTotalRow(totalsTable, "Total TTC :", formatNumber(grandTotal) + " MAD", true);
            }
            totalsCell.addElement(totalsTable);
            bottom.addCell(totalsCell);
            document.add(bottom);
            for (String mention : mentions) {
                document.add(new Paragraph(mention, FONT_BODY));
            }

            document.add(new Paragraph(" "));
            document.add(new Paragraph("Arrêtée la présente facture à la somme de : " + AmountInWords.dirhams(grandTotal) + ".", FONT_BODY_BOLD));

            // Payment terms and bank details
            String terms = branding != null && branding.paymentTerms() != null ? branding.paymentTerms()
                    : "Paiement par virement bancaire ou chèque à l'ordre de " + issuerName + ".";
            Paragraph termsP = new Paragraph("\nConditions de règlement : " + terms, FONT_BODY);
            document.add(termsP);
            if (branding != null && branding.rib() != null) {
                document.add(new Paragraph(join(" · ", prefixed("Banque : ", branding.bankName()), "RIB : " + branding.rib()), FONT_BODY));
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate invoice PDF", e);
            throw new RuntimeException("Error generating invoice PDF", e);
        }
    }

    /** A line's VAT rate as a fraction (0.2), accepting either key and a percentage (20) by mistake. */
    private BigDecimal vatRate(Map<String, Object> line) {
        Object raw = line.get("vatRate") != null ? line.get("vatRate") : line.get("taxRate");
        BigDecimal rate = toBigDecimal(raw, new BigDecimal("0.20"));
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            rate = rate.movePointLeft(2);
        }
        return rate.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static Image logo(InvoiceBranding branding) {
        if (branding == null || branding.logo() == null) {
            return null;
        }
        try {
            Image image = Image.getInstance(branding.logo());
            image.scaleToFit(140, 56);
            return image;
        } catch (Exception e) {
            log.warn("Invoice logo could not be drawn: {}", e.getMessage());
            return null;
        }
    }

    /** A very light version of the accent, for table headers and the client box. */
    private static Color tint(Color c) {
        if (c == PRIMARY_COLOR) {
            return HEADER_BG;
        }
        return new Color(245 + c.getRed() * 10 / 255, 245 + c.getGreen() * 10 / 255, 245 + c.getBlue() * 10 / 255);
    }

    private static void addLine(PdfPCell cell, String text, Font font) {
        if (text != null && !text.isBlank()) {
            cell.addElement(new Paragraph(text, font));
        }
    }

    private static void addRight(PdfPCell cell, String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        cell.addElement(p);
    }

    /**
     * The footer printed at the bottom of every invoice page: the issuer's legal identifiers
     * (each one that is filled in, never invented), its address, then the custom footer text.
     */
    static List<String> footerLines(String issuer, InvoiceBranding b) {
        List<String> lines = new java.util.ArrayList<>();
        if (b != null) {
            String company = join(" · ", issuer + (b.capital() != null && !b.capital().isBlank() ? " au capital de " + b.capital() : ""),
                    prefixed("Siège : ", b.address()));
            String ids = join(" · ", prefixed("ICE : ", b.ice()), prefixed("IF : ", b.ifNumber()), prefixed("RC : ", b.rc()),
                    prefixed("Patente : ", b.patente()), prefixed("CNSS : ", b.cnss()));
            String contact = join(" · ", prefixed("Tél : ", b.phone()), prefixed("Email : ", b.email()), b.website());
            String bank = b.rib() == null || b.rib().isBlank() ? null : join(" · ", prefixed("Banque : ", b.bankName()), "RIB : " + b.rib());
            if (ids != null || contact != null || bank != null || (b.address() != null && !b.address().isBlank())) {
                lines.add(company);
            }
            if (contact != null) lines.add(contact);
            if (ids != null) lines.add(ids);
            if (bank != null) lines.add(bank);
            if (b.footer() != null && !b.footer().isBlank()) {
                for (String l : b.footer().strip().split("\\R")) if (!l.isBlank()) lines.add(l.strip());
            }
        }
        if (lines.isEmpty()) lines.add("Document émis par Bento CRM.");
        return lines.size() > 7 ? lines.subList(0, 7) : lines;
    }

    /** Draws the footer lines and the page number at the bottom of each page. */
    private static final class LegalFooter extends com.lowagie.text.pdf.PdfPageEventHelper {
        private final List<String> lines;

        LegalFooter(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            float centre = (document.left() + document.right()) / 2;
            float y = 36 + 11 * lines.size();
            cb.setColorStroke(new Color(0xDD, 0xDD, 0xDD));
            cb.setLineWidth(0.5f);
            cb.moveTo(document.left(), y + 6);
            cb.lineTo(document.right(), y + 6);
            cb.stroke();
            for (String line : lines) {
                y -= 11;
                com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(line, FONT_MUTED), centre, y, 0);
            }
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), FONT_MUTED), document.right(), 22, 0);
        }
    }

    private static String prefixed(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value;
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(sep);
            sb.append(p);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public byte[] generatePurchaseOrderPdf(PurchaseOrder po, Partner vendor, Organization org) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            // Header
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            leftCell.addElement(new Paragraph(org != null && org.getName() != null ? org.getName() : "Bento CRM", FONT_TITLE));
            if (org != null && org.getIndustry() != null) {
                leftCell.addElement(new Paragraph(org.getIndustry(), FONT_MUTED));
            }
            headerTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);

            Paragraph docType = new Paragraph("BON DE COMMANDE", FONT_TITLE);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);

            String orderNum = po.getOrderNumber() != null ? po.getOrderNumber() : "BC-" + po.getId().toString().substring(0, 8);
            Paragraph numP = new Paragraph("N° " + orderNum, FONT_SUBTITLE);
            numP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(numP);

            Paragraph dateP = new Paragraph("Date : " + formatDate(po.getOrderDate()), FONT_BODY);
            dateP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(dateP);

            Paragraph statP = new Paragraph("Statut : " + po.getStatus(), FONT_MUTED);
            statP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(statP);

            headerTable.addCell(rightCell);
            document.add(headerTable);

            document.add(new Paragraph(" "));

            // Vendor Info
            PdfPTable vendorTable = new PdfPTable(1);
            vendorTable.setWidthPercentage(100);
            PdfPCell vendorCell = new PdfPCell();
            vendorCell.setBackgroundColor(HEADER_BG);
            vendorCell.setBorderColor(BORDER_COLOR);
            vendorCell.setPadding(10);
            vendorCell.addElement(new Paragraph("FOURNISSEUR :", FONT_SECTION));
            vendorCell.addElement(new Paragraph(vendor != null ? vendor.getName() : "Fournisseur", FONT_SUBTITLE));
            if (vendor != null) {
                if (vendor.getCompanyName() != null && !vendor.getCompanyName().isBlank()) {
                    vendorCell.addElement(new Paragraph(vendor.getCompanyName(), FONT_BODY));
                }
                if (vendor.getCity() != null) {
                    vendorCell.addElement(new Paragraph(vendor.getCity() + (vendor.getCountry() != null ? ", " + vendor.getCountry() : ""), FONT_BODY));
                }
                if (vendor.getEmail() != null) vendorCell.addElement(new Paragraph("Email : " + vendor.getEmail(), FONT_BODY));
                if (vendor.getPhone() != null) vendorCell.addElement(new Paragraph("Tél : " + vendor.getPhone(), FONT_BODY));
            }
            vendorTable.addCell(vendorCell);
            document.add(vendorTable);

            document.add(new Paragraph(" "));

            // Items Table
            PdfPTable itemsTable = new PdfPTable(4);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{50, 15, 15, 20});

            addTableHeader(itemsTable, "Article / Prestation", Element.ALIGN_LEFT);
            addTableHeader(itemsTable, "Qté", Element.ALIGN_CENTER);
            addTableHeader(itemsTable, "Prix Unit. (MAD)", Element.ALIGN_RIGHT);
            addTableHeader(itemsTable, "Total (MAD)", Element.ALIGN_RIGHT);

            List<Map<String, Object>> lines = po.getLines();
            if (lines != null && !lines.isEmpty()) {
                for (Map<String, Object> line : lines) {
                    String item = String.valueOf(line.getOrDefault("product", line.getOrDefault("item", "Article")));
                    BigDecimal qty = toBigDecimal(line.get("qty"), BigDecimal.ONE);
                    BigDecimal unitPrice = toBigDecimal(line.getOrDefault("cost", line.get("unitPrice")), BigDecimal.ZERO);
                    BigDecimal lineTotal = qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

                    addTableCell(itemsTable, item, Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(itemsTable, qty.stripTrailingZeros().toPlainString(), Element.ALIGN_CENTER, FONT_BODY);
                    addTableCell(itemsTable, formatNumber(unitPrice), Element.ALIGN_RIGHT, FONT_BODY);
                    addTableCell(itemsTable, formatNumber(lineTotal), Element.ALIGN_RIGHT, FONT_BODY);
                }
            } else {
                BigDecimal total = po.getTotal() != null ? po.getTotal() : (po.getSubtotal() != null ? po.getSubtotal() : BigDecimal.ZERO);
                addTableCell(itemsTable, "Marchandises / Services commandés", Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(itemsTable, "1", Element.ALIGN_CENTER, FONT_BODY);
                addTableCell(itemsTable, formatNumber(total), Element.ALIGN_RIGHT, FONT_BODY);
                addTableCell(itemsTable, formatNumber(total), Element.ALIGN_RIGHT, FONT_BODY);
            }
            document.add(itemsTable);

            document.add(new Paragraph(" "));

            // Totals
            PdfPTable totalsTable = new PdfPTable(2);
            totalsTable.setWidthPercentage(40);
            totalsTable.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalsTable.setWidths(new float[]{50, 50});

            BigDecimal grandTotal = po.getTotal() != null ? po.getTotal() : (po.getSubtotal() != null ? po.getSubtotal() : BigDecimal.ZERO);
            addTotalRow(totalsTable, "Total :", formatNumber(grandTotal) + " MAD", true);
            document.add(totalsTable);

            // Notes / Signatures
            if (po.getNotes() != null && !po.getNotes().isBlank()) {
                document.add(new Paragraph("\nNotes : " + po.getNotes(), FONT_BODY));
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate purchase order PDF", e);
            throw new RuntimeException("Error generating purchase order PDF", e);
        }
    }

    /** What a delivery note PDF shows: no prices, the lines and quantities, and a signature box. */
    public record DeliveryNotePdf(String number, java.time.LocalDate date, String status, String reference,
                                  String deliveryAddress, List<Map<String, Object>> lines, String notes, String signedBy) {}

    /** A delivery note (bon de livraison) in the style of the invoice: company and customer blocks, lines, signature. */
    public byte[] generateDeliveryNotePdf(DeliveryNotePdf note, Partner customer, Organization org) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});
            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            leftCell.addElement(new Paragraph(org != null && org.getName() != null ? org.getName() : "Bento CRM", FONT_TITLE));
            if (org != null && org.getIndustry() != null) {
                leftCell.addElement(new Paragraph(org.getIndustry(), FONT_MUTED));
            }
            headerTable.addCell(leftCell);
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            Paragraph docType = new Paragraph("BON DE LIVRAISON", FONT_TITLE);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);
            Paragraph numP = new Paragraph("N° " + (note.number() != null ? note.number() : "BROUILLON"), FONT_SUBTITLE);
            numP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(numP);
            Paragraph dateP = new Paragraph("Date : " + formatDate(note.date()), FONT_BODY);
            dateP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(dateP);
            if (note.reference() != null && !note.reference().isBlank()) {
                Paragraph refP = new Paragraph("Réf. commande : " + note.reference(), FONT_MUTED);
                refP.setAlignment(Element.ALIGN_RIGHT);
                rightCell.addElement(refP);
            }
            headerTable.addCell(rightCell);
            document.add(headerTable);
            document.add(new Paragraph(" "));

            PdfPTable clientTable = new PdfPTable(1);
            clientTable.setWidthPercentage(100);
            PdfPCell clientCell = new PdfPCell();
            clientCell.setBackgroundColor(HEADER_BG);
            clientCell.setBorderColor(BORDER_COLOR);
            clientCell.setPadding(10);
            clientCell.addElement(new Paragraph("LIVRÉ À :", FONT_SECTION));
            clientCell.addElement(new Paragraph(customer != null && customer.getName() != null ? customer.getName() : "Client",
                    FONT_SUBTITLE));
            if (customer != null) {
                if (customer.getCompanyName() != null && !customer.getCompanyName().isBlank()) {
                    clientCell.addElement(new Paragraph(customer.getCompanyName(), FONT_BODY));
                }
                if (note.deliveryAddress() != null && !note.deliveryAddress().isBlank()) {
                    clientCell.addElement(new Paragraph(note.deliveryAddress(), FONT_BODY));
                } else if (customer.getCity() != null) {
                    clientCell.addElement(new Paragraph(customer.getCity()
                            + (customer.getCountry() != null ? ", " + customer.getCountry() : ""), FONT_BODY));
                }
                Object ice = customer.getCompany() != null ? customer.getCompany().get("ice") : null;
                if (ice != null) clientCell.addElement(new Paragraph("ICE : " + ice, FONT_BODY));
                if (customer.getPhone() != null) clientCell.addElement(new Paragraph("Tél : " + customer.getPhone(), FONT_BODY));
            } else if (note.deliveryAddress() != null && !note.deliveryAddress().isBlank()) {
                clientCell.addElement(new Paragraph(note.deliveryAddress(), FONT_BODY));
            }
            clientTable.addCell(clientCell);
            document.add(clientTable);
            document.add(new Paragraph(" "));

            PdfPTable itemsTable = new PdfPTable(3);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{70, 15, 15});
            addTableHeader(itemsTable, "Désignation", Element.ALIGN_LEFT);
            addTableHeader(itemsTable, "Qté", Element.ALIGN_CENTER);
            addTableHeader(itemsTable, "Unité", Element.ALIGN_CENTER);
            if (note.lines() != null) {
                for (Map<String, Object> line : note.lines()) {
                    if (line == null) continue;
                    Object desc = line.get("description");
                    BigDecimal qty = toBigDecimal(line.get("qty"), BigDecimal.ONE);
                    Object unit = line.get("unit");
                    addTableCell(itemsTable, desc == null ? "" : desc.toString(), Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(itemsTable, qty.stripTrailingZeros().toPlainString(), Element.ALIGN_CENTER, FONT_BODY);
                    addTableCell(itemsTable, unit == null ? "" : unit.toString(), Element.ALIGN_CENTER, FONT_BODY);
                }
            }
            document.add(itemsTable);

            if (note.notes() != null && !note.notes().isBlank()) {
                document.add(new Paragraph("\nNotes : " + note.notes(), FONT_BODY));
            }
            document.add(new Paragraph(" "));

            // Signature boxes: the issuer and the customer, who signs on receipt.
            PdfPTable sign = new PdfPTable(2);
            sign.setWidthPercentage(100);
            sign.setWidths(new float[]{50, 50});
            for (String title : new String[]{"Cachet et signature du livreur", "Reçu par (nom, date, signature)"}) {
                PdfPCell c = new PdfPCell();
                c.setBorderColor(BORDER_COLOR);
                c.setPadding(8);
                c.setMinimumHeight(90);
                c.addElement(new Paragraph(title, FONT_SECTION));
                if (title.startsWith("Reçu") && note.signedBy() != null && !note.signedBy().isBlank()) {
                    c.addElement(new Paragraph(note.signedBy(), FONT_BODY));
                }
                sign.addCell(c);
            }
            document.add(sign);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate delivery note PDF", e);
            throw new RuntimeException("Error generating delivery note PDF", e);
        }
    }

    /**
     * A partner statement (relevé de compte) in the style of the invoice: the issuer and the partner,
     * the period, the opening balance, the movements with the running balance, the closing balance,
     * the open documents with their age and the advances. Meant for the partner: no account codes.
     */
    public byte[] generatePartnerStatementPdf(com.bento.crm.ledger.dto.LedgerStatementDtos.PartnerStatement st,
                                              Organization org, InvoiceBranding branding) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String issuer = branding != null && branding.legalName() != null ? branding.legalName()
                    : (org != null && org.getName() != null ? org.getName() : "Bento CRM");
            List<String> footerLines = footerLines(issuer, branding);
            Document document = new Document(PageSize.A4, 36, 36, 36, 36 + 11 * footerLines.size() + 14);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new LegalFooter(footerLines));
            document.open();

            Color accent = branding != null && branding.accent() != null ? branding.accent() : PRIMARY_COLOR;
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, accent);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, accent);
            Color headerBg = tint(accent);
            boolean customer = "CUSTOMER".equals(st.type());

            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});
            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            Image logo = logo(branding);
            if (logo != null) leftCell.addElement(logo);
            leftCell.addElement(new Paragraph(issuer, logo != null ? FONT_SUBTITLE : titleFont));
            if (branding != null) {
                addLine(leftCell, branding.address(), FONT_BODY);
                addLine(leftCell, join(" · ", prefixed("Tél : ", branding.phone()), branding.email()), FONT_BODY);
                addLine(leftCell, prefixed("ICE : ", branding.ice()), FONT_BODY);
            }
            headerTable.addCell(leftCell);
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            Paragraph docType = new Paragraph("RELEVÉ DE COMPTE", titleFont);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);
            addRight(rightCell, "Du " + formatDate(st.from()) + " au " + formatDate(st.to()), FONT_SUBTITLE);
            addRight(rightCell, "Édité le " + formatDate(java.time.LocalDate.now()), FONT_MUTED);
            headerTable.addCell(rightCell);
            document.add(headerTable);
            document.add(new Paragraph(" "));

            PdfPTable partnerTable = new PdfPTable(1);
            partnerTable.setWidthPercentage(100);
            PdfPCell partnerCell = new PdfPCell();
            partnerCell.setBackgroundColor(headerBg);
            partnerCell.setBorderColor(BORDER_COLOR);
            partnerCell.setPadding(10);
            partnerCell.addElement(new Paragraph(customer ? "CLIENT :" : "FOURNISSEUR :", sectionFont));
            partnerCell.addElement(new Paragraph(st.partner().name() != null ? st.partner().name() : "-", FONT_SUBTITLE));
            addLine(partnerCell, st.partner().address(), FONT_BODY);
            addLine(partnerCell, prefixed("ICE : ", st.partner().ice()), FONT_BODY);
            partnerTable.addCell(partnerCell);
            document.add(partnerTable);
            document.add(new Paragraph(" "));

            PdfPTable lines = new PdfPTable(6);
            lines.setWidthPercentage(100);
            lines.setWidths(new float[]{13, 16, 35, 12, 12, 12});
            addTableHeader(lines, "Date", Element.ALIGN_LEFT, headerBg);
            addTableHeader(lines, "Pièce", Element.ALIGN_LEFT, headerBg);
            addTableHeader(lines, "Libellé", Element.ALIGN_LEFT, headerBg);
            addTableHeader(lines, "Débit", Element.ALIGN_RIGHT, headerBg);
            addTableHeader(lines, "Crédit", Element.ALIGN_RIGHT, headerBg);
            addTableHeader(lines, "Solde", Element.ALIGN_RIGHT, headerBg);
            statementBalanceRow(lines, formatDate(st.from()), "Solde au début de la période", st.opening());
            for (var l : st.lines()) {
                addTableCell(lines, formatDate(l.date()), Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(lines, l.documentNumber() != null ? l.documentNumber() : (l.entryNumber() != null ? l.entryNumber() : ""),
                        Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(lines, l.label() != null ? l.label() : "", Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(lines, l.debit().signum() == 0 ? "" : formatNumber(l.debit()), Element.ALIGN_RIGHT, FONT_BODY);
                addTableCell(lines, l.credit().signum() == 0 ? "" : formatNumber(l.credit()), Element.ALIGN_RIGHT, FONT_BODY);
                addTableCell(lines, formatNumber(l.balance()), Element.ALIGN_RIGHT, FONT_BODY);
            }
            statementBalanceRow(lines, formatDate(st.to()), "Solde à la fin de la période", st.closing());
            document.add(lines);

            PdfPTable total = new PdfPTable(2);
            total.setWidthPercentage(50);
            total.setHorizontalAlignment(Element.ALIGN_RIGHT);
            total.setSpacingBefore(8);
            String label = st.closing().signum() >= 0
                    ? (customer ? "Solde en votre défaveur :" : "Solde en votre faveur :")
                    : (customer ? "Solde en votre faveur :" : "Solde en votre défaveur :");
            addTotalRow(total, label, formatNumber(st.closing().abs()) + " MAD", true);
            document.add(total);

            if (!st.openDocuments().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph(customer ? "FACTURES NON RÉGLÉES" : "FACTURES NON RÉGLÉES (FOURNISSEUR)", sectionFont));
                PdfPTable docs = new PdfPTable(6);
                docs.setWidthPercentage(100);
                docs.setSpacingBefore(4);
                docs.setWidths(new float[]{20, 14, 14, 18, 18, 16});
                addTableHeader(docs, "N°", Element.ALIGN_LEFT, headerBg);
                addTableHeader(docs, "Date", Element.ALIGN_LEFT, headerBg);
                addTableHeader(docs, "Échéance", Element.ALIGN_LEFT, headerBg);
                addTableHeader(docs, "Montant TTC", Element.ALIGN_RIGHT, headerBg);
                addTableHeader(docs, "Reste dû", Element.ALIGN_RIGHT, headerBg);
                addTableHeader(docs, "Retard", Element.ALIGN_RIGHT, headerBg);
                for (var d : st.openDocuments()) {
                    addTableCell(docs, d.number() != null ? d.number() : "-", Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(docs, formatDate(d.date()), Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(docs, formatDate(d.dueDate()), Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(docs, formatNumber(d.total()), Element.ALIGN_RIGHT, FONT_BODY);
                    addTableCell(docs, formatNumber(d.due()), Element.ALIGN_RIGHT, FONT_BODY_BOLD);
                    addTableCell(docs, d.daysOverdue() > 0 ? d.daysOverdue() + " j" : "Non échue", Element.ALIGN_RIGHT, FONT_BODY);
                }
                document.add(docs);
                var a = st.aging();
                PdfPTable ages = new PdfPTable(5);
                ages.setWidthPercentage(100);
                ages.setSpacingBefore(4);
                for (String h : new String[]{"Non échu", "1 - 30 j", "31 - 60 j", "61 - 90 j", "+ 90 j"}) {
                    addTableHeader(ages, h, Element.ALIGN_CENTER, headerBg);
                }
                for (BigDecimal v : new BigDecimal[]{a.current(), a.d30(), a.d60(), a.d90(), a.over90()}) {
                    addTableCell(ages, formatNumber(v), Element.ALIGN_CENTER, FONT_BODY);
                }
                document.add(ages);
            }

            var adv = st.advances();
            if (adv.closing().signum() != 0 || adv.opening().signum() != 0 || !adv.lines().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph(customer ? "AVANCES ET ACOMPTES REÇUS" : "AVANCES ET ACOMPTES VERSÉS", sectionFont));
                PdfPTable advTable = new PdfPTable(4);
                advTable.setWidthPercentage(100);
                advTable.setSpacingBefore(4);
                advTable.setWidths(new float[]{15, 20, 45, 20});
                addTableHeader(advTable, "Date", Element.ALIGN_LEFT, headerBg);
                addTableHeader(advTable, "Pièce", Element.ALIGN_LEFT, headerBg);
                addTableHeader(advTable, "Libellé", Element.ALIGN_LEFT, headerBg);
                addTableHeader(advTable, "Solde", Element.ALIGN_RIGHT, headerBg);
                addTableCell(advTable, formatDate(st.from()), Element.ALIGN_LEFT, FONT_BODY_BOLD);
                addTableCell(advTable, "", Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(advTable, "Solde au début de la période", Element.ALIGN_LEFT, FONT_BODY_BOLD);
                addTableCell(advTable, formatNumber(adv.opening()), Element.ALIGN_RIGHT, FONT_BODY_BOLD);
                for (var l : adv.lines()) {
                    addTableCell(advTable, formatDate(l.date()), Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(advTable, l.documentNumber() != null ? l.documentNumber() : (l.entryNumber() != null ? l.entryNumber() : ""),
                            Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(advTable, l.label() != null ? l.label() : "", Element.ALIGN_LEFT, FONT_BODY);
                    addTableCell(advTable, formatNumber(l.balance()), Element.ALIGN_RIGHT, FONT_BODY);
                }
                addTableCell(advTable, formatDate(st.to()), Element.ALIGN_LEFT, FONT_BODY_BOLD);
                addTableCell(advTable, "", Element.ALIGN_LEFT, FONT_BODY);
                addTableCell(advTable, "Solde à la fin de la période", Element.ALIGN_LEFT, FONT_BODY_BOLD);
                addTableCell(advTable, formatNumber(adv.closing()), Element.ALIGN_RIGHT, FONT_BODY_BOLD);
                document.add(advTable);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate partner statement PDF", e);
            throw new RuntimeException("Error generating partner statement PDF", e);
        }
    }

    /**
     * An expense report to print. Rows are its lines; proofs are the receipts in line order (then the
     * report's own documents), each with its caption and its bytes (null when it cannot be printed).
     */
    public record ExpenseReportPdf(String number, String employeeName, String title, java.time.LocalDate periodFrom,
                                   java.time.LocalDate periodTo, String status, String notes,
                                   java.time.LocalDate reimbursementDate, List<Row> rows, BigDecimal ht, BigDecimal vat,
                                   BigDecimal ttc, List<Proof> proofs) {
        public record Row(java.time.LocalDate date, String category, String description, String supplier, String reference,
                          BigDecimal ht, BigDecimal vat, BigDecimal ttc) {}

        public record Proof(String caption, String fileName, String contentType, byte[] content) {}
    }

    private static final Map<String, String> EXPENSE_CATEGORY_FR = Map.ofEntries(
            Map.entry("TRAVEL", "Déplacements"), Map.entry("FUEL", "Carburant"), Map.entry("MEALS", "Repas"),
            Map.entry("OFFICE", "Fournitures"), Map.entry("RENT", "Loyer"), Map.entry("UTILITIES", "Eau, électricité"),
            Map.entry("TELECOM", "Télécom"), Map.entry("SOFTWARE", "Logiciels"), Map.entry("MARKETING", "Marketing"),
            Map.entry("FEES", "Honoraires"), Map.entry("SALARIES", "Salaires"), Map.entry("TAXES", "Impôts et taxes"),
            Map.entry("OTHER", "Autres"));

    private static final Map<String, String> EXPENSE_REPORT_STATUS_FR = Map.of(
            "DRAFT", "Brouillon", "POSTED", "Comptabilisée", "REIMBURSED", "Remboursée", "CANCELLED", "Annulée");

    /** The legal footer, switched off for the receipt pages appended from other PDFs. */
    private static final class SwitchableFooter extends com.lowagie.text.pdf.PdfPageEventHelper {
        private final LegalFooter footer;
        boolean on = true;

        SwitchableFooter(List<String> lines) {
            this.footer = new LegalFooter(lines);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            if (on) footer.onEndPage(writer, document);
        }
    }

    /**
     * An expense report (note de frais) in the style of the invoice and the statement: the company
     * block, the number, the employee, the period, the title and the status; the table of expenses
     * with its totals, the amount due to the employee and the signature boxes. Then the receipts:
     * images embedded (one per page when upright, two when wide), then the pages of PDF receipts
     * appended, each captioned "Justificatif n° k"; other files are listed by name.
     */
    public byte[] generateExpenseReportPdf(ExpenseReportPdf r, Organization org, InvoiceBranding branding) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String issuer = branding != null && branding.legalName() != null ? branding.legalName()
                    : (org != null && org.getName() != null ? org.getName() : "Bento CRM");
            List<String> footerLines = footerLines(issuer, branding);
            Document document = new Document(PageSize.A4, 36, 36, 36, 36 + 11 * footerLines.size() + 14);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            SwitchableFooter footer = new SwitchableFooter(footerLines);
            writer.setPageEvent(footer);
            document.open();

            Color accent = branding != null && branding.accent() != null ? branding.accent() : PRIMARY_COLOR;
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, accent);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, accent);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);
            Font smallBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, PRIMARY_COLOR);
            Color headerBg = tint(accent);

            // Header: the company, the document.
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});
            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            Image logo = logo(branding);
            if (logo != null) leftCell.addElement(logo);
            leftCell.addElement(new Paragraph(issuer, logo != null ? FONT_SUBTITLE : titleFont));
            if (branding != null) {
                addLine(leftCell, branding.address(), FONT_BODY);
                addLine(leftCell, join(" · ", prefixed("Tél : ", branding.phone()), branding.email()), FONT_BODY);
                addLine(leftCell, prefixed("ICE : ", branding.ice()), FONT_BODY);
            }
            headerTable.addCell(leftCell);
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            Paragraph docType = new Paragraph("NOTE DE FRAIS", titleFont);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);
            addRight(rightCell, "N° " + (r.number() != null ? r.number() : "BROUILLON"), FONT_SUBTITLE);
            addRight(rightCell, "Statut : " + EXPENSE_REPORT_STATUS_FR.getOrDefault(r.status(), r.status()), FONT_BODY);
            addRight(rightCell, "Édité le " + formatDate(java.time.LocalDate.now()), FONT_MUTED);
            headerTable.addCell(rightCell);
            document.add(headerTable);
            document.add(new Paragraph(" "));

            // The employee, the period, the title.
            PdfPTable who = new PdfPTable(1);
            who.setWidthPercentage(100);
            PdfPCell whoCell = new PdfPCell();
            whoCell.setBackgroundColor(headerBg);
            whoCell.setBorderColor(BORDER_COLOR);
            whoCell.setPadding(10);
            whoCell.addElement(new Paragraph("EMPLOYÉ :", sectionFont));
            whoCell.addElement(new Paragraph(r.employeeName() != null ? r.employeeName() : "-", FONT_SUBTITLE));
            if (r.periodFrom() != null || r.periodTo() != null) {
                addLine(whoCell, "Période : du " + formatDate(r.periodFrom()) + " au " + formatDate(r.periodTo()), FONT_BODY);
            }
            addLine(whoCell, prefixed("Objet : ", r.title()), FONT_BODY);
            who.addCell(whoCell);
            document.add(who);
            document.add(new Paragraph(" "));

            // The expenses.
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{10, 12, 22, 14, 10, 11, 9, 12});
            table.setHeaderRows(1);
            for (String[] h : new String[][]{{"Date", "L"}, {"Catégorie", "L"}, {"Description", "L"}, {"Fournisseur", "L"},
                    {"Réf.", "L"}, {"HT", "R"}, {"TVA", "R"}, {"TTC", "R"}}) {
                addTableHeader(table, h[0], h[1].equals("R") ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT, headerBg);
            }
            for (ExpenseReportPdf.Row row : r.rows()) {
                addTableCell(table, formatDate(row.date()), Element.ALIGN_LEFT, small);
                addTableCell(table, row.category() == null ? "" : EXPENSE_CATEGORY_FR.getOrDefault(row.category(), row.category()),
                        Element.ALIGN_LEFT, small);
                addTableCell(table, row.description() == null ? "" : row.description(), Element.ALIGN_LEFT, small);
                addTableCell(table, row.supplier() == null ? "" : row.supplier(), Element.ALIGN_LEFT, small);
                addTableCell(table, row.reference() == null ? "" : row.reference(), Element.ALIGN_LEFT, small);
                addTableCell(table, formatNumber(row.ht()), Element.ALIGN_RIGHT, small);
                addTableCell(table, formatNumber(row.vat()), Element.ALIGN_RIGHT, small);
                addTableCell(table, formatNumber(row.ttc()), Element.ALIGN_RIGHT, small);
            }
            PdfPCell totalLabel = new PdfPCell(new Phrase("Total", smallBold));
            totalLabel.setColspan(5);
            totalLabel.setBorderColor(BORDER_COLOR);
            totalLabel.setBackgroundColor(headerBg);
            totalLabel.setPadding(6);
            table.addCell(totalLabel);
            for (BigDecimal v : new BigDecimal[]{r.ht(), r.vat(), r.ttc()}) {
                PdfPCell c = new PdfPCell(new Phrase(formatNumber(v), smallBold));
                c.setBorderColor(BORDER_COLOR);
                c.setBackgroundColor(headerBg);
                c.setPadding(6);
                c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(c);
            }
            document.add(table);

            PdfPTable due = new PdfPTable(2);
            due.setWidthPercentage(55);
            due.setHorizontalAlignment(Element.ALIGN_RIGHT);
            due.setSpacingBefore(8);
            addTotalRow(due, "Montant dû à l'employé :", formatNumber(r.ttc()) + " MAD", true);
            if (r.reimbursementDate() != null && "REIMBURSED".equals(r.status())) {
                addTotalRow(due, "Remboursé le :", formatDate(r.reimbursementDate()), false);
            }
            document.add(due);

            if (r.notes() != null && !r.notes().isBlank()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("NOTES", sectionFont));
                document.add(new Paragraph(r.notes(), FONT_BODY));
            }

            // Signatures.
            PdfPTable signatures = new PdfPTable(2);
            signatures.setWidthPercentage(100);
            signatures.setSpacingBefore(18);
            for (String label : new String[]{"Signature de l'employé", "Approuvé par"}) {
                PdfPCell c = new PdfPCell();
                c.setBorderColor(BORDER_COLOR);
                c.setPadding(8);
                c.setMinimumHeight(80);
                c.addElement(new Paragraph(label, FONT_SECTION));
                c.addElement(new Paragraph("Nom, date et signature", FONT_MUTED));
                signatures.addCell(c);
            }
            document.add(signatures);

            // Sort the receipts: images, PDFs (read up front so a broken one is listed instead), the rest.
            record Pdf(ExpenseReportPdf.Proof proof, com.lowagie.text.pdf.PdfReader reader) {}
            List<ExpenseReportPdf.Proof> images = new java.util.ArrayList<>();
            List<Image> imageObjects = new java.util.ArrayList<>();
            List<Pdf> pdfs = new java.util.ArrayList<>();
            List<ExpenseReportPdf.Proof> others = new java.util.ArrayList<>();
            for (ExpenseReportPdf.Proof p : r.proofs()) {
                String type = p.contentType() == null ? "" : p.contentType();
                if (p.content() != null && type.startsWith("image/")) {
                    try {
                        imageObjects.add(Image.getInstance(p.content()));
                        images.add(p);
                        continue;
                    } catch (Exception e) {
                        log.info("Receipt image {} cannot be embedded: {}", p.fileName(), e.getMessage());
                    }
                } else if (p.content() != null && type.equals("application/pdf")) {
                    try {
                        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(p.content());
                        if (reader.getNumberOfPages() > 0) {
                            pdfs.add(new Pdf(p, reader));
                            continue;
                        }
                    } catch (Exception e) {
                        log.info("Receipt PDF {} cannot be appended: {}", p.fileName(), e.getMessage());
                    }
                }
                others.add(p);
            }
            if (!r.proofs().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("JUSTIFICATIFS (" + r.proofs().size() + ")", sectionFont));
                if (!others.isEmpty()) {
                    document.add(new Paragraph("Non reproduits dans ce document :", FONT_MUTED));
                    for (ExpenseReportPdf.Proof p : others) {
                        document.add(new Paragraph(p.caption() + " - fichier " + p.fileName(), FONT_BODY));
                    }
                }
            }

            // Images: an upright one alone on its page, wide ones two per page.
            float width = document.right() - document.left();
            float height = document.top() - document.bottom();
            int used = 2;
            for (int i = 0; i < images.size(); i++) {
                Image image = imageObjects.get(i);
                boolean upright = image.getHeight() > image.getWidth();
                if (upright || used >= 2) {
                    document.newPage();
                    used = 0;
                }
                float slot = upright ? height : height / 2;
                Paragraph caption = new Paragraph(images.get(i).caption(), sectionFont);
                caption.setSpacingAfter(4);
                document.add(caption);
                image.scaleToFit(width, slot - 40);
                image.setAlignment(Image.ALIGN_CENTER);
                document.add(image);
                used += upright ? 2 : 1;
            }

            // PDF receipts: each page scaled into a band under its caption.
            for (Pdf p : pdfs) {
                int pages = p.reader().getNumberOfPages();
                for (int i = 1; i <= pages; i++) {
                    Rectangle size = p.reader().getPageSizeWithRotation(i);
                    document.setPageSize(size);
                    document.newPage();
                    footer.on = false;
                    float w = size.getWidth();
                    float h = size.getHeight();
                    float s = 0.92f;
                    float dx = w * (1 - s) / 2;
                    float dy = h * 0.02f;
                    com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
                    com.lowagie.text.pdf.PdfImportedPage page = writer.getImportedPage(p.reader(), i);
                    switch (p.reader().getPageRotation(i)) {
                        case 90 -> cb.addTemplate(page, 0, -s, s, 0, dx, dy + s * h);
                        case 180 -> cb.addTemplate(page, -s, 0, 0, -s, dx + s * w, dy + s * h);
                        case 270 -> cb.addTemplate(page, 0, s, -s, 0, dx + s * w, dy);
                        default -> cb.addTemplate(page, s, 0, 0, s, dx, dy);
                    }
                    String caption = p.proof().caption() + (pages > 1 ? " (page " + i + "/" + pages + ")" : "");
                    com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(caption, sectionFont),
                            dx, h - h * 0.035f, 0);
                }
            }

            document.close();
            for (Pdf p : pdfs) p.reader().close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate expense report PDF", e);
            throw new RuntimeException("Error generating expense report PDF", e);
        }
    }

    /** dd/MM/yyyy, for captions built outside this class. */
    public static String formatDateFr(java.time.LocalDate date) {
        return formatDate(date);
    }

    /** 1 234,50 (no-break space grouping), for captions built outside this class. */
    public static String formatAmountFr(BigDecimal value) {
        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new java.text.DecimalFormat("#,##0.00", symbols).format(value == null ? BigDecimal.ZERO : value);
    }

    private void statementBalanceRow(PdfPTable table, String date, String label, BigDecimal balance) {
        addTableCell(table, date, Element.ALIGN_LEFT, FONT_BODY_BOLD);
        addTableCell(table, "", Element.ALIGN_LEFT, FONT_BODY);
        addTableCell(table, label, Element.ALIGN_LEFT, FONT_BODY_BOLD);
        addTableCell(table, "", Element.ALIGN_RIGHT, FONT_BODY);
        addTableCell(table, "", Element.ALIGN_RIGHT, FONT_BODY);
        addTableCell(table, formatNumber(balance), Element.ALIGN_RIGHT, FONT_BODY_BOLD);
    }

    public byte[] generateProposalPdf(Proposal proposal, Partner client, Organization org) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            // Header
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            leftCell.addElement(new Paragraph(org != null && org.getName() != null ? org.getName() : "Bento CRM", FONT_TITLE));
            headerTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);

            Paragraph docType = new Paragraph("DEVIS / PROPOSITION", FONT_TITLE);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);

            Paragraph numP = new Paragraph("Réf : " + proposal.getId().toString().substring(0, 8).toUpperCase(), FONT_SUBTITLE);
            numP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(numP);

            headerTable.addCell(rightCell);
            document.add(headerTable);

            document.add(new Paragraph(" "));

            // Client Info
            PdfPTable clientTable = new PdfPTable(1);
            clientTable.setWidthPercentage(100);
            PdfPCell clientCell = new PdfPCell();
            clientCell.setBackgroundColor(HEADER_BG);
            clientCell.setBorderColor(BORDER_COLOR);
            clientCell.setPadding(10);
            clientCell.addElement(new Paragraph("PROPOSITION DESTINÉE À :", FONT_SECTION));
            clientCell.addElement(new Paragraph(client != null ? client.getName() : "Client", FONT_SUBTITLE));
            clientTable.addCell(clientCell);
            document.add(clientTable);

            document.add(new Paragraph(" "));

            // Proposal Content
            Paragraph titleP = new Paragraph("Objet : " + (proposal.getTitle() != null ? proposal.getTitle() : "Proposition commerciale"), FONT_SUBTITLE);
            document.add(titleP);

            document.add(new Paragraph(" "));

            PdfPTable valTable = new PdfPTable(2);
            valTable.setWidthPercentage(50);
            valTable.setHorizontalAlignment(Element.ALIGN_LEFT);
            BigDecimal oppVal = proposal.getOpportunityValue() != null ? proposal.getOpportunityValue() : BigDecimal.ZERO;
            addTotalRow(valTable, "Montant de l'offre :", formatNumber(oppVal) + " MAD", true);
            document.add(valTable);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate proposal PDF", e);
            throw new RuntimeException("Error generating proposal PDF", e);
        }
    }

    private void addTableHeader(PdfPTable table, String text, int align) {
        addTableHeader(table, text, align, HEADER_BG);
    }

    private void addTableHeader(PdfPTable table, String text, int align, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_TABLE_HEADER));
        cell.setBackgroundColor(background);
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private void addTableCell(PdfPTable table, String text, int align, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private void addTotalRow(PdfPTable table, String label, String value, boolean isBold) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, isBold ? FONT_BODY_BOLD : FONT_BODY));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPadding(4);
        table.addCell(labelCell);

        PdfPCell valCell = new PdfPCell(new Phrase(value, isBold ? FONT_BODY_BOLD : FONT_BODY));
        valCell.setBorder(Rectangle.NO_BORDER);
        valCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valCell.setPadding(4);
        table.addCell(valCell);
    }

    private BigDecimal toBigDecimal(Object val, BigDecimal def) {
        if (val == null) return def;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private String formatNumber(BigDecimal val) {
        if (val == null) return "0,00";
        // French grouping uses a narrow no-break space the PDF base fonts cannot draw, which turned
        // 9 500,00 into 9500,00; a plain no-break space renders and keeps the amount on one line.
        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(Locale.FRANCE);
        symbols.setGroupingSeparator('\u00A0');
        java.text.DecimalFormat nf = new java.text.DecimalFormat("#,##0.00", symbols);
        return nf.format(val);
    }

    private static final java.time.format.DateTimeFormatter DATE_FR = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static String formatDate(java.time.LocalDate date) {
        return date != null ? date.format(DATE_FR) : "-";
    }
}
