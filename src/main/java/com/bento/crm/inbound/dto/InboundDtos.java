package com.bento.crm.inbound.dto;

import com.bento.crm.ai.DocumentExtraction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InboundDtos {

    private InboundDtos() {}

    public record Mailbox(UUID id, String kind, String address, List<String> allowedSenders, boolean autoBook, boolean active) {}

    public record Settings(boolean aiEnabled, String documentModel, boolean whatsappDocuments, List<Mailbox> mailboxes) {}

    public record MailboxUpdate(List<String> allowedSenders, Boolean autoBook, Boolean active) {}

    public record SettingsUpdate(Boolean whatsappDocuments) {}

    public record Document(UUID id, String channel, String kind, String status, UUID fileId, String fileName,
                           String contentType, Long fileSize, String sender, String subject, Instant receivedAt,
                           Map<String, Object> extraction, BigDecimal confidence, String reviewReason, UUID partnerId,
                           String partnerName, UUID invoiceId, String invoiceNumber, String error, Instant processedAt) {}

    /** What a person confirms or corrects before booking. */
    public record BookRequest(UUID partnerId, String supplierName, String supplierIce, String supplierIf,
                              String supplierEmail, String supplierPhone, String invoiceNumber, LocalDate invoiceDate,
                              LocalDate dueDate, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                              List<DocumentExtraction.Line> lines) {}
}
