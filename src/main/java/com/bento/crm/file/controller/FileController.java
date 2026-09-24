package com.bento.crm.file.controller;

import com.bento.crm.file.dto.StoredFileResponse;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Generic file attachment storage endpoints")
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @Operation(summary = "Upload file", description = "Upload a file attached to an owner entity")
    public ResponseEntity<StoredFileResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ownerEntityType") String ownerEntityType,
            @RequestParam("ownerEntityId") UUID ownerEntityId) {
        StoredFile storedFile = fileStorageService.store(file, ownerEntityType, ownerEntityId);
        return ResponseEntity.status(HttpStatus.CREATED).body(StoredFileResponse.fromEntity(storedFile));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('FILES_READ')")
    @Operation(summary = "List files for owner", description = "List files attached to an owner entity")
    public ResponseEntity<List<StoredFileResponse>> listFiles(
            @RequestParam("ownerEntityType") String ownerEntityType,
            @RequestParam("ownerEntityId") UUID ownerEntityId) {
        List<StoredFileResponse> files = fileStorageService.listByOwner(ownerEntityType, ownerEntityId).stream()
                .map(StoredFileResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(files);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('FILES_READ')")
    @Operation(summary = "Download file", description = "Download a previously uploaded file")
    public ResponseEntity<Resource> downloadFile(@PathVariable UUID id) {
        StoredFile storedFile = fileStorageService.getMetadata(id);
        Resource resource = fileStorageService.load(id);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(storedFile.getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(storedFile.getFileName()))
                .body(resource);
    }

    /**
     * Build an RFC 6266 Content-Disposition value. The stored name is sanitized on upload, but
     * legacy rows may still hold newlines, quotes or non-ASCII bytes, any of which would let a
     * download response inject a header or corrupt the filename. Emit a stripped ASCII
     * {@code filename} for old clients plus a percent-encoded {@code filename*} for the real name.
     */
    private static String contentDisposition(String rawName) {
        String name = (rawName == null || rawName.isBlank()) ? "file" : rawName;
        String asciiFallback = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'").replace("\\", "_");
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
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @Operation(summary = "Delete file", description = "Delete a previously uploaded file")
    public ResponseEntity<Void> deleteFile(@PathVariable UUID id) {
        fileStorageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/public/{id}")
    @Operation(summary = "View public file inline", description = "Stream public file such as organization logo")
    public ResponseEntity<Resource> viewPublicFile(@PathVariable UUID id) {
        StoredFile storedFile = fileStorageService.getPublicMetadata(id);
        Resource resource = fileStorageService.loadPublic(id);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(storedFile.getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(resource);
    }
}
