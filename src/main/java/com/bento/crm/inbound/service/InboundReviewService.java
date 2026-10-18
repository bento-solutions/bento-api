package com.bento.crm.inbound.service;

import com.bento.crm.ai.AiProperties;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.inbound.dto.InboundDtos;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.model.InboundMailbox;
import com.bento.crm.inbound.repository.InboundDocumentRepository;
import com.bento.crm.inbound.repository.InboundMailboxRepository;
import com.bento.crm.invoice.model.Invoice;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What people do with received documents: see the queue, check and book an invoice the AI could
 * not book on its own, set one aside, and manage the inbox addresses.
 */
@Service
@RequiredArgsConstructor
public class InboundReviewService {

    /** Who may see which kind of document: invoices with invoice rights, statements with payment rights. */
    static final Map<InboundDocument.Kind, String> READ_AUTHORITY = Map.of(
            InboundDocument.Kind.VENDOR_INVOICE, "INVOICES_READ",
            InboundDocument.Kind.BANK_STATEMENT, "PAYMENTS_READ",
            InboundDocument.Kind.OTHER, "FILES_READ");

    /** Who may change (set aside, read again) which kind of document. */
    static final Map<InboundDocument.Kind, String> WRITE_AUTHORITY = Map.of(
            InboundDocument.Kind.VENDOR_INVOICE, "INVOICES_WRITE",
            InboundDocument.Kind.BANK_STATEMENT, "PAYMENTS_WRITE",
            InboundDocument.Kind.OTHER, "FILES_WRITE");

    private final InboundIntakeService intake;
    private final InboundMailboxRepository mailboxRepository;
    private final InboundDocumentRepository documentRepository;
    private final VendorInvoiceBooker booker;
    private final AiProperties aiProperties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // Settings

