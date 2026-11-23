package com.bento.crm.expense.dto;

import com.bento.crm.expense.model.Expense;
import com.bento.crm.payment.model.Payment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExpenseDtos {

    private ExpenseDtos() {}

    public record ExpenseRequest(@NotNull LocalDate expenseDate, @NotBlank @Size(max = 60) String category,
                                 @Size(max = 255) String description, @NotNull @Positive BigDecimal amount,
                                 @PositiveOrZero BigDecimal vatAmount, @NotNull Payment.Method paymentMethod,
                                 UUID vendorPartnerId, @Size(max = 255) String vendorName,
                                 @Size(max = 100) String reference, UUID costCenterId, @Size(max = 20) String vatCode,
                                 UUID missionId, UUID dealId, UUID customerId) {

        public ExpenseRequest(LocalDate expenseDate, String category, String description, BigDecimal amount,
                              BigDecimal vatAmount, Payment.Method paymentMethod, UUID vendorPartnerId,
                              String vendorName, String reference, UUID costCenterId, String vatCode) {
            this(expenseDate, category, description, amount, vatAmount, paymentMethod, vendorPartnerId, vendorName,
                    reference, costCenterId, vatCode, null, null, null);
        }
    }

    /**
     * Display names of the cost centers (code), missions, deals (title) and partners an expense refers to.
     * Built in one go for a page of expenses.
     */
    public record Names(Map<UUID, String> costCenterCodes, Map<UUID, String> missions, Map<UUID, String> deals,
                       Map<UUID, String> partners) {
        public static final Names NONE = new Names(Map.of(), Map.of(), Map.of(), Map.of());

        private static String get(Map<UUID, String> m, UUID id) {
            return id == null ? null : m.get(id);
        }

        public String costCenterCode(UUID id) { return get(costCenterCodes, id); }

        public String mission(UUID id) { return get(missions, id); }

        public String deal(UUID id) { return get(deals, id); }

        public String partner(UUID id) { return get(partners, id); }
    }

    /**
     * An expense. expenseReportId / expenseReportNumber / expenseReportStatus are set on the lines of
     * an expense report (the number is null until the report is first posted); such an expense is
     * changed through its report only.
     */
    public record ExpenseResponse(UUID id, LocalDate expenseDate, String category, String description,
                                  BigDecimal amount, BigDecimal vatAmount, Payment.Method paymentMethod,
                                  UUID vendorPartnerId, String vendorName, String reference, UUID costCenterId,
                                  UUID createdBy, Instant createdAt, String vatCode, UUID expenseReportId,
                                  String expenseReportNumber, String expenseReportStatus, String costCenterCode,
                                  UUID missionId, String missionName, UUID dealId, String dealName, UUID customerId,
                                  String customerName, UUID vendorId, String vendorPartnerName) {
        public static ExpenseResponse of(Expense e) {
            return of(e, null, null, Names.NONE);
        }

        public static ExpenseResponse of(Expense e, String reportNumber, String reportStatus, Names n) {
            return new ExpenseResponse(e.getId(), e.getExpenseDate(), e.getCategory(), e.getDescription(), e.getAmount(),
                    e.getVatAmount(), e.getPaymentMethod(), e.getVendorPartnerId(), e.getVendorName(), e.getReference(),
                    e.getCostCenterId(), e.getCreatedBy(), e.getCreatedAt(), e.getVatCode(), e.getExpenseReportId(),
                    reportNumber, reportStatus, n.costCenterCode(e.getCostCenterId()), e.getMissionId(),
                    n.mission(e.getMissionId()), e.getDealId(), n.deal(e.getDealId()), e.getCustomerId(),
                    n.partner(e.getCustomerId()), e.getVendorPartnerId(), n.partner(e.getVendorPartnerId()));
        }
    }

    /** The number and status of the report an expense belongs to. */
    public record ReportRef(String number, String status) {}

    public record Summary(LocalDate from, LocalDate to, BigDecimal total, BigDecimal vat, long count,
                          List<CategoryTotal> byCategory, List<String> categories) {
        public record CategoryTotal(String category, BigDecimal amount, BigDecimal vat, long count) {}
    }
}
