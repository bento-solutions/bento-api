package com.bento.crm.tax.controller;

import com.bento.crm.tax.dto.TaxDtos;
import com.bento.crm.tax.model.TaxDeadline;
import com.bento.crm.tax.service.TaxCalendarService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Moroccan tax deadlines (TVA, IS, IR, CNSS) for the company and its clients. */
@RestController
@RequestMapping("/tax-calendar")
@RequiredArgsConstructor
@Tag(name = "Tax calendar")
public class TaxCalendarController {

    private static final String READ = "hasAnyAuthority('INVOICES_READ', 'PAYMENTS_READ')";
    private static final String WRITE = "hasAnyAuthority('INVOICES_WRITE', 'PAYMENTS_WRITE')";

    private final TaxCalendarService service;

    @GetMapping("/profiles")
    @PreAuthorize(READ)
    public List<TaxDtos.Profile> profiles() {
        return service.profiles();
    }

    @PostMapping("/profiles")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public TaxDtos.Profile create(@RequestBody TaxDtos.ProfileRequest r) {
        return service.saveProfile(null, r);
    }

    @PutMapping("/profiles/{id}")
    @PreAuthorize(WRITE)
    public TaxDtos.Profile update(@PathVariable UUID id, @RequestBody TaxDtos.ProfileRequest r) {
        return service.saveProfile(id, r);
    }

    @DeleteMapping("/profiles/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteProfile(id);
    }

    @GetMapping("/deadlines")
    @PreAuthorize(READ)
    public List<TaxDtos.Deadline> deadlines(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(defaultValue = "false") boolean companyOnly,
            @RequestParam(required = false) TaxDeadline.Status status) {
        LocalDate f = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate t = to == null ? LocalDate.now().plusDays(90) : to;
        if (t.isBefore(f) || t.isAfter(f.plusYears(2))) {
            throw new IllegalArgumentException("Choose a range of up to two years");
        }
        return service.deadlines(f, t, partnerId, companyOnly, status);
    }

    @PutMapping("/deadlines/{id}")
    @PreAuthorize(WRITE)
    public TaxDtos.Deadline updateDeadline(@PathVariable UUID id, @RequestBody TaxDtos.DeadlineUpdate r) {
        return service.update(id, r);
    }
}
