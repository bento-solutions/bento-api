package com.bento.crm.payment.dto;

import com.bento.crm.payment.model.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponse {

    private UUID id;
    private UUID organizationId;
    private UUID partnerId;
    private UUID invoiceId;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private Payment.Method method;
    private String reference;
    private String notes;
    private UUID bankAccountId;
    private boolean cashRegister;
    private boolean advance;
    private LocalDate advanceAppliedOn;
    private UUID createdBy;
    private UUID updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public static PaymentResponse fromEntity(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .organizationId(payment.getOrganizationId())
                .partnerId(payment.getPartnerId())
                .invoiceId(payment.getInvoiceId())
                .paymentDate(payment.getPaymentDate())
                .amount(payment.getAmount())
                .method(payment.getMethod())
                .reference(payment.getReference())
                .notes(payment.getNotes())
                .bankAccountId(payment.getBankAccountId())
                .cashRegister(payment.isCashRegister())
                .advance(payment.isAdvance())
                .advanceAppliedOn(payment.getAdvanceAppliedOn())
                .createdBy(payment.getCreatedBy())
                .updatedBy(payment.getUpdatedBy())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }
}
