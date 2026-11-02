package com.bento.crm.ledger.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.ledger.dto.LedgerClosingDtos.*;
import com.bento.crm.ledger.service.CashRegisterService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** The cash register: balance and movements of the cash account. */
@RestController
@RequestMapping("/cash")
@RequiredArgsConstructor
@Tag(name = "Cash register")
public class CashController {

    private final CashRegisterService cash;

    @GetMapping("/register")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    public CashRegister register() {
        return cash.register();
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    public PageResponse<CashMovement> movements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return cash.movements(from, to, page, size);
    }

    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('PAYMENTS_WRITE')")
    public ResponseEntity<CashMovement> create(@Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.create(request));
    }

    @DeleteMapping("/movements/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_WRITE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        cash.delete(id);
        return ResponseEntity.noContent().build();
    }
}
