package com.bento.crm.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the general ledger (camelCase, like the bank and expense DTOs). */
public final class LedgerDtos {

    private LedgerDtos() {}

    // Accounts

    public record Account(UUID id, String code, String name, int accountClass, String kind, boolean active,
                          boolean system, boolean used, BigDecimal balance) {}

    public record AccountCreate(@NotBlank @Pattern(regexp = "^[1-8][0-9]{3,9}$",
                                        message = "must be 4 to 10 digits starting with 1 to 8") String code,
                                @NotBlank @Size(max = 160) String name) {}

    public record AccountUpdate(@Size(min = 1, max = 160) String name, Boolean active) {}

    // Journals

    public record Journal(UUID id, String code, String name, String kind) {}

    // Cost centers

    public record CostCenter(UUID id, String code, String name, boolean active, UUID missionId, String missionName) {}

    public record CostCenterCreate(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 160) String name,
                                   UUID missionId) {}

    /**
     * Partial update. A record cannot tell an absent {@code missionId} from an explicit null, so
     * this one is a class: {@code "missionId": null} unlinks the mission, leaving it out keeps it.
     */
    @Getter
    public static final class CostCenterUpdate {
        @Size(min = 1, max = 160)
        private String name;
        private Boolean active;
        private UUID missionId;
        @JsonIgnore
        private boolean missionIdPresent;

        @JsonSetter("name")
        public void setName(String name) { this.name = name; }

        @JsonSetter("active")
        public void setActive(Boolean active) { this.active = active; }

        @JsonSetter("missionId")
        public void setMissionId(UUID missionId) {
            this.missionId = missionId;
            this.missionIdPresent = true;
        }
    }

    // Entries

    public record Line(UUID id, int lineNo, UUID accountId, String accountCode, String accountName, UUID partnerId,
                       String partnerName, UUID costCenterId, String costCenterCode, String label, BigDecimal debit,
                       BigDecimal credit) {}

    public record Entry(UUID id, String number, UUID journalId, String journalCode, LocalDate entryDate,
                        String description, String sourceType, UUID sourceId, String sourceLabel, UUID reversalOfId,
                        UUID reversedById, BigDecimal totalDebit, BigDecimal totalCredit, String postedBy,
                        Instant createdAt, List<Line> lines) {}

    public record EntryLineRequest(@NotNull UUID accountId, UUID partnerId, UUID costCenterId,
                                   @Size(max = 255) String label, BigDecimal debit, BigDecimal credit) {}

    public record EntryCreate(UUID journalId, @NotNull LocalDate entryDate, @NotBlank @Size(max = 255) String description,
                              @NotNull @Valid List<EntryLineRequest> lines) {}

    public record ReverseRequest(LocalDate entryDate, @Size(max = 255) String description) {}

    // Reports

    public record GeneralLedger(LocalDate from, LocalDate to, List<GeneralLedgerAccount> accounts) {}

    public record GeneralLedgerAccount(UUID accountId, String code, String name, BigDecimal openingBalance,
                                       List<GeneralLedgerLine> lines, BigDecimal totalDebit, BigDecimal totalCredit,
                                       BigDecimal closingBalance) {}

    public record GeneralLedgerLine(UUID entryId, String entryNumber, String journalCode, LocalDate entryDate,
                                    String label, String partnerName, String costCenterCode, BigDecimal debit,
                                    BigDecimal credit, BigDecimal balance) {}

    public record TrialBalance(LocalDate from, LocalDate to, List<TrialBalanceRow> rows, TrialBalanceTotals totals) {}

    public record TrialBalanceRow(UUID accountId, String code, String name, int accountClass, BigDecimal openingDebit,
                                  BigDecimal openingCredit, BigDecimal periodDebit, BigDecimal periodCredit,
                                  BigDecimal closingDebit, BigDecimal closingCredit) {}

    public record TrialBalanceTotals(BigDecimal openingDebit, BigDecimal openingCredit, BigDecimal periodDebit,
                                     BigDecimal periodCredit, BigDecimal closingDebit, BigDecimal closingCredit) {}

    // Posting setup (phase 2)

    public record PostingSetup(List<PartnerGroup> partnerGroups, List<Group> businessGroups, List<Group> productGroups,
                               List<GeneralSetupRow> generalSetup, List<VatSetupRow> vatSetup,
                               List<BankAccountRow> bankAccounts, UUID cashAccountId, String cashAccountCode,
                               List<CategoryRow> bankCategories, List<CategoryRow> expenseCategories,
                               LocalDate startDate) {}

    public record PartnerGroup(UUID id, String code, String name, String side, UUID accountId, String accountCode,
                               @JsonProperty("isDefault") boolean isDefault) {}

    public record Group(UUID id, String code, String name, @JsonProperty("isDefault") boolean isDefault) {}

    public record GeneralSetupRow(UUID id, UUID businessGroupId, UUID productGroupId, UUID salesAccountId,
                                  String salesAccountCode, UUID purchaseAccountId, String purchaseAccountCode) {}

    public record VatSetupRow(UUID id, String code, String name, BigDecimal rate, UUID collectedAccountId,
                              String collectedAccountCode, UUID recoverableAccountId, String recoverableAccountCode,
                              boolean exempt) {}

    public record BankAccountRow(UUID bankAccountId, String bankAccountName, UUID accountId, String accountCode) {}

    public record CategoryRow(String category, UUID accountId, String accountCode) {}

    public record PartnerGroupCreate(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 160) String name,
                                     @NotBlank String side, @NotNull UUID accountId) {}

    public record PartnerGroupUpdate(@Size(min = 1, max = 160) String name, UUID accountId,
                                     @JsonProperty("isDefault") Boolean isDefault) {}

    public record GroupCreate(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 160) String name) {}

    public record GroupUpdate(@Size(min = 1, max = 160) String name, @JsonProperty("isDefault") Boolean isDefault) {}

    public record GeneralSetupUpdate(UUID salesAccountId, UUID purchaseAccountId) {}

    public record VatSetupUpdate(UUID collectedAccountId, UUID recoverableAccountId) {}

    public record AccountAssignment(@NotNull UUID accountId) {}

    public record StartDateUpdate(@NotNull LocalDate startDate) {}

    public record RepostResult(int posted, int skipped, List<RepostError> errors) {}

    public record RepostError(String sourceType, UUID sourceId, String label, String message) {}
}
