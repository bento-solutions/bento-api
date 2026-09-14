package com.bento.crm.payment.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.payment.dto.CreatePaymentRequest;
import com.bento.crm.payment.dto.PaymentResponse;
import com.bento.crm.payment.model.Payment;
import com.bento.crm.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Settlement movements recorded against partners and invoices")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @PreAuthorize("hasAuthority('PAYMENTS_CREATE')")
    @Operation(summary = "Record payment", description = "Record a payment against a partner, optionally allocated to an invoice")
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.fromEntity(payment));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    @Operation(summary = "Get payment")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.fromEntity(paymentService.getPayment(id)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    @Operation(summary = "List payments", description = "List all payments in the organization")
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(Pageable pageable) {
        Page<PaymentResponse> page = paymentService.listPayments(pageable).map(PaymentResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping("/partner/{partnerId}")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    @Operation(summary = "List payments by partner")
    public ResponseEntity<List<PaymentResponse>> listByPartner(@PathVariable UUID partnerId) {
        return ResponseEntity.ok(paymentService.listPaymentsByPartner(partnerId).stream()
                .map(PaymentResponse::fromEntity).toList());
    }

    @GetMapping("/invoice/{invoiceId}")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    @Operation(summary = "List payments by invoice")
    public ResponseEntity<List<PaymentResponse>> listByInvoice(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(paymentService.listPaymentsByInvoice(invoiceId).stream()
                .map(PaymentResponse::fromEntity).toList());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_WRITE')")
    @Operation(summary = "Update payment")
    public ResponseEntity<PaymentResponse> updatePayment(@PathVariable UUID id, @Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.ok(PaymentResponse.fromEntity(paymentService.updatePayment(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_DELETE')")
    @Operation(summary = "Delete payment")
    public ResponseEntity<Void> deletePayment(@PathVariable UUID id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PAYMENTS_DELETE')")
    @Operation(summary = "Restore payment", description = "Undo a soft delete on a payment")
    public ResponseEntity<PaymentResponse> restorePayment(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.fromEntity(paymentService.restorePayment(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PAYMENTS_DELETE')")
    @Operation(summary = "List deleted payments", description = "Soft-deleted payments still inside the retention window")
    public ResponseEntity<PageResponse<PaymentResponse>> listDeleted(Pageable pageable) {
        Page<PaymentResponse> page = paymentService.listDeleted(pageable).map(PaymentResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
