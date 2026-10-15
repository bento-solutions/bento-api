package com.bento.crm.bank.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One statement file read into an account. */
@Entity
@Table(name = "bank_import")
@Getter
@Setter
public class BankImport extends BaseTenantEntity {

    @Column(name = "bank_account_id", nullable = false, columnDefinition = "uuid")
    private UUID bankAccountId;

    @Column(name = "file_id", columnDefinition = "uuid")
    private UUID fileId;

    @Column(name = "inbound_document_id", columnDefinition = "uuid")
    private UUID inboundDocumentId;

    @Column(nullable = false, length = 10)
    private String format;

    @Column(nullable = false, length = 40)
    private String parser;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "opening_balance", precision = 19, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 2)
    private BigDecimal closingBalance;

    @Column(name = "rows_imported", nullable = false)
    private int rowsImported;

    @Column(name = "rows_skipped", nullable = false)
    private int rowsSkipped;

    @Column(name = "balance_mismatch", nullable = false)
    private boolean balanceMismatch;
}
