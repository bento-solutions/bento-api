package com.bento.crm.ledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** GL phase 4: financial statements (bilan, CPC), partner statements and balances. */
public final class LedgerStatementDtos {

    private LedgerStatementDtos() {}

    // 1. Bilan

    /**
     * One rubric of the bilan. {@code accountFrom}/{@code accountTo} are the account-code prefixes the
     * row covers, for the general ledger filters. On the passif side {@code gross} equals {@code net}
     * and {@code amortProv} is 0.
     */
    public record BilanRow(String code, String label, String accountFrom, String accountTo, BigDecimal gross,
                           BigDecimal amortProv, BigDecimal net, BigDecimal netPrior) {}

    public record BilanSection(String key, String label, List<BilanRow> rows, BigDecimal totalGross,
                               BigDecimal totalAmortProv, BigDecimal totalNet, BigDecimal totalNetPrior) {}

    /** {@code balance} is signed by the side convention of its rubric (negative: the unusual sign). */
    public record BilanWarning(String accountCode, String accountName, BigDecimal balance, String message) {}

    public record Bilan(int fiscalYear, boolean closed, LocalDate asOf, LocalDate priorAsOf, List<BilanSection> actif,
                        List<BilanSection> passif, BigDecimal totalActif, BigDecimal totalPassif,
                        BigDecimal totalActifPrior, BigDecimal totalPassifPrior, BigDecimal resultatNet,
                        BigDecimal resultatNetPrior, boolean balanced, List<BilanWarning> warnings) {}

    // 1. CPC

    public record CpcRow(String code, String label, String accountFrom, String accountTo, BigDecimal current,
                         BigDecimal prior) {}

    /** {@code type}: PRODUCTS, CHARGES or RESULT (a result has no rows). */
    public record CpcSection(String key, String label, String type, List<CpcRow> rows, BigDecimal total,
                             BigDecimal totalPrior) {}

    public record Cpc(int fiscalYear, LocalDate from, LocalDate to, LocalDate priorFrom, LocalDate priorTo,
                      List<CpcSection> sections, BigDecimal resultatNet, BigDecimal resultatNetPrior) {}

    // 2. Partner balances

    public record Aging(BigDecimal current, BigDecimal d30, BigDecimal d60, BigDecimal d90, BigDecimal over90) {}

    public record PartnerBalance(UUID partnerId, String partnerName, BigDecimal opening, BigDecimal debit,
                                 BigDecimal credit, BigDecimal closing, BigDecimal advances, BigDecimal overdue,
                                 Aging aging) {}

    public record PartnerBalanceTotals(BigDecimal opening, BigDecimal debit, BigDecimal credit, BigDecimal closing,
                                       BigDecimal advances, BigDecimal overdue, Aging aging) {}

    public record PartnerBalances(String type, LocalDate from, LocalDate to, List<PartnerBalance> items,
                                  PartnerBalanceTotals totals, int page, int size,
                                  @JsonProperty("total_elements") long totalElements,
                                  @JsonProperty("total_pages") int totalPages) {}

    // 2. Partner statement

    public record StatementPartner(UUID id, String name, String ice, String address) {}

    public record StatementLine(LocalDate date, UUID entryId, String entryNumber, String journalCode, String sourceType,
                                UUID sourceId, String documentNumber, String label, BigDecimal debit,
                                BigDecimal credit, BigDecimal balance) {}

    public record StatementAdvances(BigDecimal opening, List<StatementLine> lines, BigDecimal closing) {}

    /** {@code type}: INVOICE, BILL or ADVANCE. {@code bucket}: current, d30, d60, d90 or over90. */
    public record OpenDocument(String type, UUID id, String number, LocalDate date, LocalDate dueDate,
                               BigDecimal total, BigDecimal due, int daysOverdue, String bucket) {}

    public record PartnerStatement(String type, StatementPartner partner, LocalDate from, LocalDate to,
                                   BigDecimal opening, List<StatementLine> lines, BigDecimal closing,
                                   StatementAdvances advances, List<OpenDocument> openDocuments, Aging aging) {}
}
