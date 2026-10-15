package com.bento.crm.invoice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** The day a supplier bill is planned to be paid; null clears it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentScheduleRequest {

    private LocalDate scheduledPaymentDate;
}
