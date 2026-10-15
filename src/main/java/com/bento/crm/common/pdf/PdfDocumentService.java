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
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
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

            Paragraph docType = new Paragraph(invoice.getType() == Invoice.InvoiceType.VENDOR ? "FACTURE FOURNISSEUR" : "FACTURE", titleFont);
            docType.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(docType);

            String invNum = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId().toString().substring(0, 8);
            addRight(rightCell, "N° " + invNum, FONT_SUBTITLE);
            addRight(rightCell, "Date : " + (invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : (invoice.getCreatedAt() != null ? invoice.getCreatedAt().toString().substring(0, 10) : "-")), FONT_BODY);
            addRight(rightCell, "Échéance : " + (invoice.getDueDate() != null ? invoice.getDueDate() : "-"), FONT_BODY_BOLD);
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
            if (!baseByRate.isEmpty()) {
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
            addTotalRow(totalsTable, "Total HT :", formatNumber(subtotal) + " MAD", false);
            addTotalRow(totalsTable, "Total TVA :", formatNumber(tax) + " MAD", false);
            addTotalRow(totalsTable, "Total TTC :", formatNumber(grandTotal) + " MAD", true);
            totalsCell.addElement(totalsTable);
            bottom.addCell(totalsCell);
            document.add(bottom);

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

            // Footer: the legal identifiers Moroccan invoices must carry, then the custom footer
            String legal = branding == null ? null : join(" · ",
                    issuerName + (branding.capital() != null ? " au capital de " + branding.capital() : ""),
                    prefixed("ICE : ", branding.ice()), prefixed("IF : ", branding.ifNumber()),
                    prefixed("RC : ", branding.rc()), prefixed("Patente : ", branding.patente()),
                    prefixed("CNSS : ", branding.cnss()));
            StringBuilder footerText = new StringBuilder("\n\n");
            if (legal != null && branding.ice() != null) {
                footerText.append(legal).append('\n');
            }
            if (branding != null && branding.footer() != null) {
                footerText.append(branding.footer());
            } else if (branding == null || branding.ice() == null) {
                footerText.append("Document émis par Bento CRM.");
            }
            Paragraph footer = new Paragraph(footerText.toString(), FONT_MUTED);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

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

            Paragraph dateP = new Paragraph("Date : " + (po.getOrderDate() != null ? po.getOrderDate() : "-"), FONT_BODY);
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
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRANCE);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(val);
    }
}
