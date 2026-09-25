package com.bento.crm.whatsapp.controller;

import com.bento.crm.whatsapp.config.BaileysProperties;
import com.bento.crm.whatsapp.service.BaileysWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Events from the Baileys bot (messages, receipts, session changes).
 *
 * <p>The bot calls this directly on the internal Docker network. Three checks guard it:
 * <ul>
 *   <li>any header a reverse proxy adds means the call came in through Traefik rather than from
 *       the bot, and is refused (Tomcat's RemoteIpValve consumes X-Forwarded-For itself, so the
 *       headers Traefik adds alongside it are checked too);</li>
 *   <li>the timestamp must be within five minutes, which bounds replay;</li>
 *   <li>the signature is HMAC-SHA256 over {@code "<timestamp>.<raw body>"} with the shared
 *       secret, compared in constant time. The body is bound as bytes because re-serializing
 *       parsed JSON would change it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/webhooks/baileys")
@RequiredArgsConstructor
@Hidden
@Slf4j
public class BaileysWebhookController {

    static final long MAX_SKEW_SECONDS = 300;
    private static final List<String> PROXY_HEADERS =
            List.of("X-Forwarded-For", "X-Real-Ip", "X-Forwarded-Host", "X-Forwarded-Server");

    private final BaileysProperties properties;
    private final BaileysWebhookService webhookService;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(@RequestBody byte[] rawBody,
                                     @RequestHeader(value = "X-Bento-Timestamp", required = false) String timestamp,
                                     @RequestHeader(value = "X-Bento-Signature", required = false) String signature,
                                     HttpServletRequest request) {
        for (String header : PROXY_HEADERS) {
            if (request.getHeader(header) != null) {
                log.warn("[baileys-hook] refused a call that came through a proxy ({})", header);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        if (!signatureValid(rawBody, timestamp, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(webhookService.process(objectMapper.readTree(rawBody)));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    boolean signatureValid(byte[] rawBody, String timestamp, String signature) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.length() < 32 || timestamp == null || signature == null
                || !signature.startsWith("sha256=")) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_SKEW_SECONDS) {
            log.warn("[baileys-hook] rejected a call with a stale timestamp ({})", ts);
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.substring("sha256=".length()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[baileys-hook] signature check failed", e);
            return false;
        }
    }
}
