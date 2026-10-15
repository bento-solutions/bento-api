package com.bento.crm.expense.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.expense.dto.ExpenseDtos.*;
import com.bento.crm.expense.service.ExpenseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
@Tag(name = "Expenses")
public class ExpenseController {

    private final ExpenseService service;

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    public PageResponse<ExpenseResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category, Pageable pageable) {
        return PageResponse.fromPage(service.list(from, to, category, pageable).map(ExpenseResponse::of));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PAYMENTS_READ')")
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(from, to);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PAYMENTS_CREATE')")
    public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ExpenseResponse.of(service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_WRITE')")
    public ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        return ExpenseResponse.of(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
