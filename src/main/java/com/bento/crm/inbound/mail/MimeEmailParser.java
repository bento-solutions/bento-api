package com.bento.crm.inbound.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Turns a MIME message (from IMAP or an inbound-mail webhook) into a {@link RawEmail}. */
public final class MimeEmailParser {

    /** Headers that carry the address the message was really delivered to (catch-all mailboxes). */
    private static final String[] DELIVERY_HEADERS = {"Delivered-To", "X-Original-To", "X-Forwarded-To", "Envelope-To"};
    private static final int MAX_PARTS = 50;

    private MimeEmailParser() {}

    public static RawEmail parse(byte[] mime, Set<String> trustedAuthservIds) throws MessagingException, IOException {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
        return parse(message, trustedAuthservIds);
    }

    public static RawEmail parse(MimeMessage message, Set<String> trustedAuthservIds) throws MessagingException, IOException {
        Set<String> recipients = new LinkedHashSet<>();
        for (String header : DELIVERY_HEADERS) {
            String[] values = message.getHeader(header);
            if (values != null) {
                for (String v : values) {
                    addAddresses(recipients, v);
                }
            }
        }
        for (Message.RecipientType type : new Message.RecipientType[]{Message.RecipientType.TO, Message.RecipientType.CC}) {
            Address[] addresses = message.getRecipients(type);
            if (addresses != null) {
                for (Address a : addresses) {
                    if (a instanceof InternetAddress ia && ia.getAddress() != null) {
                        recipients.add(ia.getAddress().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        String from = null;
        Address[] froms = message.getFrom();
        if (froms != null && froms.length > 0 && froms[0] instanceof InternetAddress ia) {
            from = ia.getAddress();
        }
        List<RawEmail.Attachment> attachments = new ArrayList<>();
        collect(message, attachments, 0);
        Instant received = message.getSentDate() != null ? message.getSentDate().toInstant() : Instant.now();
        return new RawEmail(new ArrayList<>(recipients), from, message.getSubject(), message.getMessageID(), received,
                attachments, verified(message.getHeader("Authentication-Results"), from, trustedAuthservIds));
    }

    /**
     * Whether our own receiving server vouched for the sender's domain. A From header alone is easy
     * to forge, so without this an email never books an invoice on its own.
     *
     * <p>Anyone can write Authentication-Results headers into the email they send, so only the
     * topmost one counts (receiving servers add theirs on top), and only when its authserv-id is
     * one of {@code trusted} (our mail provider's). With nothing configured no email is verified.
     *
     * @param results the Authentication-Results headers, topmost first
     */
    public static boolean verified(String[] results, String from, Set<String> trusted) {
        if (results == null || results.length == 0 || from == null || !from.contains("@") || trusted == null
                || trusted.isEmpty()) {
            return false;
        }
        String domain = from.substring(from.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT).trim();
        String top = results[0].toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String[] clauses = top.split(";");
        // "mx.example.net 1" -> the authserv-id is the first word; a version number may follow.
        String authserv = clauses[0].trim().split(" ")[0];
        if (trusted.stream().noneMatch(t -> t.trim().toLowerCase(Locale.ROOT).equals(authserv))) {
            return false;
        }
        boolean spf = false, dkim = false;
        for (int i = 1; i < clauses.length; i++) {
            String c = clauses[i].trim();
            if (c.startsWith("dmarc=pass") && property(c, "header.from", domain)) return true;
            if (c.startsWith("spf=pass") && (property(c, "smtp.mailfrom", domain) || !c.contains("smtp.mailfrom="))) spf = true;
            if (c.startsWith("dkim=pass") && (property(c, "header.d", domain) || property(c, "header.i", domain))) dkim = true;
        }
        return spf && dkim;
    }

    /** {@code name=value} in a clause, where value is exactly the domain (or an address at it). */
    private static boolean property(String clause, String name, String domain) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)" + java.util.regex.Pattern.quote(name)
                + "=([^\\s;()]+)").matcher(clause);
        while (m.find()) {
            String v = m.group(1);
            String d = v.contains("@") ? v.substring(v.lastIndexOf('@') + 1) : v;
            if (d.equals(domain)) return true;
        }
        return false;
    }

    private static void collect(Part part, List<RawEmail.Attachment> out, int depth) throws MessagingException, IOException {
        if (depth > 10 || out.size() >= MAX_PARTS) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount() && i < MAX_PARTS; i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                collect(bodyPart, out, depth + 1);
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            // A forwarded email: its attachments count too.
            Object nested = part.getContent();
            if (nested instanceof Part p) {
                collect(p, out, depth + 1);
            }
            return;
        }
        String fileName = part.getFileName();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || fileName != null;
        if (!isAttachment || part.isMimeType("text/plain") && fileName == null || part.isMimeType("text/html")) {
            return;
        }
        if (fileName != null) {
            fileName = MimeUtility.decodeText(fileName);
        }
        String contentType = part.getContentType() == null ? "application/octet-stream"
                : part.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        try (InputStream in = part.getInputStream()) {
            out.add(new RawEmail.Attachment(fileName, contentType, in.readNBytes(16 * 1024 * 1024)));
        }
    }

    private static void addAddresses(Set<String> into, String header) {
        try {
            for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
                if (a.getAddress() != null) {
                    into.add(a.getAddress().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            // A malformed delivery header is ignored; To/Cc still route the message.
        }
    }
}
