package com.bento.crm.payment.dto;

import com.bento.crm.payment.model.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {

    @NotNull
    private UUID partnerId;

    private UUID invoiceId;

    @NotNull
    private LocalDate paymentDate;

    @NotNull
    @Positive
    private BigDecimal amount;

    private Payment.Method method;

    private String reference;

    private String notes;

    /** The bank account the money went through; null picks the organization's main account. */
    private UUID bankAccountId;

    /** Paid through the cash register; null follows the method (CASH = cash register). */
    private Boolean cashRegister;

    public CreatePaymentRequest(UUID partnerId, UUID invoiceId, LocalDate paymentDate, BigDecimal amount,
                                Payment.Method method, String reference, String notes) {
        this(partnerId, invoiceId, paymentDate, amount, method, reference, notes, null, null);
    }
}
