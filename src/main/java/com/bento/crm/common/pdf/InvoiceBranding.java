package com.bento.crm.common.pdf;

import java.awt.Color;

/**
 * How an invoice PDF is dressed: who issues it (legal name and Moroccan identifiers), how to pay,
 * the accent colour, the logo and the footer. Every field is optional; a null branding prints the
 * plain default layout.
 */
public record InvoiceBranding(
        String legalName,
        String address,
        String phone,
        String email,
        String website,
        String ice,
        String ifNumber,
        String rc,
        String patente,
        String cnss,
        String capital,
        String bankName,
        String rib,
        Color accent,
        byte[] logo,
        String paymentTerms,
        String footer) {
}
