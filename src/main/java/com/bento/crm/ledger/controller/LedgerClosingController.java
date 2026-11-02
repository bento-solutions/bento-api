package com.bento.crm.ledger.controller;

import com.bento.crm.ledger.dto.LedgerClosingDtos.*;
import com.bento.crm.ledger.service.LedgerClosingService;
import com.bento.crm.ledger.service.LedgerCutoffService;
import com.bento.crm.ledger.service.LedgerStockChangeService;
import com.bento.crm.ledger.service.LedgerVatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** General ledger phase 3, wave B: settings and lock date, years, VAT, stock change, cut-off. */
@RestController
@RequestMapping("/ledger")
@RequiredArgsConstructor
@Tag(name = "General ledger closing")
public class LedgerClosingController {

    private final LedgerClosingService closing;
    private final LedgerVatService vat;
    private final LedgerStockChangeService stockChange;
    private final LedgerCutoffService cutoff;

    // Settings and years

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Settings settings() {
        return closing.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public Settings updateSettings(@RequestBody SettingsUpdate request) {
        return closing.updateSettings(request);
    }

    @GetMapping("/years")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<FiscalYear> years() {
        return closing.years();
    }

    @PostMapping("/years/{year}/close")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public FiscalYear close(@PathVariable int year) {
        return closing.close(year);
    }

    @PostMapping("/years/{year}/reopen")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public FiscalYear reopen(@PathVariable int year) {
        return closing.reopen(year);
    }

    // VAT

    @GetMapping("/vat")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public VatSummary vat(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return vat.summary(from, to);
    }

    @PostMapping("/vat/settle")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public VatSummary settle(@Valid @RequestBody VatSettleRequest request) {
        return vat.settle(request);
    }

    @DeleteMapping("/vat/settle")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public VatSummary cancelSettlement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return vat.cancel(from, to);
    }

    // Stock change

    @GetMapping("/stock-change")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public StockChange stockChange(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return stockChange.get(date);
    }

    @PostMapping("/stock-change")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public StockChange postStockChange(@Valid @RequestBody StockChangeRequest request) {
        return stockChange.post(request);
    }

    @DeleteMapping("/stock-change")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public StockChange cancelStockChange(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return stockChange.cancel(date);
    }

    // Cut-off

    @GetMapping("/cutoff/proposals")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public CutoffProposals cutoffProposals(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cutoff.proposals(date);
    }

    @GetMapping("/cutoff")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Cutoff cutoff(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cutoff.get(date);
    }

    @PostMapping("/cutoff")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public ResponseEntity<Cutoff> postCutoff(@Valid @RequestBody CutoffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cutoff.post(request));
    }

    @DeleteMapping("/cutoff")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public Cutoff cancelCutoff(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cutoff.cancel(date);
    }
}
