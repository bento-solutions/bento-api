package com.bento.crm.ledger.controller;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.pdf.PdfDocumentService;
import com.bento.crm.invoice.service.InvoiceTemplateService;
import com.bento.crm.ledger.dto.LedgerStatementDtos.*;
import com.bento.crm.ledger.service.LedgerExportService;
import com.bento.crm.ledger.service.LedgerPartnerService;
import com.bento.crm.ledger.service.LedgerStatementsService;
import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/** General ledger phase 4: financial statements, partner statements and balances, the accountant export. */
@RestController
@RequestMapping("/ledger")
@RequiredArgsConstructor
@Tag(name = "General ledger statements")
public class LedgerStatementsController {

    private final LedgerStatementsService statements;
    private final LedgerPartnerService partners;
    private final LedgerExportService export;
    private final PdfDocumentService pdf;
    private final InvoiceTemplateService templates;
    private final OrganizationRepository organizations;

    private static ResponseEntity<byte[]> file(byte[] body, String filename, String type, boolean inline) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment") + "; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }

    // 1. Financial statements

    @GetMapping("/statements/bilan")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Bilan bilan(@RequestParam(required = false) Integer year,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(defaultValue = "false") boolean comparative) {
        return statements.bilan(year, date, comparative);
    }

    @GetMapping("/statements/bilan.xlsx")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public ResponseEntity<byte[]> bilanXlsx(@RequestParam(required = false) Integer year,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @RequestParam(defaultValue = "false") boolean comparative) {
        return file(export.bilanXlsx(year, date, comparative), "bilan" + (year != null ? "-" + year : "") + ".xlsx",
                LedgerExportService.XLSX, false);
    }

    @GetMapping("/statements/cpc")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public Cpc cpc(@RequestParam(required = false) Integer year,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                   @RequestParam(defaultValue = "false") boolean comparative) {
        return statements.cpc(year, from, to, comparative);
    }

    @GetMapping("/statements/cpc.xlsx")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public ResponseEntity<byte[]> cpcXlsx(@RequestParam(required = false) Integer year,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "false") boolean comparative) {
        return file(export.cpcXlsx(year, from, to, comparative), "cpc" + (year != null ? "-" + year : "") + ".xlsx",
                LedgerExportService.XLSX, false);
    }

    // 2. Partner balances and statements

    @GetMapping("/partners/balances")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public PartnerBalances balances(@RequestParam(defaultValue = "CUSTOMER") String type,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(defaultValue = "false") boolean onlyOpen,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return partners.balances(type, from, to, search, onlyOpen, page, size);
    }

    @GetMapping("/partners/balances.xlsx")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public ResponseEntity<byte[]> balancesXlsx(@RequestParam(defaultValue = "CUSTOMER") String type,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(defaultValue = "false") boolean onlyOpen) {
        String name = type != null && type.trim().equalsIgnoreCase("SUPPLIER") ? "balance-fournisseurs" : "balance-clients";
        return file(export.balancesXlsx(type, from, to, search, onlyOpen), name + ".xlsx", LedgerExportService.XLSX, false);
    }

    @GetMapping("/partners/{partnerId}/statement")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public PartnerStatement statement(@PathVariable UUID partnerId, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return partners.statement(partnerId, type, from, to);
    }

    @GetMapping(value = "/partners/{partnerId}/statement.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public ResponseEntity<byte[]> statementPdf(@PathVariable UUID partnerId, @RequestParam(required = false) String type,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        PartnerStatement st = partners.statement(partnerId, type, from, to);
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Partner partner = partners.partner(partnerId);
        Organization org = organizations.findById(orgId).orElse(null);
        byte[] body = pdf.generatePartnerStatementPdf(st, org, templates.brandingFor(orgId, partner));
        return file(body, "releve-" + st.from() + "-" + st.to() + ".pdf", MediaType.APPLICATION_PDF_VALUE, true);
    }

    // 3. Accountant export

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    public void export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "xlsx") String format,
                       HttpServletResponse response) throws IOException {
        String f = LedgerExportService.format(format);
        LedgerExportService.Period p = export.period(from, to);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.filename(f) + "\"");
        if (f.equals("csv")) {
            response.setContentType("text/csv;charset=UTF-8");
            export.csv(p, response.getOutputStream());
        } else {
            response.setContentType(LedgerExportService.XLSX);
            export.xlsx(p, response.getOutputStream());
        }
        response.flushBuffer();
    }
}
