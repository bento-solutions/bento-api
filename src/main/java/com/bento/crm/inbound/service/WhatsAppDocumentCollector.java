package com.bento.crm.inbound.service;

import com.bento.crm.inbound.model.InboundDocument;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.whatsapp.model.WaAccount;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Files the PDFs and photos a known contact sends on WhatsApp in that contact's drive folder.
 * A supplier's document goes through invoice reading like an emailed one; anything else is
 * simply filed. Numbers that match no contact are ignored, so strangers cannot fill the drive.
 */
@Slf4j
@Service
public class WhatsAppDocumentCollector {

    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private final InboundIntakeService intake;
    private final PartnerRepository partnerRepository;
    private final JdbcTemplate jdbc;
    private final RestClient meta;
    private final String apiVersion;

    public WhatsAppDocumentCollector(InboundIntakeService intake, PartnerRepository partnerRepository, JdbcTemplate jdbc,
                                     RestClient.Builder builder,
                                     @Value("${whatsapp.meta.base-url:https://graph.facebook.com}") String baseUrl,
                                     @Value("${whatsapp.meta.api-version:v22.0}") String apiVersion) {
        this.intake = intake;
        this.partnerRepository = partnerRepository;
        this.jdbc = jdbc;
        this.meta = builder.baseUrl(baseUrl).build();
        this.apiVersion = apiVersion;
    }

    /** A Meta Cloud API image or document message: the file is fetched from Meta with the account token. */
    public void fromMeta(WaAccount account, JsonNode message) {
        String type = message.path("type").asText("");
        if (!type.equals("image") && !type.equals("document")) {
            return;
        }
        JsonNode media = message.path(type);
        String mediaId = media.path("id").asText(null);
        String digits = message.path("from").asText("").replaceAll("\\D", "");
        if (mediaId == null || digits.isEmpty()) {
            return;
        }
        collect(account.getOrganizationId(), "+" + digits, media.path("mime_type").asText(null),
                media.path("filename").asText(null), message.path("id").asText(null), () -> download(account, mediaId));
    }

    /** A Baileys message whose bot attached the file as base64. */
    public void fromBaileys(WaAccount account, JsonNode data) {
        if (!"IN".equals(data.path("direction").asText("IN")) || !data.hasNonNull("mediaBase64")) {
            return;
        }
        collect(account.getOrganizationId(), data.path("phoneE164").asText(null), data.path("mimeType").asText(null),
                data.path("fileName").asText(null), data.path("wamid").asText(null),
                () -> Base64.getDecoder().decode(data.get("mediaBase64").asText()));
    }

    public void collect(UUID orgId, String phone, String mimeType, String fileName, String wamid, Supplier<byte[]> bytes) {
        if (phone == null || mimeType == null
                || !(mimeType.startsWith("application/pdf") || mimeType.startsWith("image/"))) {
            return;
        }
        Boolean on = jdbc.queryForObject("SELECT whatsapp_documents FROM organization WHERE id = ?", Boolean.class, orgId);
        if (!Boolean.TRUE.equals(on)) {
            return;
        }
        Optional<Partner> partner = partnerRepository.findByOrganizationIdAndPhoneDigits(orgId, phone.replaceAll("\\D", ""));
        if (partner.isEmpty()) {
            return;
        }
        byte[] content = bytes.get();
        if (content == null || content.length > MAX_BYTES) {
            return;
        }
        InboundDocument.Kind kind = partner.get().getType() == Partner.PartnerType.VENDOR
                ? InboundDocument.Kind.VENDOR_INVOICE : InboundDocument.Kind.OTHER;
        String type = InboundIntakeService.sniff(content, mimeType.split(";")[0].trim(), fileName);
        if (!InboundIntakeService.acceptable(kind, type, content)) {
            return;
        }
        InboundDocument doc = intake.intake(orgId, null, InboundDocument.Channel.WHATSAPP, kind, content, fileName, type,
                phone, null, wamid, Instant.now(), partner.get().getId());
        if (doc != null) {
            log.info("Filed a WhatsApp document from {} for org {}", partner.get().getId(), orgId);
        }
    }

    private byte[] download(WaAccount account, String mediaId) {
        JsonNode info = meta.get().uri("/" + apiVersion + "/" + mediaId)
                .header("Authorization", "Bearer " + account.getAccessToken())
                .retrieve().body(JsonNode.class);
        String url = info == null ? null : info.path("url").asText(null);
        if (url == null || info.path("file_size").asLong(0) > MAX_BYTES) {
            return null;
        }
        // The media URL is Meta's; never follow one pointing elsewhere with our token.
        String host = URI.create(url).getHost();
        if (host == null || !(host.endsWith(".fbsbx.com") || host.endsWith(".facebook.com") || host.endsWith(".whatsapp.net"))) {
            log.warn("Refusing WhatsApp media URL on unexpected host {}", host);
            return null;
        }
        return RestClient.create().get().uri(URI.create(url))
                .header("Authorization", "Bearer " + account.getAccessToken())
                .retrieve().body(byte[].class);
    }
}
