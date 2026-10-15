package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * What an organization prints on its invoices: the Moroccan legal identifiers (ICE, IF, RC,
 * patente, CNSS), contact and bank details, an accent colour, a logo and a footer. One row per
 * organization; a brand can override the logo and footer.
 */
@Entity
@Table(name = "invoice_template")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceTemplate extends BaseTenantEntity {

    private String legalName;

    @Column(columnDefinition = "text")
    private String address;

    private String phone;

    private String email;

    private String website;

    private String ice;

    private String ifNumber;

    private String rc;

    private String patente;

    private String cnss;

    private String capital;

    private String bankName;

    private String rib;

    /** #RRGGBB, used for the document title and table headers. */
    private String accentColor;

    /** A file uploaded against the organization, so it is served publicly like the org logo. */
    @Column(columnDefinition = "uuid")
    private UUID logoFileId;

    @Column(columnDefinition = "text")
    private String paymentTermsText;

    @Column(columnDefinition = "text")
    private String footerText;
}
