package com.bento.crm.payment.controller;

import com.bento.crm.payment.dto.PublicInvoiceView;
import com.bento.crm.payment.model.PaymentSettings;
import com.bento.crm.payment.service.PaymentLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The customer-facing side of Pay-by-Link. No login: the link token is the credential, and it only
 * reaches its own invoice. Provider webhooks land here too and are verified by signature.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Pay-by-Link (public)")
public class PublicPayController {

    private final PaymentLinkService service;

    @GetMapping("/public/pay/{token}")
    @Operation(summary = "Invoice summary for the pay page")
    public PublicInvoiceView view(@PathVariable String token) {
        return service.publicView(token);
    }

    @GetMapping(value = "/public/pay/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Invoice PDF for the pay page")
    public ResponseEntity<byte[]> pdf(@PathVariable String token) {
        PaymentLinkService.PublicPdf pdf = service.publicPdf(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.content());
    }

    @PostMapping("/public/pay/{token}/checkout")
    @Operation(summary = "Start a card checkout; returns the URL to send the customer to")
    public Map<String, String> checkout(@PathVariable String token) {
        return Map.of("url", service.startCheckout(token));
    }

    @PostMapping("/public/pay/{token}/mock-complete")
    @Operation(summary = "Test mode only: pay the open test checkout")
    public ResponseEntity<Void> mockComplete(@PathVariable String token, @RequestBody Map<String, String> body) {
        service.completeMock(token, body.get("sessionId"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhooks/stripe")
    @Operation(summary = "Stripe webhook (checkout.session.completed)")
    public ResponseEntity<Void> stripe(@RequestBody String payload,
                                       @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        try {
            service.handleWebhook(PaymentSettings.Provider.STRIPE, payload, signature);
        } catch (SecurityException e) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.ok().build();
    }
}
