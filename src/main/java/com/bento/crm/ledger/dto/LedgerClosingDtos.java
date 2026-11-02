package com.bento.crm.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of general ledger phase 3, wave B (closing, VAT, fixed assets, cash). */
public final class LedgerClosingDtos {

    private LedgerClosingDtos() {}

    // 5. Settings and years

    public record Settings(LocalDate lockDate, String vatRegime, String vatPeriod, int fiscalYearStartMonth) {}

    /** Partial update; {@code "lockDate": null} removes the lock date, leaving it out keeps it. */
    @Getter
    public static final class SettingsUpdate {
        private LocalDate lockDate;
        @JsonIgnore
        private boolean lockDatePresent;
        private String vatRegime;
        private String vatPeriod;
        private Integer fiscalYearStartMonth;

        @JsonSetter("lockDate")
        public void setLockDate(LocalDate lockDate) {
            this.lockDate = lockDate;
            this.lockDatePresent = true;
        }

        @JsonSetter("vatRegime")
        public void setVatRegime(String vatRegime) { this.vatRegime = vatRegime; }

        @JsonSetter("vatPeriod")
        public void setVatPeriod(String vatPeriod) { this.vatPeriod = vatPeriod; }

        @JsonSetter("fiscalYearStartMonth")
        public void setFiscalYearStartMonth(Integer fiscalYearStartMonth) { this.fiscalYearStartMonth = fiscalYearStartMonth; }
    }

    public record FiscalYear(int year, String status, LocalDate startDate, LocalDate endDate, Instant closedAt,
                             UUID resultEntryId, UUID openingEntryId) {}

    // 6. VAT

    /** {@code rate} is a percentage, like the VAT setup's (20 for TVA20). */
    public record VatLine(String vatCode, BigDecimal rate, BigDecimal base, BigDecimal vat) {}

    public record VatSummary(LocalDate from, LocalDate to, String regime, List<VatLine> collected, List<VatLine> recoverable,
                             BigDecimal totalCollected, BigDecimal totalRecoverable, BigDecimal creditCarriedIn,
                             BigDecimal due, BigDecimal credit, UUID settlementEntryId, UUID taxDeadlineId) {}

    public record VatSettleRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

    // 7. Stock change

    public record StockChange(LocalDate date, BigDecimal opening, BigDecimal closing, BigDecimal change, UUID entryId) {}

    public record StockChangeRequest(@NotNull LocalDate date) {}

    // 8. Cut-off

    public record CutoffItem(String key, String sourceType, UUID sourceId, UUID partnerId, String partnerName, String label,
                             BigDecimal base, String vatCode, BigDecimal vat, UUID accountId, String accountCode) {}

    public record CutoffProposals(LocalDate date, List<CutoffItem> fnp, List<CutoffItem> fae, List<CutoffItem> cca,
                                  List<CutoffItem> pca) {}

    /** {@code vat} is optional: by default base x the rate of {@code vatCode} (0 without a code). */
    public record CutoffLine(@NotBlank String type, @NotNull UUID accountId, UUID partnerId, @NotNull BigDecimal base,
                             String vatCode, BigDecimal vat, @Size(max = 255) String label, String sourceType,
                             UUID sourceId) {}

    public record CutoffRequest(@NotNull LocalDate date, @NotNull @Valid List<CutoffLine> lines) {}

    /** The cut-off posted on a date and its reversal on the next day; both null when none is posted. */
    public record Cutoff(LocalDate date, UUID entryId, UUID reversalEntryId, LocalDate reversalDate,
                         LedgerDtos.Entry entry, LedgerDtos.Entry reversal) {}

    // 9. Fixed assets

    public record AssetCategory(String code, String name, String assetAccountCode, String depreciationAccountCode,
                                String expenseAccountCode, int defaultLifeMonths) {}

    public record Asset(UUID id, String code, String name, String category, LocalDate acquisitionDate, LocalDate startDate,
                        BigDecimal acquisitionCost, BigDecimal residualValue, String method, int usefulLifeMonths,
                        BigDecimal coefficient, String assetAccountCode, String depreciationAccountCode,
                        String expenseAccountCode, BigDecimal accumulatedDepreciation, BigDecimal netBookValue,
                        String status, LocalDate disposedAt, BigDecimal disposalPrice, UUID supplierInvoiceId,
                        Integer supplierInvoiceLineIndex, String paidFrom, UUID costCenterId, UUID acquisitionEntryId,
                        UUID disposalEntryId) {}

    public record AssetRequest(@Size(max = 30) String code, @NotBlank @Size(max = 160) String name,
                               @NotBlank String category, @NotNull LocalDate acquisitionDate, LocalDate startDate,
                               @NotNull @Positive BigDecimal acquisitionCost, @PositiveOrZero BigDecimal residualValue,
                               String method, @Positive Integer usefulLifeMonths, UUID supplierInvoiceId,
                               Integer supplierInvoiceLineIndex, String paidFrom, UUID costCenterId) {}

    public record ScheduleRow(int year, int month, BigDecimal amount, BigDecimal cumulated, BigDecimal netBookValue,
                              UUID entryId) {}

    public record DepreciateRequest(@NotNull LocalDate upTo) {}

    /** {@code months}: asset-months posted; {@code entries}: journal entries written. */
    public record DepreciationResult(int months, int entries, BigDecimal total) {}

    public record DisposeRequest(@NotNull LocalDate date, @NotNull @PositiveOrZero BigDecimal price, String paidTo) {}

    public record RegisterDisposal(LocalDate date, BigDecimal price, BigDecimal netBookValue, BigDecimal gainOrLoss) {}

    /** {@code rate} is the yearly depreciation rate as a percentage (20 for 5 years linear). */
    public record RegisterRow(UUID id, String code, String name, String category, LocalDate acquisitionDate, BigDecimal cost,
                              String method, BigDecimal rate, BigDecimal accumulatedStart, BigDecimal depreciationYear,
                              BigDecimal accumulatedEnd, BigDecimal netBookValue, RegisterDisposal disposal) {}

    public record RegisterTotals(String category, BigDecimal cost, BigDecimal accumulatedStart, BigDecimal depreciationYear,
                                 BigDecimal accumulatedEnd, BigDecimal netBookValue) {}

    public record Register(int year, List<RegisterRow> assets, List<RegisterTotals> categories, RegisterTotals totals) {}

    // 10. Cash register

    public record CashRegister(BigDecimal balance, UUID accountId, String accountCode) {}

    public record CashMovement(UUID id, LocalDate date, String kind, BigDecimal amount, String label, String category,
                               UUID partnerId, String partnerName, String sourceType, UUID sourceId, UUID entryId) {}

    public record CashMovementRequest(@NotNull LocalDate date, @NotBlank String kind, @NotNull @Positive BigDecimal amount,
                                      @NotBlank @Size(max = 255) String label, @Size(max = 60) String category,
                                      UUID partnerId, UUID costCenterId) {}
}
