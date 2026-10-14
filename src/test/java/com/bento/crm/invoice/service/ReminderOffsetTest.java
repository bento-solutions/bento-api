package com.bento.crm.invoice.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderOffsetTest {

    private static final List<Integer> OFFSETS = List.of(-3, 1, 7, 15);
    private static final LocalDate DUE = LocalDate.of(2027, 3, 10);

    @Test
    void nothingIsDueBeforeTheFirstOffset() {
        assertThat(InvoiceReminderService.offsetReached(OFFSETS, DUE, LocalDate.of(2027, 3, 6))).isNull();
    }

    @Test
    void theUpcomingReminderStartsThreeDaysBeforeTheDueDate() {
        assertThat(InvoiceReminderService.offsetReached(OFFSETS, DUE, LocalDate.of(2027, 3, 7))).isEqualTo(-3);
        assertThat(InvoiceReminderService.offsetReached(OFFSETS, DUE, LocalDate.of(2027, 3, 10))).isEqualTo(-3);
    }

    @Test
    void anInvoiceFoundLateGetsOnlyTheLatestReminderItHasReached() {
        assertThat(InvoiceReminderService.offsetReached(OFFSETS, DUE, LocalDate.of(2027, 3, 30))).isEqualTo(15);
    }
}
