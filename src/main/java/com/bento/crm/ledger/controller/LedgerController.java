package com.bento.crm.ledger.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.ledger.dto.LedgerDtos.*;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.ledger.service.LedgerAccountService;
import com.bento.crm.ledger.service.LedgerAutoPostingService;
import com.bento.crm.ledger.service.LedgerPostingSetupService;
import com.bento.crm.ledger.service.LedgerEntryService;
import com.bento.crm.ledger.service.LedgerReportService;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ledger")
@RequiredArgsConstructor
@Tag(name = "General ledger")
public class LedgerController {

    private final LedgerAccountService accounts;
    private final LedgerEntryService entries;
    private final LedgerReportService reports;
    private final LedgerPostingSetupService postingSetup;
    private final LedgerAutoPostingService autoPosting;

    // Accounts

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<Account> accounts(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return accounts.accounts(includeInactive);
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody AccountCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.createAccount(request));
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public Account updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountUpdate request) {
        return accounts.updateAccount(id, request);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        accounts.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    // Journals

    @GetMapping("/journals")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<Journal> journals() {
        return accounts.journals();
    }

    // Cost centers

    @GetMapping("/cost-centers")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public List<CostCenter> costCenters(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return accounts.costCenters(includeInactive);
    }

    @PostMapping("/cost-centers")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<CostCenter> createCostCenter(@Valid @RequestBody CostCenterCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.createCostCenter(request));
    }

    @PutMapping("/cost-centers/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public CostCenter updateCostCenter(@PathVariable UUID id, @Valid @RequestBody CostCenterUpdate request) {
        return accounts.updateCostCenter(id, request);
    }

    @DeleteMapping("/cost-centers/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Void> deleteCostCenter(@PathVariable UUID id) {
        accounts.deleteCostCenter(id);
        return ResponseEntity.noContent().build();
    }

    // Entries

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public PageResponse<Entry> entries(
            @RequestParam(required = false) UUID journalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) UUID costCenterId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) UUID sourceId,
            Pageable pageable) {
        return entries.list(journalId, from, to, accountId, partnerId, costCenterId, q, sourceType, sourceId, pageable);
    }

    /** The live entry a document (INVOICE, BILL, PAYMENT, EXPENSE, BANK_LINE) posted; 404 when none. */
    @GetMapping("/source/{sourceType}/{sourceId}")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Entry sourceEntry(@PathVariable String sourceType, @PathVariable UUID sourceId) {
        return entries.forSource(sourceType, sourceId, autoPosting);
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Entry entry(@PathVariable UUID id) {
        return entries.get(id);
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Entry> createEntry(@Valid @RequestBody EntryCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(entries.createManual(request));
    }

    @PostMapping("/entries/{id}/reverse")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<Entry> reverse(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) ReverseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(entries.reverse(id, request));
    }

    // Reports

    @GetMapping("/general-ledger")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public GeneralLedger generalLedger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String accountFrom,
            @RequestParam(required = false) String accountTo,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(required = false) UUID costCenterId) {
        return reports.generalLedger(from, to, accountId, accountFrom, accountTo, partnerId, costCenterId);
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public TrialBalance trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer accountClass) {
        return reports.trialBalance(from, to, accountClass);
    }

    // Posting setup

    @GetMapping("/posting-setup")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public PostingSetup postingSetup() {
        return postingSetup.get();
    }

    @PostMapping("/posting-setup/partner-groups")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<PostingSetup> createPartnerGroup(@Valid @RequestBody PartnerGroupCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postingSetup.createPartnerGroup(request));
    }

    @PutMapping("/posting-setup/partner-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updatePartnerGroup(@PathVariable UUID id, @Valid @RequestBody PartnerGroupUpdate request) {
        return postingSetup.updatePartnerGroup(id, request);
    }

    @DeleteMapping("/posting-setup/partner-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup deletePartnerGroup(@PathVariable UUID id) {
        return postingSetup.deletePartnerGroup(id);
    }

    @PostMapping("/posting-setup/business-groups")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<PostingSetup> createBusinessGroup(@Valid @RequestBody GroupCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postingSetup.createBusinessGroup(request));
    }

    @PutMapping("/posting-setup/business-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateBusinessGroup(@PathVariable UUID id, @Valid @RequestBody GroupUpdate request) {
        return postingSetup.updateBusinessGroup(id, request);
    }

    @DeleteMapping("/posting-setup/business-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup deleteBusinessGroup(@PathVariable UUID id) {
        return postingSetup.deleteBusinessGroup(id);
    }

    @PostMapping("/posting-setup/product-groups")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public ResponseEntity<PostingSetup> createProductGroup(@Valid @RequestBody GroupCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postingSetup.createProductGroup(request));
    }

    @PutMapping("/posting-setup/product-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateProductGroup(@PathVariable UUID id, @Valid @RequestBody GroupUpdate request) {
        return postingSetup.updateProductGroup(id, request);
    }

    @DeleteMapping("/posting-setup/product-groups/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup deleteProductGroup(@PathVariable UUID id) {
        return postingSetup.deleteProductGroup(id);
    }

    @PutMapping("/posting-setup/general/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateGeneral(@PathVariable UUID id, @Valid @RequestBody GeneralSetupUpdate request) {
        return postingSetup.updateGeneral(id, request);
    }

    @PutMapping("/posting-setup/vat/{id}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateVat(@PathVariable UUID id, @Valid @RequestBody VatSetupUpdate request) {
        return postingSetup.updateVat(id, request);
    }

    @PutMapping("/posting-setup/bank/{bankAccountId}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateBank(@PathVariable UUID bankAccountId, @Valid @RequestBody AccountAssignment request) {
        return postingSetup.updateBank(bankAccountId, request);
    }

    @PutMapping("/posting-setup/cash")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateCash(@Valid @RequestBody AccountAssignment request) {
        return postingSetup.updateCash(request);
    }

    @PutMapping("/posting-setup/bank-categories/{category}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateBankCategory(@PathVariable String category, @Valid @RequestBody AccountAssignment request) {
        return postingSetup.updateBankCategory(category, request);
    }

    @PutMapping("/posting-setup/expense-categories/{category}")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateExpenseCategory(@PathVariable String category,
                                              @Valid @RequestBody AccountAssignment request) {
        return postingSetup.updateExpenseCategory(category, request);
    }

    @PutMapping("/posting-setup/start-date")
    @PreAuthorize("hasAuthority('LEDGER_WRITE')")
    public PostingSetup updateStartDate(@Valid @RequestBody StartDateUpdate request) {
        return postingSetup.updateStartDate(request);
    }

    /** Posts every document on or after the start date that has no entry yet. Idempotent. */
    @PostMapping("/repost")
    @PreAuthorize("hasAuthority('LEDGER_CLOSE')")
    public RepostResult repost() {
        return autoPosting.repost(TenantContext.getCurrentOrganizationId());
    }
}
