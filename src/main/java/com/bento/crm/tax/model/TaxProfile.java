package com.bento.crm.tax.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Which declarations a company files: the organization itself (no partner) or one of its clients. */
@Entity
@Table(name = "tax_profile")
@Getter
@Setter
public class TaxProfile extends BaseTenantEntity {

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_regime", nullable = false, length = 10)
    private VatRegime vatRegime = VatRegime.MONTHLY;

    /** Subject to corporate tax (IS); false for a sole trader under IR. */
    @Column(name = "corporate_tax", nullable = false)
    private boolean corporateTax = true;

    @Column(name = "fiscal_year_end_month", nullable = false)
    private int fiscalYearEndMonth = 12;

    @Column(name = "has_employees", nullable = false)
    private boolean hasEmployees = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public enum VatRegime { NONE, MONTHLY, QUARTERLY }
}
