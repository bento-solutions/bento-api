package com.bento.crm.bank.controller;

import com.bento.crm.bank.dto.BankDtos;
import com.bento.crm.bank.model.BankTransaction;
import com.bento.crm.bank.service.BankCategories;
import com.bento.crm.bank.service.BankService;
import com.bento.crm.bank.service.CashService;
import com.bento.crm.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bank accounts, statements, reconciliation and the cash dashboard. */
@RestController
@RequestMapping("/bank")
@RequiredArgsConstructor
@Tag(name = "Bank and cash")
public class BankController {

    private static final String READ = "hasAuthority('PAYMENTS_READ')";
    private static final String WRITE = "hasAuthority('PAYMENTS_WRITE')";

    private final BankService bank;
    private final CashService cash;

    @GetMapping("/dashboard")
    @PreAuthorize(READ)
    public BankDtos.Dashboard dashboard() {
        return cash.dashboard();
    }

    @GetMapping("/categories")
    @PreAuthorize(READ)
    public List<String> categories() {
        return BankCategories.ALL;
    }

    @GetMapping("/accounts")
    @PreAuthorize(READ)
    public List<BankDtos.Account> accounts() {
        return bank.accounts();
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize(READ)
    public BankDtos.Account account(@PathVariable UUID id) {
        return bank.account(id);
    }

    @PostMapping("/accounts")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BankDtos.Account create(@RequestBody BankDtos.AccountRequest r) {
        return bank.createAccount(r);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize(WRITE)
    public BankDtos.Account update(@PathVariable UUID id, @RequestBody BankDtos.AccountRequest r) {
        return bank.updateAccount(id, r);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        bank.deleteAccount(id);
    }

    @PostMapping(value = "/accounts/{id}/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BankDtos.ImportResult upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        return bank.upload(id, file);
    }

    @PostMapping("/statements/from-document/{documentId}")
    @PreAuthorize(WRITE)
    public BankDtos.ImportResult importDocument(@PathVariable UUID documentId, @RequestParam UUID accountId) {
        return bank.importDocument(documentId, accountId);
    }

    @GetMapping("/statements")
    @PreAuthorize(READ)
    public List<BankDtos.Import> imports(@RequestParam(required = false) UUID accountId) {
        return bank.imports(accountId);
    }

    @DeleteMapping("/statements/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImport(@PathVariable UUID id) {
        bank.deleteImport(id);
    }

    @GetMapping("/transactions")
    @PreAuthorize(READ)
    public PageResponse<BankDtos.Transaction> transactions(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) BankTransaction.Status status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return bank.transactions(accountId, status, category, q, from, to, direction, Math.max(0, page),
                Math.min(Math.max(size, 1), 200));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize(READ)
    public BankDtos.Transaction transaction(@PathVariable UUID id) {
        return bank.transaction(id);
    }

    @GetMapping("/transactions/{id}/candidates")
    @PreAuthorize(READ)
    public List<BankDtos.Candidate> candidates(@PathVariable UUID id) {
        return bank.candidates(id);
    }

    @PostMapping("/transactions/{id}/match")
    @PreAuthorize(WRITE)
    public BankDtos.Transaction match(@PathVariable UUID id, @RequestBody BankDtos.MatchRequest r) {
        return bank.match(id, r);
    }

    @PostMapping("/transactions/{id}/unmatch")
    @PreAuthorize(WRITE)
    public BankDtos.Transaction unmatch(@PathVariable UUID id) {
        return bank.unmatch(id);
    }

    @PostMapping("/transactions/{id}/ignore")
    @PreAuthorize(WRITE)
    public BankDtos.Transaction ignore(@PathVariable UUID id) {
        return bank.ignore(id, true);
    }

    @PostMapping("/transactions/{id}/restore")
    @PreAuthorize(WRITE)
    public BankDtos.Transaction restore(@PathVariable UUID id) {
        return bank.ignore(id, false);
    }

    @PutMapping("/transactions/{id}/category")
    @PreAuthorize(WRITE)
    public BankDtos.Transaction category(@PathVariable UUID id, @RequestBody BankDtos.CategoryRequest r) {
        return bank.setCategory(id, r);
    }

    @PostMapping("/transactions/categorize")
    @PreAuthorize(WRITE)
    public Map<String, Integer> categorize() {
        return Map.of("categorized", bank.recategorize());
    }

    @GetMapping("/rules")
    @PreAuthorize(READ)
    public List<BankDtos.Rule> rules() {
        return bank.rules();
    }

    @PostMapping("/rules")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BankDtos.Rule createRule(@RequestBody BankDtos.RuleRequest r) {
        return bank.createRule(r);
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        bank.deleteRule(id);
    }
}
