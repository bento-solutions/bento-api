package com.bento.crm.bank.dto;

import com.bento.crm.bank.model.BankAccount;
import com.bento.crm.bank.model.BankImport;
import com.bento.crm.bank.model.BankRule;
import com.bento.crm.bank.model.BankTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BankDtos {

    private BankDtos() {}

    public record Account(UUID id, String name, BankAccount.Bank bank, String accountNumber, String currency,
                          BigDecimal openingBalance, LocalDate openingDate, boolean active, BigDecimal balance,
                          LocalDate lastTransactionDate, long unmatched) {}

    public record AccountRequest(String name, BankAccount.Bank bank, String accountNumber, String currency,
                                 BigDecimal openingBalance, LocalDate openingDate, Boolean active) {}

    public record Import(UUID id, UUID bankAccountId, UUID fileId, UUID inboundDocumentId, String format, String parser,
                         LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance, BigDecimal closingBalance,
                         int rowsImported, int rowsSkipped, boolean balanceMismatch, Instant createdAt) {
        public static Import of(BankImport i) {
            return new Import(i.getId(), i.getBankAccountId(), i.getFileId(), i.getInboundDocumentId(), i.getFormat(),
                    i.getParser(), i.getPeriodStart(), i.getPeriodEnd(), i.getOpeningBalance(), i.getClosingBalance(),
                    i.getRowsImported(), i.getRowsSkipped(), i.isBalanceMismatch(), i.getCreatedAt());
        }
    }

    public record Transaction(UUID id, UUID bankAccountId, LocalDate bookingDate, LocalDate valueDate, String description,
                              String reference, BigDecimal amount, BigDecimal balanceAfter, String category,
                              BankTransaction.CategorySource categorySource, BankTransaction.Status status, UUID invoiceId,
                              String invoiceNumber, UUID paymentId, UUID expenseId, boolean paymentCreated,
                              String matchLabel) {}

    public record Candidate(String kind, UUID id, String label, String partnerName, LocalDate date, BigDecimal amount,
                            BigDecimal outstanding, int score, String reason) {}

    public record MatchRequest(String kind, UUID id) {}

    public record CategoryRequest(String category, Boolean remember, String containsText) {}

    public record Rule(UUID id, String containsText, BankRule.Direction direction, String category) {
        public static Rule of(BankRule r) {
            return new Rule(r.getId(), r.getContainsText(), r.getDirection(), r.getCategory());
        }
    }

    public record RuleRequest(String containsText, BankRule.Direction direction, String category) {}

    public record ImportResult(Import importSummary, BankAccount.Bank detectedBank, String detectedAccountNumber,
                               int autoMatched) {}

    public record Dashboard(BigDecimal totalBalance, List<Account> accounts, long unmatched, long uncategorized,
                            List<Month> months, List<CategoryTotal> outflowByCategory, Forecast forecast) {}

    public record Month(String month, BigDecimal inflow, BigDecimal outflow) {}

    public record CategoryTotal(String category, BigDecimal amount) {}

    public record Forecast(LocalDate from, BigDecimal startBalance, List<Horizon> horizons, List<Week> weeks,
                           BigDecimal lowestBalance, LocalDate lowestWeek, List<ForecastItem> items,
                           List<CategoryTotal> recurringMonthly) {}

    public record Horizon(int days, BigDecimal inflow, BigDecimal outflow, BigDecimal endBalance) {}

    public record Week(LocalDate weekStart, BigDecimal inflow, BigDecimal outflow, BigDecimal balance) {}

    /** One expected movement: an invoice due, a bill to pay, a recurring invoice, a monthly cost. */
    public record ForecastItem(LocalDate date, String kind, String label, BigDecimal amount, UUID invoiceId) {}
}
