package com.bento.crm.invoice;

import com.bento.crm.invoice.model.RecurringInvoice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringScheduleTest {

    private static RecurringInvoice template(RecurringInvoice.Frequency frequency, int interval, LocalDate start) {
        RecurringInvoice t = new RecurringInvoice();
        t.setFrequency(frequency);
        t.setIntervalCount(interval);
        t.setStartDate(start);
        return t;
    }

    @Test
    void monthlyFromThe31stClampsToShortMonthsWithoutDrifting() {
        RecurringInvoice t = template(RecurringInvoice.Frequency.MONTHLY, 1, LocalDate.of(2027, 1, 31));

        assertThat(t.occurrence(0)).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(t.occurrence(1)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(t.occurrence(2)).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void quarterlyAndIntervalsMultiply() {
        RecurringInvoice quarterly = template(RecurringInvoice.Frequency.QUARTERLY, 1, LocalDate.of(2027, 1, 15));
        RecurringInvoice fortnightly = template(RecurringInvoice.Frequency.WEEKLY, 2, LocalDate.of(2027, 1, 1));

        assertThat(quarterly.occurrence(2)).isEqualTo(LocalDate.of(2027, 7, 15));
        assertThat(fortnightly.occurrence(3)).isEqualTo(LocalDate.of(2027, 2, 12));
    }

    @Test
    void scheduleNextEndsTheTemplateAfterItsEndDate() {
        RecurringInvoice t = template(RecurringInvoice.Frequency.MONTHLY, 1, LocalDate.of(2027, 1, 10));
        t.setEndDate(LocalDate.of(2027, 2, 10));

        t.setPeriodIndex(1);
        t.scheduleNext();
        assertThat(t.getNextRunDate()).isEqualTo(LocalDate.of(2027, 2, 10));
        assertThat(t.getStatus()).isEqualTo(RecurringInvoice.Status.ACTIVE);

        t.setPeriodIndex(2);
        t.scheduleNext();
        assertThat(t.getNextRunDate()).isNull();
        assertThat(t.getStatus()).isEqualTo(RecurringInvoice.Status.ENDED);
    }
}
