package com.bento.crm.ledger.controller;

import com.bento.crm.ledger.dto.LedgerClosingDtos.*;
import com.bento.crm.ledger.service.FixedAssetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Fixed assets (immobilisations), their depreciation, disposal and register. */
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
@Tag(name = "Fixed assets")
public class FixedAssetController {

    private final FixedAssetService assets;

    @GetMapping
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<Asset> list() {
        return assets.list();
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<AssetCategory> categories() {
        return assets.listCategories();
    }

    @GetMapping("/register")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Register register(@RequestParam int year) {
        return assets.register(year);
    }

    @PostMapping("/depreciate")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public DepreciationResult depreciate(@Valid @RequestBody DepreciateRequest request) {
        return assets.depreciate(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Asset get(@PathVariable UUID id) {
        return assets.get(id);
    }

    @GetMapping("/{id}/schedule")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<ScheduleRow> schedule(@PathVariable UUID id) {
        return assets.scheduleOf(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Asset> create(@Valid @RequestBody AssetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assets.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public Asset update(@PathVariable UUID id, @Valid @RequestBody AssetRequest request) {
        return assets.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        assets.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public Asset dispose(@PathVariable UUID id, @Valid @RequestBody DisposeRequest request) {
        return assets.dispose(id, request);
    }
}
