package com.bento.crm.portal.controller;

import com.bento.crm.checklist.dto.ChecklistDtos;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.portal.PortalPrincipal;
import com.bento.crm.portal.dto.PortalDtos;
import com.bento.crm.portal.service.PortalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** The client portal, for a signed-in client of the firm. Staff tokens are not accepted here. */
@RestController
@RequestMapping("/portal")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PORTAL_CLIENT')")
@Tag(name = "Client portal")
public class PortalController {

    private final PortalService portal;

    @GetMapping("/me")
    public PortalDtos.Me me() {
        return portal.me(PortalPrincipal.current());
    }

    @GetMapping("/home")
    public PortalDtos.Home home() {
        return portal.home(PortalPrincipal.current());
    }

    @GetMapping("/invoices")
    public List<PortalDtos.Invoice> invoices() {
        return portal.invoices(PortalPrincipal.current());
    }

    @GetMapping(value = "/invoices/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        var pdf = portal.invoicePdf(PortalPrincipal.current(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.getKey()).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.getValue());
    }

    @GetMapping("/checklists")
    public List<ChecklistDtos.Summary> checklists() {
        return portal.checklists(PortalPrincipal.current());
    }

    @GetMapping("/checklists/{id}")
    public ChecklistDtos.Detail checklist(@PathVariable UUID id) {
        return portal.checklist(PortalPrincipal.current(), id);
    }

    @PostMapping(value = "/checklist-items/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChecklistDtos.Detail upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return portal.upload(PortalPrincipal.current(), id, file);
    }

    @GetMapping("/checklist-items/{id}/file")
    public ResponseEntity<Resource> checklistFile(@PathVariable UUID id) {
        return download(portal.checklistFile(PortalPrincipal.current(), id));
    }

    @GetMapping("/missions")
    public List<PortalDtos.Mission> missions() {
        return portal.missions(PortalPrincipal.current());
    }

    @GetMapping("/deliverables/{id}/file")
    public ResponseEntity<Resource> deliverableFile(@PathVariable UUID id) {
        return download(portal.deliverableFile(PortalPrincipal.current(), id));
    }

    @GetMapping("/tax-deadlines")
    public List<PortalDtos.TaxDeadline> taxDeadlines() {
        return portal.taxDeadlines(PortalPrincipal.current());
    }

    private ResponseEntity<Resource> download(StoredFile f) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(f.getContentType());
        } catch (RuntimeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.getFileName() == null ? "document" : f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(portal.open(f));
    }
}
