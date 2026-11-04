package com.bento.crm.expense.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.expense.dto.ExpenseReportDtos.*;
import com.bento.crm.expense.service.ExpenseReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** Expense reports (notes de frais). Every write answers with the whole report, lines and totals included. */
@RestController
@RequestMapping("/expense-reports")
@RequiredArgsConstructor
@Tag(name = "Expense reports")
public class ExpenseReportController {

    private static final String READ = "hasAuthority('PAYMENTS_READ')";
    private static final String WRITE = "hasAuthority('PAYMENTS_CREATE')";

    private final ExpenseReportService service;

    /** employee: a user id, or part of the employee's name. from/to: reports whose period overlaps them. */
    @GetMapping
    @PreAuthorize(READ)
    public PageResponse<ExpenseReportResponse> list(
            @RequestParam(required = false) String status, @RequestParam(required = false) String employee,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(status, employee, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ExpenseReportResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseReportResponse create(@Valid @RequestBody ExpenseReportRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseReportRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseReportResponse addLine(@PathVariable UUID id, @Valid @RequestBody LineRequest request) {
        return service.addLine(id, request);
    }

    @PutMapping("/{id}/lines/{expenseId}")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse updateLine(@PathVariable UUID id, @PathVariable UUID expenseId,
                                            @Valid @RequestBody LineRequest request) {
        return service.updateLine(id, expenseId, request);
    }

    @DeleteMapping("/{id}/lines/{expenseId}")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse deleteLine(@PathVariable UUID id, @PathVariable UUID expenseId) {
        return service.deleteLine(id, expenseId);
    }

    @PostMapping("/{id}/post")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse post(@PathVariable UUID id) {
        return service.post(id);
    }

    @PostMapping("/{id}/unpost")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse unpost(@PathVariable UUID id) {
        return service.unpost(id);
    }

    @PostMapping("/{id}/reimburse")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse reimburse(@PathVariable UUID id, @Valid @RequestBody ReimburseRequest request) {
        return service.reimburse(id, request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    public ExpenseReportResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        ExpenseReportResponse r = service.get(id);
        byte[] body = service.pdf(id);
        String name = "note-de-frais-" + (r.number() != null ? r.number() : id.toString().substring(0, 8)) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
