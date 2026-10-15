package com.bento.crm.payment.gateway;

import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.model.PaymentSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stripe Checkout over Stripe's REST API (no SDK, so nothing new to vet in the build). Keys come
 * from the server configuration ({@code STRIPE_SECRET_KEY}, {@code STRIPE_WEBHOOK_SECRET}); with no
 * secret key the gateway reports itself unconfigured and organizations cannot pick it.
 */
@Component
public class StripePaymentGateway implements PaymentGateway {

    /** Stripe rejects events whose signature timestamp is older than this, and so do we. */
    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final String secretKey;
    private final String webhookSecret;
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public StripePaymentGateway(@Value("${app.payments.stripe.secret-key:}") String secretKey,
                                @Value("${app.payments.stripe.webhook-secret:}") String webhookSecret,
                                @Value("${app.payments.stripe.api-base:https://api.stripe.com}") String apiBase,
                                ObjectMapper objectMapper) {
        this(secretKey, webhookSecret, RestClient.builder().baseUrl(apiBase), objectMapper, Clock.systemUTC());
    }

    StripePaymentGateway(String secretKey, String webhookSecret, RestClient.Builder client,
                         ObjectMapper objectMapper, Clock clock) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.client = client.build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public PaymentSettings.Provider kind() {
        return PaymentSettings.Provider.STRIPE;
    }

    @Override
    public boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }

    @Override
    public Checkout startCheckout(PaymentLink link, String description, String customerEmail,
                                  String successUrl, String cancelUrl) {
        if (!configured()) {
            throw new IllegalStateException("Stripe is not configured on this server");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", successUrl);
        form.add("cancel_url", cancelUrl);
        form.add("client_reference_id", link.getId().toString());
        form.add("metadata[payment_link_id]", link.getId().toString());
        form.add("metadata[organization_id]", link.getOrganizationId().toString());
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", link.getCurrency().toLowerCase());
        form.add("line_items[0][price_data][unit_amount]", minorUnits(link.getAmount()));
        form.add("line_items[0][price_data][product_data][name]", description);
        if (customerEmail != null && !customerEmail.isBlank()) {
            form.add("customer_email", customerEmail);
        }

        JsonNode session = client.post()
                .uri("/v1/checkout/sessions")
                .header("Authorization", "Bearer " + secretKey)
                // One session per link and amount: a double click does not open two checkouts.
                .header("Idempotency-Key", link.getId() + "-" + minorUnits(link.getAmount()) + "-" + link.getVersion())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (session == null || !session.hasNonNull("id") || !session.hasNonNull("url")) {
            throw new IllegalStateException("Stripe did not return a checkout session");
        }
        return new Checkout(session.get("id").asText(), session.get("url").asText());
    }

    @Override
    public Optional<CompletedPayment> parseWebhook(String payload, String signatureHeader) {
        verifySignature(payload, signatureHeader);
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable Stripe event");
        }
        String type = event.path("type").asText();
        if (!type.equals("checkout.session.completed") && !type.equals("checkout.session.async_payment_succeeded")) {
            return Optional.empty();
        }
        JsonNode session = event.path("data").path("object");
        if (!"paid".equals(session.path("payment_status").asText())) {
            return Optional.empty();
        }
        BigDecimal amount = BigDecimal.valueOf(session.path("amount_total").asLong()).movePointLeft(2);
        String reference = session.hasNonNull("payment_intent") ? session.get("payment_intent").asText()
                : session.path("id").asText();
        return Optional.of(new CompletedPayment(session.path("id").asText(), amount, reference));
    }

    /**
     * Stripe signs {@code timestamp + "." + payload} with HMAC-SHA256 and sends
     * {@code t=<timestamp>,v1=<hex>[,v1=...]}. Any matching v1 within the tolerance is accepted.
     */
    void verifySignature(String payload, String header) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new SecurityException("Stripe webhook secret is not configured");
        }
        if (header == null || header.isBlank()) {
            throw new SecurityException("Missing Stripe signature");
        }
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) timestamp = kv[1];
            if (kv[0].equals("v1")) signatures.add(kv[1]);
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new SecurityException("Malformed Stripe signature");
        }
        long age;
        try {
            age = clock.instant().getEpochSecond() - Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new SecurityException("Malformed Stripe signature");
        }
        if (Math.abs(age) > SIGNATURE_TOLERANCE.toSeconds()) {
            throw new SecurityException("Stripe signature is too old");
        }
        byte[] expected = hmac(timestamp + "." + payload).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return;
            }
        }
        throw new SecurityException("Stripe signature does not match");
    }

    String hmac(String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String minorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigIntegerExact().toString();
    }
}
