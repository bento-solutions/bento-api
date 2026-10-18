package com.bento.crm.inbound.controller;

import com.bento.crm.inbound.mail.MimeEmailParser;
import com.bento.crm.inbound.mail.RawEmail;
import com.bento.crm.inbound.service.InboundIntakeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Receives emails sent to the inbox addresses from the mail provider: a raw MIME message
 * (message/rfc822, or a multipart field "email"/"body-mime" as SendGrid and Mailgun post it), or
 * Postmark's inbound JSON. The secret in the path is the credential; without one configured the
 * endpoint does not exist.
 */
@Slf4j
@RestController
@RequestMapping("/public/inbound")
@RequiredArgsConstructor
public class InboundEmailWebhookController {

    private static final int MAX_BYTES = 40 * 1024 * 1024;

    private final InboundIntakeService intake;
    private final ObjectMapper objectMapper;

    @Value("${app.inbound.webhook-secret:}")
    private String secret;

    /** authserv-ids of our mail provider: only their Authentication-Results header is believed. */
    @Value("${app.inbound.trusted-authserv-ids:}")
    private java.util.Set<String> trustedAuthservIds;

    @PostMapping("/email/{secret}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable("secret") String given, HttpServletRequest request) throws Exception {
        if (secret == null || secret.isBlank() || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.notFound().build();
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            return ResponseEntity.status(413).build();
        }
        RawEmail email = read(request);
        if (email == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No email in the request"));
        }
        int filed = intake.ingest(email);
        log.info("Inbound email from {} filed {} document(s)", email.from(), filed);
        // Always 200 once read: a provider retrying an email we chose to skip would only resend it.
        return ResponseEntity.ok(Map.of("filed", filed));
    }

    private RawEmail read(HttpServletRequest request) throws Exception {
        String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/form-data")) {
            for (String name : List.of("email", "body-mime")) {
                Part part = request.getPart(name);
                if (part != null) {
                    return MimeEmailParser.parse(part.getInputStream().readNBytes(MAX_BYTES), trusted());
                }
            }
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES);
        if (type.startsWith("application/json")) {
            return postmark(objectMapper.readTree(body));
        }
        return body.length == 0 ? null : MimeEmailParser.parse(body, trusted());
    }

    /** Postmark inbound JSON. Its spam and SPF verdicts arrive as headers. */
    RawEmail postmark(JsonNode j) {
        List<String> to = new ArrayList<>();
        for (String f : List.of("ToFull", "CcFull", "BccFull")) {
            j.path(f).forEach(a -> to.add(a.path("Email").asText("").toLowerCase(Locale.ROOT)));
        }
        if (j.hasNonNull("OriginalRecipient")) {
            to.add(j.get("OriginalRecipient").asText().toLowerCase(Locale.ROOT));
        }
        List<RawEmail.Attachment> attachments = new ArrayList<>();
        for (JsonNode a : j.path("Attachments")) {
            try {
                attachments.add(new RawEmail.Attachment(a.path("Name").asText(null), a.path("ContentType").asText(null),
                        Base64.getMimeDecoder().decode(a.path("Content").asText(""))));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping an attachment that is not valid base64");
            }
        }
        List<String> authResults = new ArrayList<>();
        for (JsonNode h : j.path("Headers")) {
            if ("Authentication-Results".equalsIgnoreCase(h.path("Name").asText())) {
                authResults.add(h.path("Value").asText(""));
            }
        }
        String from = j.path("FromFull").path("Email").asText(j.path("From").asText(null));
        return new RawEmail(to, from, j.path("Subject").asText(null), j.path("MessageID").asText(null), Instant.now(),
                attachments, MimeEmailParser.verified(authResults.toArray(String[]::new), from, trusted()));
    }

    private java.util.Set<String> trusted() {
        return trustedAuthservIds == null ? java.util.Set.of()
                : trustedAuthservIds.stream().filter(t -> t != null && !t.isBlank()).collect(java.util.stream.Collectors.toSet());
    }
}
