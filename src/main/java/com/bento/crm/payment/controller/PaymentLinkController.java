package com.bento.crm.payment.controller;

import com.bento.crm.payment.dto.PaymentLinkResponse;
import com.bento.crm.payment.dto.PaymentSettingsRequest;
import com.bento.crm.payment.dto.PaymentSettingsResponse;
import com.bento.crm.payment.model.PaymentLink;
import com.bento.crm.payment.service.PaymentLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Pay-by-Link", description = "Online card payment for invoices")
public class PaymentLinkController {

    private final PaymentLinkService service;

    @GetMapping("/payment-settings")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    @Operation(summary = "Online payment settings")
    public PaymentSettingsResponse settings() {
        return service.settings();
    }

    @PutMapping("/payment-settings")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    @Operation(summary = "Choose the payment provider")
    public PaymentSettingsResponse updateSettings(@Valid @RequestBody PaymentSettingsRequest request) {
        return service.updateSettings(request);
    }

    @GetMapping("/invoices/{invoiceId}/payment-link")
    @PreAuthorize("hasAuthority('INVOICES_READ')")
    @Operation(summary = "The invoice's latest payment link, if any")
    public ResponseEntity<PaymentLinkResponse> current(@PathVariable UUID invoiceId) {
        return service.currentLink(invoiceId)
                .map(link -> ResponseEntity.ok(PaymentLinkResponse.of(link, service.urlFor(link))))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/invoices/{invoiceId}/payment-link")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Get or create the invoice's open payment link")
    public PaymentLinkResponse create(@PathVariable UUID invoiceId) {
        PaymentLink link = service.linkFor(invoiceId);
        return PaymentLinkResponse.of(link, service.urlFor(link));
    }

    @DeleteMapping("/invoices/{invoiceId}/payment-link")
    @PreAuthorize("hasAuthority('INVOICES_WRITE')")
    @Operation(summary = "Deactivate the invoice's open payment link")
    public ResponseEntity<Void> cancel(@PathVariable UUID invoiceId) {
        service.cancel(invoiceId);
        return ResponseEntity.noContent().build();
    }
}