    @Transactional
    public InboundDtos.Settings settings() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        List<InboundDtos.Mailbox> boxes = intake.mailboxes(orgId).stream().map(this::view).toList();
        Boolean wa = jdbc.queryForObject("SELECT whatsapp_documents FROM organization WHERE id = ?", Boolean.class, orgId);
        return new InboundDtos.Settings(aiProperties.isConfigured(), aiProperties.getDocumentModel(),
                Boolean.TRUE.equals(wa), boxes);
    }

    @Transactional
    public InboundDtos.Settings updateSettings(InboundDtos.SettingsUpdate r) {
        if (r.whatsappDocuments() != null) {
            jdbc.update("UPDATE organization SET whatsapp_documents = ? WHERE id = ?", r.whatsappDocuments(),
                    TenantContext.getCurrentOrganizationId());
        }
        return settings();
    }

    @Transactional
    public InboundDtos.Mailbox updateMailbox(UUID id, InboundDtos.MailboxUpdate r) {
        InboundMailbox m = mailbox(id);
        if (r.allowedSenders() != null) {
            List<String> clean = new ArrayList<>();
            for (String s : r.allowedSenders()) {
                String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
                if (v.isEmpty()) continue;
                if (!v.matches("^[^@\\s]*@[a-z0-9.-]+\\.[a-z]{2,}$")) {
                    throw new IllegalArgumentException("Not an email address or @domain: " + s);
                }
                if (!clean.contains(v)) clean.add(v);
            }
            if (clean.size() > 100) {
                throw new IllegalArgumentException("At most 100 trusted senders");
            }
            m.setAllowedSenders(clean.toArray(String[]::new));
        }
        if (r.autoBook() != null) m.setAutoBook(r.autoBook());
        if (r.active() != null) m.setActive(r.active());
        return view(mailboxRepository.save(m));
    }

    /** Gives an inbox a new address, for when the old one leaked and attracts spam. */
    @Transactional
    public InboundDtos.Mailbox rotate(UUID id) {
        InboundMailbox m = mailbox(id);
        String prefix = m.getKind() == InboundMailbox.Kind.VENDOR_INVOICES ? "factures-" : "releves-";
        m.setToken(prefix + InboundIntakeService.randomToken(8));
        return view(mailboxRepository.save(m));
    }

    private InboundMailbox mailbox(UUID id) {
        return mailboxRepository.find(TenantContext.getCurrentOrganizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox not found"));
    }

    private InboundDtos.Mailbox view(InboundMailbox m) {
        return new InboundDtos.Mailbox(m.getId(), m.getKind().name(), intake.address(m), Arrays.asList(m.getAllowedSenders()),
                m.isAutoBook(), m.isActive());
    }

    // Documents

    @Transactional(readOnly = true)
    public PageResponse<InboundDtos.Document> list(Set<InboundDocument.Status> statuses, Set<InboundDocument.Kind> kinds,
                                                   int page, int size) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Set<InboundDocument.Kind> allowed = readableKinds(kinds);
        if (allowed.isEmpty()) {
            return PageResponse.fromPage(new PageImpl<>(List.of(), PageRequest.of(page, size), 0));
        }
        Set<InboundDocument.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(InboundDocument.Status.class) : statuses;
        String[] k = allowed.stream().map(Enum::name).toArray(String[]::new);
        String[] s = st.stream().map(Enum::name).toArray(String[]::new);
        Long total = jdbc.queryForObject("SELECT count(*) FROM inbound_document d WHERE d.organization_id = ?"
                + " AND d.deleted_at IS NULL AND d.kind = ANY(?) AND d.status = ANY(?)", Long.class, orgId, k, s);
        List<InboundDtos.Document> rows = jdbc.query(SELECT + " WHERE d.organization_id = ? AND d.deleted_at IS NULL"
                + " AND d.kind = ANY(?) AND d.status = ANY(?) ORDER BY d.received_at DESC LIMIT ? OFFSET ?",
                this::row, orgId, k, s, size, (long) page * size);
        return PageResponse.fromPage(new PageImpl<>(rows, PageRequest.of(page, size), total == null ? 0 : total));
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        String[] k = readableKinds(null).stream().map(Enum::name).toArray(String[]::new);
        Map<String, Long> out = new LinkedHashMap<>();
        for (InboundDocument.Status s : InboundDocument.Status.values()) out.put(s.name(), 0L);
        jdbc.query("SELECT status, count(*) FROM inbound_document WHERE organization_id = ? AND deleted_at IS NULL"
                + " AND kind = ANY(?) GROUP BY status", rs -> { out.put(rs.getString(1), rs.getLong(2)); }, orgId, k);
        return out;
    }

    @Transactional(readOnly = true)
    public InboundDtos.Document get(UUID id) {
        return jdbc.query(SELECT + " WHERE d.organization_id = ? AND d.id = ? AND d.deleted_at IS NULL", this::row,
                        TenantContext.getCurrentOrganizationId(), id).stream()
                .filter(d -> CurrentUser.hasAuthority(READ_AUTHORITY.get(InboundDocument.Kind.valueOf(d.kind()))))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    /** A person drops a file in: it joins the queue like an emailed one. */
    public InboundDtos.Document upload(MultipartFile file, InboundDocument.Kind kind, UUID partnerId) throws IOException {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        if (!CurrentUser.hasAuthority(READ_AUTHORITY.get(kind))) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
        byte[] content = file.getBytes();
        String type = InboundIntakeService.sniff(content, file.getContentType(), file.getOriginalFilename());
        if (!InboundIntakeService.acceptable(kind, type, content)) {
            throw new IllegalArgumentException(kind == InboundDocument.Kind.BANK_STATEMENT
                    ? "Send a PDF, a picture, a CSV or an Excel file"
                    : "Send a PDF or a picture (JPEG, PNG or WebP)");
        }
        InboundDocument doc = intake.intake(orgId, null, InboundDocument.Channel.UPLOAD, kind, content,
                file.getOriginalFilename(), type, null, null, null, Instant.now(), partnerId);
        if (doc == null) {
            throw new IllegalStateException("This file was already received");
        }
        return get(doc.getId());
    }

    /** Books an invoice after a person checked (and maybe corrected) what was read. */
    @Transactional
    public InboundDtos.Document book(UUID id, InboundDtos.BookRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        InboundDocument doc = document(orgId, id);
        if (doc.getKind() != InboundDocument.Kind.VENDOR_INVOICE) {
            throw new IllegalArgumentException("Only supplier invoices are booked here");
        }
        if (doc.getStatus() == InboundDocument.Status.BOOKED) {
            throw new IllegalStateException("This invoice is already booked");
        }
        if (r.total() == null || r.total().signum() <= 0) {
            throw new IllegalArgumentException("Enter the total (TTC)");
        }
        if (r.partnerId() == null && (r.supplierName() == null || r.supplierName().isBlank())) {
            throw new IllegalArgumentException("Choose the supplier or enter its name");
        }
        Invoice invoice = booker.book(orgId, doc, new VendorInvoiceBooker.Fields(r.partnerId(), r.supplierName(),
                r.supplierIce(), r.supplierIf(), r.supplierEmail(), r.supplierPhone(), r.invoiceNumber(), r.invoiceDate(),
                r.dueDate(), r.subtotal(), r.tax(), r.total(), r.lines()));
        doc.setStatus(InboundDocument.Status.BOOKED);
        doc.setInvoiceId(invoice.getId());
        doc.setPartnerId(invoice.getPartnerId());
        doc.setReviewReason(null);
        doc.setProcessedAt(Instant.now());
        documentRepository.saveAndFlush(doc);
        return get(id);
    }

    @Transactional
    public InboundDtos.Document ignore(UUID id) {
        InboundDocument doc = writable(document(TenantContext.getCurrentOrganizationId(), id));
        if (doc.getStatus() == InboundDocument.Status.BOOKED) {
            throw new IllegalStateException("A booked invoice is removed from Finance, not from here");
        }
        doc.setStatus(InboundDocument.Status.IGNORED);
        doc.setProcessedAt(Instant.now());
        documentRepository.saveAndFlush(doc);
        return get(id);
    }

    /** Reads the document again (after AI was set up, or a temporary failure). */
    @Transactional
    public InboundDtos.Document retry(UUID id) {
        InboundDocument doc = writable(document(TenantContext.getCurrentOrganizationId(), id));
        if (doc.getStatus() == InboundDocument.Status.BOOKED || doc.getStatus() == InboundDocument.Status.QUEUED) {
            throw new IllegalStateException("This document cannot be read again");
        }
        doc.setStatus(InboundDocument.Status.QUEUED);
        doc.setAttempts(0);
        doc.setNextAttemptAt(null);
        doc.setError(null);
        documentRepository.saveAndFlush(doc);
        return get(id);
    }

    private static InboundDocument writable(InboundDocument d) {
        if (!CurrentUser.hasAuthority(WRITE_AUTHORITY.get(d.getKind()))) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
        return d;
    }

    /** Locks the row first, so a decision by a person and the AI reader never interleave. */
    private InboundDocument document(UUID orgId, UUID id) {
        jdbc.queryForList("SELECT id FROM inbound_document WHERE id = ? AND organization_id = ? FOR UPDATE", UUID.class, id, orgId);
        InboundDocument d = documentRepository.find(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (!CurrentUser.hasAuthority(READ_AUTHORITY.get(d.getKind()))) {
            throw new ResourceNotFoundException("Document not found");
        }
        return d;
    }

    private static Set<InboundDocument.Kind> readableKinds(Set<InboundDocument.Kind> wanted) {
        Set<InboundDocument.Kind> out = EnumSet.noneOf(InboundDocument.Kind.class);
        for (InboundDocument.Kind k : InboundDocument.Kind.values()) {
            if ((wanted == null || wanted.isEmpty() || wanted.contains(k)) && CurrentUser.hasAuthority(READ_AUTHORITY.get(k))) {
                out.add(k);
            }
        }
        return out;
    }

    private static final String SELECT = """
            SELECT d.*, f.file_name, f.content_type, f.size_bytes AS file_size,
                   coalesce(nullif(p.company_name, ''), p.name) AS partner_name, i.invoice_number
            FROM inbound_document d
            JOIN stored_file f ON f.id = d.file_id
            LEFT JOIN partner p ON p.id = d.partner_id
            LEFT JOIN invoice i ON i.id = d.invoice_id AND i.deleted_at IS NULL
            """;

    private InboundDtos.Document row(ResultSet rs, int i) throws SQLException {
        Map<String, Object> extraction = null;
        String json = rs.getString("extraction");
        if (json != null) {
            try {
                extraction = objectMapper.readValue(json, new TypeReference<>() {});
            } catch (IOException e) {
                extraction = null;
            }
        }
        java.sql.Timestamp processed = rs.getTimestamp("processed_at");
        return new InboundDtos.Document(rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("kind"),
                rs.getString("status"), rs.getObject("file_id", UUID.class), rs.getString("file_name"),
                rs.getString("content_type"), rs.getObject("file_size") == null ? null : rs.getLong("file_size"),
                rs.getString("sender"), rs.getString("subject"), rs.getTimestamp("received_at").toInstant(), extraction,
                rs.getBigDecimal("confidence"), rs.getString("review_reason"), rs.getObject("partner_id", UUID.class),
                rs.getString("partner_name"), rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                rs.getString("error"), processed == null ? null : processed.toInstant());
    }
}
