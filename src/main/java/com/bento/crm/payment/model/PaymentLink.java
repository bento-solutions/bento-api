package com.bento.crm.payment.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A public link a customer opens to pay an invoice by card. The token is the only secret: anyone
 * holding it can see the invoice summary and pay it, nothing else.
 */
@Entity
@Table(name = "payment_link")
@Getter
@Setter
public class PaymentLink extends BaseTenantEntity {

    public enum Status { OPEN, PAID, CANCELLED }

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(nullable = false, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentSettings.Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    /** The gateway's checkout session for the latest attempt; webhooks are matched on it. */
    private String providerSessionId;

    /** The amount asked for at the latest checkout (what was still owed then). */
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "MAD";

    private Instant paidAt;

    @Column(columnDefinition = "uuid")
    private UUID paymentId;
}
