package com.bento.crm.payment.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** How an organization takes card payments. One row per organization; absent means NONE. */
@Entity
@Table(name = "payment_settings")
@Getter
@Setter
public class PaymentSettings extends BaseTenantEntity {

    /** Which gateway backs the organization's payment links. */
    public enum Provider {
        /** Pay-by-Link is off. */
        NONE,
        /** Simulated checkout for testing: the hosted page has a "pay" button that settles the invoice. */
        MOCK,
        /** Stripe Checkout, with keys from the server configuration. */
        STRIPE
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider = Provider.NONE;

    /** Whether invoice and reminder emails carry a "Pay online" button. */
    @Column(nullable = false)
    private Boolean includeInEmails = Boolean.TRUE;
}
