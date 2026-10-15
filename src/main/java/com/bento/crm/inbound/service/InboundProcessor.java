package com.bento.crm.inbound.service;

import com.bento.crm.ai.ClaudeClient;
import com.bento.crm.ai.DocumentExtraction;
import com.bento.crm.ai.DocumentExtractor;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.model.InboundMailbox;
import com.bento.crm.inbound.repository.InboundDocumentRepository;
import com.bento.crm.inbound.repository.InboundMailboxRepository;
import com.bento.crm.invoice.model.Invoice;
import com.bento.crm.notification.service.TeamNotifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works through the queue of received documents: reads each vendor invoice with the AI, books
 * it when every check passes, and otherwise puts it in the review queue for a person.
 *
 * <p>Documents are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease, so several app
 * instances can run this side by side, and a crashed run is picked up again later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundProcessor {

    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 10;

    private final InboundDocumentRepository documentRepository;
    private final InboundMailboxRepository mailboxRepository;
    private final StoredFileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final DocumentExtractor extractor;
    private final VendorInvoiceBooker booker;
    private final TeamNotifier teamNotifier;
    private final List<InboundDocumentHandler> handlers;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("${app.inbound.processing-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.inbound.process-delay-ms:20000}", initialDelayString = "${app.inbound.process-delay-ms:20000}")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** Processes what is due now; returns how many documents were handled. */
    public int runOnce() {
        int done = 0;
        for (UUID id : claim()) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.error("Inbound document {} failed unexpectedly", id, e);
            }
            done++;
        }
        return done;
    }

    /** Leases a batch: other runs skip these rows until the lease expires. */
    private List<UUID> claim() {
        return jdbc.queryForList("""
                UPDATE inbound_document SET attempts = attempts + 1, next_attempt_at = now() + interval '10 minutes'
                WHERE id IN (SELECT id FROM inbound_document
                             WHERE status = 'QUEUED' AND deleted_at IS NULL
                               AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                             ORDER BY received_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id
                """, UUID.class, BATCH);
    }

    void process(UUID id) {
        InboundDocument doc = documentRepository.findById(id).orElse(null);
        if (doc == null || doc.getStatus() != InboundDocument.Status.QUEUED) {
            return;
        }
        TenantContext.setCurrentOrganizationId(doc.getOrganizationId());
        try {
            StoredFile file = fileRepository.findById(doc.getFileId()).orElse(null);
            if (file == null) {
                finish(doc, InboundDocument.Status.FAILED, null, "The file is missing");
                return;
            }
            if (doc.getKind() == InboundDocument.Kind.VENDOR_INVOICE) {
                vendorInvoice(doc, file);
                return;
            }
            for (InboundDocumentHandler h : handlers) {
                if (h.handles(doc.getKind())) {
                    byte[] content = fileStorageService.readAll(file);
                    tx.executeWithoutResult(s -> h.handle(doc, content, file.getContentType(), file.getFileName()));
                    return;
                }
            }
            finish(doc, InboundDocument.Status.FILED, null, null);
        } catch (RuntimeException e) {
            retryOrFail(doc, e);
        } finally {
            TenantContext.clear();
        }
    }

    private void vendorInvoice(InboundDocument doc, StoredFile file) {
        if (!extractor.available()) {
            finish(doc, InboundDocument.Status.NEEDS_REVIEW, "AI reading is not set up: enter the invoice by hand", null);
            return;
        }
        DocumentExtraction e = extractor.extract(fileStorageService.readAll(file), file.getContentType(), file.getFileName());
        InboundMailbox mailbox = doc.getMailboxId() == null ? null
                : mailboxRepository.find(doc.getOrganizationId(), doc.getMailboxId()).orElse(null);
        tx.executeWithoutResult(s -> {
            InboundDocument d = documentRepository.findById(doc.getId()).orElseThrow();
            d.setExtraction(toMap(e));
            d.setConfidence(e.confidence() == null ? null : BigDecimal.valueOf(e.confidence()).setScale(3, RoundingMode.HALF_UP));
            VendorInvoiceBooker.Decision decision = booker.decide(d.getOrganizationId(), d, mailbox, e);
            d.setPartnerId(decision.partnerId() != null ? decision.partnerId() : d.getPartnerId());
            d.setProcessedAt(Instant.now());
            d.setNextAttemptAt(null);
            d.setError(null);
            if (decision.duplicate()) {
                d.setStatus(InboundDocument.Status.DUPLICATE);
                d.setReviewReason(reasons(decision.reasons()));
            } else if (decision.book()) {
                Invoice invoice = booker.book(d.getOrganizationId(), d, VendorInvoiceBooker.Fields.of(e, decision.partnerId()));
                d.setStatus(InboundDocument.Status.BOOKED);
                d.setInvoiceId(invoice.getId());
                d.setPartnerId(invoice.getPartnerId());
                d.setReviewReason(null);
            } else {
                d.setStatus(InboundDocument.Status.NEEDS_REVIEW);
                d.setReviewReason(reasons(decision.reasons()));
            }
            documentRepository.save(d);
            if (d.getStatus() == InboundDocument.Status.NEEDS_REVIEW) {
                teamNotifier.notifyManagers(d.getOrganizationId(), "Invoice to check: " + label(e, file),
                        d.getReviewReason(), "INBOUND_DOCUMENT", d.getId());
            }
        });
    }

    private void retryOrFail(InboundDocument doc, RuntimeException e) {
        boolean last = doc.getAttempts() >= MAX_ATTEMPTS || !(e instanceof ClaudeClient.AiException);
        log.warn("Inbound document {} attempt {} failed: {}", doc.getId(), doc.getAttempts(), e.getMessage());
        tx.executeWithoutResult(s -> documentRepository.findById(doc.getId()).ifPresent(d -> {
            d.setError(truncate(e.getMessage()));
            if (last) {
                // Reading failed for good: a person enters the invoice from the file instead.
                d.setStatus(doc.getKind() == InboundDocument.Kind.VENDOR_INVOICE
                        ? InboundDocument.Status.NEEDS_REVIEW : InboundDocument.Status.FAILED);
                d.setReviewReason("The document could not be read automatically");
                d.setNextAttemptAt(null);
                d.setProcessedAt(Instant.now());
            } else {
                d.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(5L << d.getAttempts())));
            }
            documentRepository.save(d);
        }));
    }

    private void finish(InboundDocument doc, InboundDocument.Status status, String reason, String error) {
        tx.executeWithoutResult(s -> documentRepository.findById(doc.getId()).ifPresent(d -> {
            d.setStatus(status);
            d.setReviewReason(reason);
            d.setError(error);
            d.setNextAttemptAt(null);
            d.setProcessedAt(Instant.now());
            documentRepository.save(d);
        }));
    }

    private Map<String, Object> toMap(DocumentExtraction e) {
        ObjectMapper m = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m.convertValue(e, new TypeReference<>() {});
    }

    private static String label(DocumentExtraction e, StoredFile f) {
        if (e.supplierName() != null) {
            return e.supplierName() + (e.invoiceNumber() != null ? " " + e.invoiceNumber() : "");
        }
        return f.getFileName() == null ? "document" : f.getFileName();
    }

    private static String reasons(List<String> reasons) {
        return truncate(String.join("; ", reasons));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 255 ? s.substring(0, 252) + "..." : s;
    }
}
