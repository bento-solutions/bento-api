package com.bento.crm.mission.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Money paid on a client's behalf, billed back at cost once rebillable. */
@Entity
@Table(name = "disbursement")
@Getter
@Setter
public class Disbursement extends BaseTenantEntity {

    @Column(name = "partner_id", nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(name = "mission_id", columnDefinition = "uuid")
    private UUID missionId;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** VAT rebilled on top, as a fraction (0.20). Stamp duty and registry fees carry none. */
    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal vatRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean rebillable = true;

    @Column(name = "invoice_id", columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(name = "receipt_file_id", columnDefinition = "uuid")
    private UUID receiptFileId;
}
