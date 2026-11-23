package com.bento.crm.expense.dto;

import com.bento.crm.expense.model.Expense;
import com.bento.crm.expense.model.ExpenseReport;
import com.bento.crm.payment.model.Payment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Expense reports (notes de frais). Amounts are TTC with VAT inside, scale 2. */
public final class ExpenseReportDtos {

    private ExpenseReportDtos() {}

    /**
     * Create or update a DRAFT report. employeeName is required unless employeeUserId is given, in
     * which case it defaults to that user's name.
     */
    public record ExpenseReportRequest(UUID employeeUserId, @Size(max = 160) String employeeName,
                                       @Size(max = 255) String title, LocalDate periodFrom, LocalDate periodTo,
                                       @Size(max = 5000) String notes, UUID costCenterId) {}

    /**
     * A line: an expense paid by the employee. When vatAmount is left out it is computed from the VAT
     * code's rate (VAT inside the TTC amount); vatCode null means TVA20 when there is VAT.
     */
    public record LineRequest(@NotNull LocalDate expenseDate, @NotBlank @Size(max = 60) String category,
                              @Size(max = 255) String description, @NotNull @Positive BigDecimal amount,
                              @PositiveOrZero BigDecimal vatAmount, @Size(max = 20) String vatCode,
                              UUID vendorPartnerId, @Size(max = 255) String vendorName,
                              @Size(max = 100) String reference, UUID costCenterId, UUID missionId, UUID dealId,
                              UUID customerId) {

        public LineRequest(LocalDate expenseDate, String category, String description, BigDecimal amount,
                           BigDecimal vatAmount, String vatCode, UUID vendorPartnerId, String vendorName, String reference,
                           UUID costCenterId) {
            this(expenseDate, category, description, amount, vatAmount, vatCode, vendorPartnerId, vendorName, reference,
                    costCenterId, null, null, null);
        }
    }

    /** method: BANK_TRANSFER (bankAccountId, else the main bank account) or CASH (the cash register). */
    public record ReimburseRequest(@NotNull LocalDate date, @NotNull ExpenseReport.ReimbursementMethod method,
                                   UUID bankAccountId) {}

    public record Totals(BigDecimal ht, BigDecimal vat, BigDecimal ttc) {
        public static final Totals ZERO = new Totals(money(null), money(null), money(null));
    }

    /**
     * A line: the expense's fields, its HT amount and the number of receipts attached (files owner EXPENSE).
     * costCenterId is the line's own (null when it relies on the report's); the dimensions are the line's,
     * vendorId being vendorPartnerId.
     */
    public record Line(UUID id, LocalDate expenseDate, String category, String description, BigDecimal amount,
                       BigDecimal vatAmount, BigDecimal amountHt, String vatCode, Payment.Method paymentMethod,
                       UUID vendorPartnerId, String vendorName, String reference, UUID costCenterId,
                       UUID expenseReportId, UUID createdBy, Instant createdAt, long attachments, String costCenterCode,
                       UUID missionId, String missionName, UUID dealId, String dealName, UUID customerId,
                       String customerName, UUID vendorId, String vendorPartnerName) {
        public static Line of(Expense e, long attachments) {
            return of(e, attachments, ExpenseDtos.Names.NONE);
        }

        public static Line of(Expense e, long attachments, ExpenseDtos.Names n) {
            BigDecimal ttc = money(e.getAmount());
            BigDecimal vat = money(e.getVatAmount());
            return new Line(e.getId(), e.getExpenseDate(), e.getCategory(), e.getDescription(), ttc, vat,
                    ttc.subtract(vat), e.getVatCode(), e.getPaymentMethod(), e.getVendorPartnerId(), e.getVendorName(),
                    e.getReference(), e.getCostCenterId(), e.getExpenseReportId(), e.getCreatedBy(), e.getCreatedAt(),
                    attachments, n.costCenterCode(e.getCostCenterId()), e.getMissionId(), n.mission(e.getMissionId()),
                    e.getDealId(), n.deal(e.getDealId()), e.getCustomerId(), n.partner(e.getCustomerId()),
                    e.getVendorPartnerId(), n.partner(e.getVendorPartnerId()));
        }
    }

    /**
     * A report. lines is set on GET /expense-reports/{id} and on every write, null in the list.
     * attachments counts the report-level documents (files owner EXPENSE_REPORT).
     */
    public record ExpenseReportResponse(UUID id, String number, UUID employeeUserId, String employeeName, String title,
                                        LocalDate periodFrom, LocalDate periodTo, String notes, ExpenseReport.Status status,
                                        Instant postedAt, Instant reimbursedAt, LocalDate reimbursementDate,
                                        ExpenseReport.ReimbursementMethod reimbursementMethod,
                                        UUID reimbursementBankAccountId, UUID costCenterId, LocalDate postingDate,
                                        int lineCount, Totals totals, long attachments, List<Line> lines,
                                        UUID createdBy, Instant createdAt, Instant updatedAt) {}

    public static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
