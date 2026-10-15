package com.bento.crm.payment.gateway;

import com.bento.crm.payment.model.PaymentLink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StripePaymentGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.stripe.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final StripePaymentGateway gateway = new StripePaymentGateway("sk_test_123", "whsec_abc", builder,
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void opensACheckoutForTheAmountOwedInCentimes() {
        PaymentLink link = new PaymentLink();
        link.setId(UUID.randomUUID());
        link.setOrganizationId(UUID.randomUUID());
        link.setAmount(new BigDecimal("1250.50"));
        server.expect(requestTo("https://api.stripe.test/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_123"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=125050")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("currency%5D=mad")))
                .andRespond(withSuccess("{\"id\":\"cs_test_1\",\"url\":\"https://checkout.stripe.com/c/cs_test_1\"}",
                        MediaType.APPLICATION_JSON));

        PaymentGateway.Checkout checkout = gateway.startCheckout(link, "Facture FAC-1", "a@b.ma",
                "https://app/pay/t?status=success", "https://app/pay/t");

        assertThat(checkout.sessionId()).isEqualTo("cs_test_1");
        assertThat(checkout.url()).isEqualTo("https://checkout.stripe.com/c/cs_test_1");
        server.verify();
    }

    @Test
    void readsACompletedCheckoutFromASignedEvent() {
        String payload = """
                {"type":"checkout.session.completed","data":{"object":{"id":"cs_test_1","payment_status":"paid",
                 "amount_total":125050,"payment_intent":"pi_42"}}}""";
        long t = NOW.getEpochSecond();
        String header = "t=" + t + ",v1=" + gateway.hmac(t + "." + payload);

        PaymentGateway.CompletedPayment done = gateway.parseWebhook(payload, header).orElseThrow();
        assertThat(done.sessionId()).isEqualTo("cs_test_1");
        assertThat(done.amount()).isEqualByComparingTo("1250.50");
        assertThat(done.reference()).isEqualTo("pi_42");

        String other = "{\"type\":\"customer.created\"}";
        assertThat(gateway.parseWebhook(other, "t=" + t + ",v1=" + gateway.hmac(t + "." + other))).isEmpty();
    }

    @Test
    void rejectsForgedOrStaleSignatures() {
        String payload = "{\"type\":\"checkout.session.completed\"}";
        long t = NOW.getEpochSecond();
        assertThatThrownBy(() -> gateway.parseWebhook(payload, "t=" + t + ",v1=deadbeef"))
                .isInstanceOf(SecurityException.class);
        long old = t - 600;
        assertThatThrownBy(() -> gateway.parseWebhook(payload, "t=" + old + ",v1=" + gateway.hmac(old + "." + payload)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(payload, null)).isInstanceOf(SecurityException.class);
    }
}
