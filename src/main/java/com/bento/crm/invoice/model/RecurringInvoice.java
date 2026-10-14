package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A template the recurring-invoice job turns into a real {@link Invoice} once per period.
 *
 * <p>Occurrence {@code n} falls on {@code startDate + n × interval}, always counted from the
 * start date rather than from the previous run, so a monthly template started on the 31st
 * lands on the last day of February and is back on the 31st in March.
 */
@Entity
@Table(name = "recurring_invoice")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringInvoice extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    @Column(nullable = false)
    @Builder.Default
    private Integer intervalCount = 1;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    private LocalDate nextRunDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    @Builder.Default
    private Integer paymentTermsDays = 30;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoSend = true;

    private String customerName;

    @Column(columnDefinition = "text")
    private String deliveryAddress;

    private String vatNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> lines = new ArrayList<>();

    /** Index of the next occurrence to bill; moves past periods skipped while paused. */
    @Column(nullable = false)
    @Builder.Default
    private Integer periodIndex = 0;

    /** Invoices generated so far. */
    @Column(nullable = false)
    @Builder.Default
    private Integer generatedCount = 0;

    private Instant lastRunAt;

    @Column(columnDefinition = "uuid")
    private UUID lastInvoiceId;

    /** The date of occurrence {@code n} (0-based). */
    public LocalDate occurrence(int n) {
        long steps = (long) n * intervalCount;
        return switch (frequency) {
            case WEEKLY -> startDate.plusWeeks(steps);
            case MONTHLY -> startDate.plusMonths(steps);
            case QUARTERLY -> startDate.plusMonths(steps * 3);
            case YEARLY -> startDate.plusYears(steps);
        };
    }

    /**
     * Points {@code nextRunDate} at the next occurrence still to generate, and ends the
     * template once that occurrence would fall after {@code endDate}.
     */
    public void scheduleNext() {
        LocalDate next = occurrence(periodIndex);
        if (endDate != null && next.isAfter(endDate)) {
            nextRunDate = null;
            status = Status.ENDED;
        } else {
            nextRunDate = next;
        }
    }

    public enum Frequency {
        WEEKLY, MONTHLY, QUARTERLY, YEARLY
    }

    public enum Status {
        ACTIVE, PAUSED, ENDED
    }
}
