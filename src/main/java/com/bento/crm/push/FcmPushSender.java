package com.bento.crm.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Firebase Cloud Messaging (HTTP v1), which reaches Android and, through APNs, iOS. Configured by
 * pointing {@code app.push.fcm.service-account-file} at a Firebase service-account JSON key; off
 * otherwise, and notifications then stay in the app only.
 */
@Slf4j
@Component
public class FcmPushSender implements PushSender {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String projectId;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey key;

    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    public FcmPushSender(ObjectMapper mapper, @Value("${app.push.fcm.service-account-file:}") String file) {
        this.mapper = mapper;
        String p = null, e = null, u = null;
        PrivateKey k = null;
        if (file != null && !file.isBlank()) {
            try {
                JsonNode sa = mapper.readTree(Files.readString(Path.of(file)));
                p = sa.path("project_id").asText();
                e = sa.path("client_email").asText();
                u = sa.path("token_uri").asText("https://oauth2.googleapis.com/token");
                String pem = sa.path("private_key").asText().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
                k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            } catch (Exception ex) {
                log.warn("[push] Firebase key {} not usable, push is off: {}", file, ex.getMessage());
                p = null;
                k = null;
            }
        }
        this.projectId = p;
        this.clientEmail = e;
        this.tokenUri = u;
        this.key = k;
    }

    @Override
    public boolean configured() {
        return key != null && projectId != null && !projectId.isBlank();
    }

    @Override
    public Result send(String deviceToken, String title, String body, Map<String, String> data) {
        if (!configured()) return Result.FAILED;
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("notification", Map.of("title", title, "body", body == null ? "" : body));
            if (data != null && !data.isEmpty()) message.put("data", data);
            String json = mapper.writeValueAsString(Map.of("message", message));
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) return Result.SENT;
            if (res.statusCode() == 404 || res.body().contains("UNREGISTERED") || res.body().contains("INVALID_ARGUMENT")) {
                return Result.INVALID_TOKEN;
            }
            log.warn("[push] FCM answered {}: {}", res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("[push] not sent: {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /** An OAuth access token from the service account (JWT bearer grant), cached until near expiry. */
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(60))) return accessToken;
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .setIssuer(clientEmail).setAudience(tokenUri).claim("scope", SCOPE)
                .setIssuedAt(Date.from(now)).setExpiration(Date.from(now.plusSeconds(3600)))
                .signWith(key, SignatureAlgorithm.RS256).compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Google token endpoint answered " + res.statusCode());
        JsonNode t = mapper.readTree(res.body());
        accessToken = t.path("access_token").asText();
        accessTokenExpires = now.plusSeconds(t.path("expires_in").asLong(3600));
        return accessToken;
    }
}
