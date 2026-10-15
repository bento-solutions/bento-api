package com.bento.crm.payment.gateway;

import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.model.PaymentSettings;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A card payment provider behind Pay-by-Link, chosen per organization by
 * {@link PaymentSettings#getProvider()}, the same way {@code WhatsAppProvider} is chosen per
 * account. {@link MockPaymentGateway} simulates checkout for testing; {@link StripePaymentGateway}
 * uses Stripe Checkout. CMI will be a third implementation.
 */
public interface PaymentGateway {

    PaymentSettings.Provider kind();

    /** Whether the server holds what this gateway needs (keys, URLs) to take a payment. */
    boolean configured();

    /**
     * Opens a hosted checkout for the link's amount.
     *
     * @param description what the customer sees, e.g. "Facture FAC-2026-0003"
     * @param successUrl  where the customer returns after paying
     * @param cancelUrl   where the customer returns after giving up
     */
    Checkout startCheckout(PaymentLink link, String description, String customerEmail,
                           String successUrl, String cancelUrl);

    /**
     * Reads a provider webhook. Returns the completed payment it reports, or empty for an event
     * that is not a completed payment. Throws {@link SecurityException} when the signature is wrong.
     */
    default Optional<CompletedPayment> parseWebhook(String payload, String signatureHeader) {
        return Optional.empty();
    }

    /** A hosted checkout: the gateway's session id and the page to send the customer to. */
    record Checkout(String sessionId, String url) {}

    /**
     * @param sessionId the checkout session the payment belongs to
     * @param amount    what was charged, in currency units
     * @param reference the provider's payment reference, stored on the Payment
     */
    record CompletedPayment(String sessionId, BigDecimal amount, String reference) {}
}
