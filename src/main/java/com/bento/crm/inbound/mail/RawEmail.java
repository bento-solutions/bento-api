package com.bento.crm.inbound.mail;

import java.time.Instant;
import java.util.List;

/**
 * An email reduced to what document intake needs, whichever way it arrived.
 *
 * @param senderVerified the receiving server confirmed the From domain (DMARC, or SPF and DKIM),
 *                       so the sender can be trusted for automatic booking
 */
public record RawEmail(List<String> recipients, String from, String subject, String messageId, Instant receivedAt,
                       List<Attachment> attachments, boolean senderVerified) {

    public record Attachment(String fileName, String contentType, byte[] content) {}
}
