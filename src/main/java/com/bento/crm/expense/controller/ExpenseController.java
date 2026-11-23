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
        var page = service.list(from, to, category, pageable);
        var refs = service.reportRefs(page.getContent());
        Names names = service.names(page.getContent());
        return PageResponse.fromPage(page.map(e -> {
            ReportRef ref = e.getExpenseReportId() == null ? null : refs.get(e.getExpenseReportId());
            return ExpenseResponse.of(e, ref == null ? null : ref.number(), ref == null ? null : ref.status(), names);
        }));
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
        var e = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExpenseResponse.of(e, null, null, service.names(java.util.List.of(e))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_WRITE')")
    public ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        var e = service.update(id, request);
        return ExpenseResponse.of(e, null, null, service.names(java.util.List.of(e)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENTS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
