package com.bento.crm.inbound.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.drive.model.DriveFolder;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.inbound.mail.RawEmail;
import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.inbound.model.InboundMailbox;
import com.bento.crm.inbound.repository.InboundDocumentRepository;
import com.bento.crm.inbound.repository.InboundMailboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Where documents enter: emails sent to an organization's inbox addresses, WhatsApp documents
 * and manual uploads. Each attachment is filed in the drive and queued for reading.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundIntakeService {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Inline logos and signature images are smaller than any real scanned invoice. */
    private static final int MIN_IMAGE_BYTES = 12 * 1024;
    private static final int MAX_ATTACHMENTS_PER_EMAIL = 20;
    private static final int MAX_DOCUMENTS_PER_DAY = 500;

    private final InboundMailboxRepository mailboxRepository;
    private final InboundDocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final com.bento.crm.file.repository.StoredFileRepository fileRepository;
    private final DriveService driveService;
    private final JdbcTemplate jdbc;

    @Value("${app.inbound.domain:in.crmbento.com}")
    private String domain;

    // Mailboxes

    /** The organization's two inbox addresses, created on first use. */
    @Transactional
    public List<InboundMailbox> mailboxes(UUID orgId) {
        List<InboundMailbox> existing = mailboxRepository.forOrganization(orgId);
        if (existing.size() == InboundMailbox.Kind.values().length) {
            return existing;
        }
        for (InboundMailbox.Kind kind : InboundMailbox.Kind.values()) {
            if (existing.stream().noneMatch(m -> m.getKind() == kind)) {
                String prefix = kind == InboundMailbox.Kind.VENDOR_INVOICES ? "factures-" : "releves-";
                jdbc.update("INSERT INTO inbound_mailbox (organization_id, kind, token) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        orgId, kind.name(), prefix + randomToken(8));
            }
        }
        return mailboxRepository.forOrganization(orgId);
    }

    public String address(InboundMailbox m) {
        return m.getToken() + "@" + domain;
    }

    // Email

    /** Files every usable attachment of an email sent to one of our inbox addresses. */
    public int ingest(RawEmail email) {
        Map<UUID, InboundMailbox> targets = new LinkedHashMap<>();
        for (String recipient : email.recipients()) {
            mailboxFor(recipient).ifPresent(m -> targets.putIfAbsent(m.getId(), m));
        }
        if (targets.isEmpty()) {
            log.info("Inbound email for unknown address(es) {} ignored", email.recipients());
            return 0;
        }
        int filed = 0;
        for (InboundMailbox mailbox : targets.values()) {
            if (!mailbox.isActive()) {
                continue;
            }
            InboundDocument.Kind kind = mailbox.getKind() == InboundMailbox.Kind.BANK_STATEMENTS
                    ? InboundDocument.Kind.BANK_STATEMENT : InboundDocument.Kind.VENDOR_INVOICE;
            int count = 0;
            for (RawEmail.Attachment a : email.attachments()) {
                if (count >= MAX_ATTACHMENTS_PER_EMAIL) {
                    break;
                }
                String type = sniff(a.content(), a.contentType(), a.fileName());
                if (!acceptable(kind, type, a.content())) {
                    continue;
                }
                count++;
                InboundDocument doc = intake(mailbox.getOrganizationId(), mailbox.getId(), InboundDocument.Channel.EMAIL, kind,
                        a.content(), a.fileName(), type, email.from(), email.subject(), email.messageId(), email.receivedAt(), null);
                if (doc != null) {
                    if (email.senderVerified()) {
                        jdbc.update("UPDATE inbound_document SET sender_verified = TRUE WHERE id = ?", doc.getId());
                    }
                    filed++;
                }
            }
        }
        return filed;
    }

    java.util.Optional<InboundMailbox> mailboxFor(String address) {
        if (address == null) {
            return java.util.Optional.empty();
        }
        String a = address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        if (at <= 0 || !a.substring(at + 1).equals(domain.toLowerCase(Locale.ROOT))) {
            return java.util.Optional.empty();
        }
        String local = a.substring(0, at);
        // Plus addressing (docs+factures-xxxx@…) lets a single catch-all mailbox serve every token.
        if (local.contains("+")) {
            local = local.substring(local.indexOf('+') + 1);
        }
        return mailboxRepository.findByToken(local);
    }

    // Any channel

    /**
     * Stores one document and queues it. Returns null when the same file was already received
     * (or the daily cap is reached), so a re-sent email does not create a second invoice.
     */
    public InboundDocument intake(UUID orgId, UUID mailboxId, InboundDocument.Channel channel, InboundDocument.Kind kind,
                                  byte[] content, String fileName, String contentType, String sender, String subject,
                                  String messageId, Instant receivedAt, UUID partnerId) {
        UUID previous = TenantContext.currentOrganizationIdOrNull();
        TenantContext.setCurrentOrganizationId(orgId);
        try {
            return doIntake(orgId, mailboxId, channel, kind, content, fileName, contentType, sender, subject, messageId,
                    receivedAt, partnerId);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentOrganizationId(previous);
            }
        }
    }

    private InboundDocument doIntake(UUID orgId, UUID mailboxId, InboundDocument.Channel channel, InboundDocument.Kind kind,
                                       byte[] content, String fileName, String contentType, String sender, String subject,
                                       String messageId, Instant receivedAt, UUID partnerId) {
        String sha = sha256(content);
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM inbound_document d JOIN stored_file f ON f.id = d.file_id"
                + " WHERE d.organization_id = ? AND f.sha256 = ? AND d.deleted_at IS NULL", Integer.class, orgId, sha);
        if (seen != null && seen > 0) {
            log.info("Inbound document {} for org {} already received, skipped", fileName, orgId);
            return null;
        }
        Integer today = jdbc.queryForObject("SELECT count(*) FROM inbound_document WHERE organization_id = ?"
                + " AND created_at > now() - interval '1 day'", Integer.class, orgId);
        if (today != null && today >= MAX_DOCUMENTS_PER_DAY) {
            log.warn("Inbound document cap reached for org {}, {} dropped", orgId, fileName);
            return null;
        }
        Instant when = receivedAt == null ? Instant.now() : receivedAt;
        YearMonth month = YearMonth.from(when.atZone(ZoneId.of("Africa/Casablanca")));
        DriveFolder folder = switch (kind) {
            case VENDOR_INVOICE -> driveService.systemFolder(orgId, DriveService.VENDOR_INVOICES, month);
            case BANK_STATEMENT -> driveService.systemFolder(orgId, DriveService.BANK_STATEMENTS, month);
            case OTHER -> partnerId != null ? driveService.partnerFolder(orgId, partnerId)
                    : driveService.systemFolder(orgId, DriveService.RECEIVED, month);
        };
        StoredFile.Source source = switch (channel) {
            case EMAIL -> StoredFile.Source.EMAIL;
            case WHATSAPP -> StoredFile.Source.WHATSAPP;
            case UPLOAD -> StoredFile.Source.UPLOAD;
        };
        StoredFile file = fileStorageService.storeBytes(orgId, content, fileName == null ? defaultName(contentType) : fileName,
                contentType, null, null, source, folder.getId());
        if (partnerId != null) {
            file.setPartnerId(partnerId);
            file = fileRepository.save(file);
        }

        InboundDocument doc = new InboundDocument();
        doc.setOrganizationId(orgId);
        doc.setMailboxId(mailboxId);
        doc.setChannel(channel);
        doc.setKind(kind);
        doc.setStatus(InboundDocument.Status.QUEUED);
        doc.setFileId(file.getId());
        doc.setSender(truncate(sender, 320));
        doc.setSubject(truncate(subject, 500));
        doc.setMessageId(truncate(messageId, 500));
        doc.setReceivedAt(when);
        doc.setPartnerId(partnerId);
        return documentRepository.save(doc);
    }

    // Helpers

    /** Mail clients often label PDFs application/octet-stream: trust the bytes over the label. */
    static String sniff(byte[] b, String declared, String fileName) {
        if (b == null || b.length < 4) {
            return declared;
        }
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (b[0] == 'P' && b[1] == 'K' && name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if ((b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".csv") || "text/csv".equals(declared)) return "text/csv";
        if (name.endsWith(".txt") || name.endsWith(".ofx") || name.endsWith(".mt940") || name.endsWith(".sta")) return "text/plain";
        return declared;
    }

    /** Invoices are PDFs or pictures; statements may also be spreadsheets or CSV exports. */
    static boolean acceptable(InboundDocument.Kind kind, String type, byte[] content) {
        if (type == null || content == null || content.length == 0) {
            return false;
        }
        if (type.startsWith("image/") && content.length < MIN_IMAGE_BYTES) {
            return false;
        }
        boolean document = type.equals("application/pdf") || type.equals("image/png") || type.equals("image/jpeg") || type.equals("image/webp");
        if (kind == InboundDocument.Kind.BANK_STATEMENT) {
            return document || type.equals("text/csv") || type.equals("text/plain") || type.equals("application/vnd.ms-excel")
                    || type.startsWith("application/vnd.openxmlformats");
        }
        return document;
    }

    private static String defaultName(String contentType) {
        if ("application/pdf".equals(contentType)) return "document.pdf";
        if (contentType != null && contentType.startsWith("image/")) return "document." + contentType.substring(6);
        return "document";
    }

    static String randomToken(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
