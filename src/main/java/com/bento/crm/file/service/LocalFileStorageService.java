package com.bento.crm.file.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.storage.BlobStores;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * File metadata in {@code stored_file}, bytes in whichever {@link BlobStores} backend is
 * configured (the server's disk by default, or an S3-compatible bucket).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    private final StoredFileRepository fileRepository;
    private final BlobStores blobStores;

    static final long MAX_FILE_SIZE = 15 * 1024 * 1024; // 15 MB
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String[] ALLOWED_TYPES = {"image/", "application/pdf", "text/plain", "text/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats"};

    @Override
    public StoredFile store(MultipartFile file, String ownerEntityType, UUID ownerEntityId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        try {
            return storeBytes(TenantContext.getCurrentOrganizationId(), file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), ownerEntityType, ownerEntityId, StoredFile.Source.UPLOAD, null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    @Override
    public StoredFile storeBytes(UUID orgId, byte[] content, String fileName, String contentType,
                                 String ownerEntityType, UUID ownerEntityId, StoredFile.Source source, UUID folderId) {
        validate(content, contentType);
        UUID fileId = UUID.randomUUID();
        String location;
        try (InputStream in = new ByteArrayInputStream(content)) {
            location = blobStores.put(orgId, fileId, in, content.length, contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }

        StoredFile storedFile = StoredFile.builder()
                .ownerEntityId(ownerEntityId)
                .ownerEntityType(ownerEntityType)
                // The original name is kept only as display metadata, sanitized so it cannot
                // smuggle control characters into later responses. It never builds a path.
                .fileName(sanitizeFileName(fileName))
                .contentType(contentType)
                .sizeBytes((long) content.length)
                .storagePath(location)
                .uploadedAt(Instant.now())
                .source(source == null ? StoredFile.Source.UPLOAD : source)
                .folderId(folderId)
                .sha256(sha256(content))
                .build();
        storedFile.setOrganizationId(orgId);
        return fileRepository.save(storedFile);
    }

    @Override
    public StoredFile getMetadata(UUID fileId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return fileRepository.findByOrganizationIdAndId(orgId, fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
    }

    @Override
    public List<StoredFile> listByOwner(String ownerEntityType, UUID ownerEntityId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return fileRepository.findByOrganizationIdAndOwnerEntityTypeAndOwnerEntityId(orgId, ownerEntityType, ownerEntityId);
    }

    @Override
    public Resource load(UUID fileId) {
        return open(getMetadata(fileId));
    }

    @Override
    public Resource open(StoredFile file) {
        Resource resource = blobStores.open(file.getStoragePath());
        if (resource == null) {
            throw new ResourceNotFoundException("File not found in storage");
        }
        return resource;
    }

    @Override
    public byte[] readAll(StoredFile file) {
        try (InputStream in = open(file).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
        }
    }

    @Override
    public StoredFile getPublicMetadata(UUID fileId) {
        return fileRepository.findPublicById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
    }

    @Override
    public Resource loadPublic(UUID fileId) {
        return open(getPublicMetadata(fileId));
    }

    @Override
    public void delete(UUID fileId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StoredFile storedFile = fileRepository.findByOrganizationIdAndId(orgId, fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
        try {
            blobStores.delete(storedFile.getStoragePath());
        } catch (IOException e) {
            // Surface as a 500 rather than returning 204 while the blob is orphaned.
            throw new RuntimeException("Failed to delete file blob", e);
        }
        fileRepository.delete(storedFile);
    }

    private static void validate(byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("File is empty");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File exceeds maximum size of 15 MB");
        }
        if (!isAllowedType(contentType)) {
            throw new IllegalArgumentException("File type not allowed");
        }
    }

    public static boolean isAllowedType(String contentType) {
        if (contentType == null) {
            return false;
        }
        for (String type : ALLOWED_TYPES) {
            if (contentType.startsWith(type)) {
                return true;
            }
        }
        return false;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reduce a client-supplied filename to a plain display label: drop any directory component,
     * strip control characters and quoting characters that would let it break out of a header or
     * a JSON string later, and cap the length. Never used to build a filesystem path.
     */
    static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "file";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"\r\n]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return name;
    }
}
