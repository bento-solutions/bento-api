package com.bento.crm.payment.gateway;

import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.model.PaymentSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test checkout. The "hosted page" is Bento's own pay page with a test card form; submitting it
 * calls the public mock-complete endpoint, which settles the invoice like a real webhook would.
 * Off unless {@code app.payments.mock-enabled=true} (PAYMENTS_MOCK_ENABLED), so a deployment that
 * does not ask for it cannot mark invoices paid without a real charge.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final boolean enabled;

    public MockPaymentGateway(@Value("${app.payments.mock-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PaymentSettings.Provider kind() {
        return PaymentSettings.Provider.MOCK;
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    @Override
    public Checkout startCheckout(PaymentLink link, String description, String customerEmail,
                                  String successUrl, String cancelUrl) {
        String sessionId = "mock_" + UUID.randomUUID();
        String separator = cancelUrl.contains("?") ? "&" : "?";
        return new Checkout(sessionId, cancelUrl + separator + "mock=" + sessionId);
    }
}
