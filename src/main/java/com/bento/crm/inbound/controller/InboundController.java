package com.bento.crm.inbound.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.inbound.dto.InboundDtos;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.service.InboundReviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Received documents (email, WhatsApp, upload), their review queue and the inbox addresses. */
@RestController
@RequestMapping("/inbound")
@RequiredArgsConstructor
@Tag(name = "Documents inbox")
public class InboundController {

    private static final String CAN_SEE = "hasAnyAuthority('INVOICES_READ', 'PAYMENTS_READ')";

    private final InboundReviewService review;

    @GetMapping("/settings")
    @PreAuthorize(CAN_SEE)
    public InboundDtos.Settings settings() {
        return review.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    public InboundDtos.Settings updateSettings(@RequestBody InboundDtos.SettingsUpdate request) {
        return review.updateSettings(request);
    }

    @PutMapping("/mailboxes/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    public InboundDtos.Mailbox updateMailbox(@PathVariable UUID id, @RequestBody InboundDtos.MailboxUpdate request) {
        return review.updateMailbox(id, request);
    }

    @PostMapping("/mailboxes/{id}/rotate")
    @PreAuthorize("hasAuthority('ADMIN_ACCESS')")
    public InboundDtos.Mailbox rotate(@PathVariable UUID id) {
        return review.rotate(id);
    }

    @GetMapping("/documents")
    @PreAuthorize(CAN_SEE)
    public PageResponse<InboundDtos.Document> list(@RequestParam(required = false) Set<InboundDocument.Status> status,
                                                   @RequestParam(required = false) Set<InboundDocument.Kind> kind,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size) {
        return review.list(status, kind, Math.max(0, page), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/documents/counts")
    @PreAuthorize(CAN_SEE)
    public Map<String, Long> counts() {
        return review.counts();
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize(CAN_SEE)
    public InboundDtos.Document get(@PathVariable UUID id) {
        return review.get(id);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public InboundDtos.Document upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "VENDOR_INVOICE") InboundDocument.Kind kind,
                                       @RequestParam(required = false) UUID partnerId) throws IOException {
        return review.upload(file, kind, partnerId);
    }

    @PostMapping("/documents/{id}/book")
    @PreAuthorize("hasAuthority('INVOICES_CREATE')")
    public InboundDtos.Document book(@PathVariable UUID id, @RequestBody InboundDtos.BookRequest request) {
        return review.book(id, request);
    }

    @PostMapping("/documents/{id}/ignore")
    @PreAuthorize(CAN_SEE)
    public InboundDtos.Document ignore(@PathVariable UUID id) {
        return review.ignore(id);
    }

    @PostMapping("/documents/{id}/retry")
    @PreAuthorize(CAN_SEE)
    public InboundDtos.Document retry(@PathVariable UUID id) {
        return review.retry(id);
    }
}
