package com.bento.crm.invoice.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceTemplateRequest {

    @Size(max = 255)
    private String legalName;

    @Size(max = 1000)
    private String address;

    @Size(max = 50)
    private String phone;

    @Size(max = 255)
    private String email;

    @Size(max = 255)
    private String website;

    /** Identifiant Commun de l'Entreprise: 15 digits. */
    @Pattern(regexp = "^$|^\\d{15}$", message = "ICE must be 15 digits")
    private String ice;

    @Size(max = 30)
    private String ifNumber;

    @Size(max = 50)
    private String rc;

    @Size(max = 30)
    private String patente;

    @Size(max = 30)
    private String cnss;

    @Size(max = 50)
    private String capital;

    @Size(max = 100)
    private String bankName;

    /** Moroccan RIB: 24 digits, spaces allowed. */
    @Pattern(regexp = "^$|^[\\d ]{24,30}$", message = "RIB must be 24 digits")
    private String rib;

    @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "Accent colour must look like #1F2937")
    private String accentColor;

    private UUID logoFileId;

    @Size(max = 2000)
    private String paymentTermsText;

    @Size(max = 2000)
    private String footerText;
}
