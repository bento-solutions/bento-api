package com.bento.crm.drive.controller;

import com.bento.crm.file.model.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Serving stored files: only safe types are shown inline, everything else downloads. */
public final class FileResponses {

    private static final Set<String> INLINE = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain");

    private FileResponses() {}

    public static ResponseEntity<Resource> of(StoredFile f, Resource body, boolean forceDownload) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(f.getContentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        // SVG and HTML-like types could run script in our origin: never render them inline.
        boolean inline = !forceDownload && INLINE.contains(type.getType() + "/" + type.getSubtype());
        return ResponseEntity.ok()
                .contentType(inline ? type : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(inline ? "inline" : "attachment", f.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    /** RFC 6266: a stripped ASCII {@code filename} for old clients plus the real name percent-encoded. */
    static String disposition(String kind, String rawName) {
        String name = (rawName == null || rawName.isBlank()) ? "file" : rawName;
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'").replace("\\", "_");
        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return kind + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
