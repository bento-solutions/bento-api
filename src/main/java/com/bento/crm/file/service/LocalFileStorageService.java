package com.bento.crm.file.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    @Value("${FILE_STORAGE_PATH:/data/uploads}")
    private String storagePath;

    private final StoredFileRepository fileRepository;

    private static final long MAX_FILE_SIZE = 15 * 1024 * 1024; // 15 MB
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String[] ALLOWED_TYPES = {"image/", "application/pdf", "text/plain", "application/vnd.ms-excel", "application/vnd.openxmlformats"};

    @Override
    public StoredFile store(MultipartFile file, String ownerEntityType, UUID ownerEntityId) {
        validateFile(file);

        UUID orgId = TenantContext.getCurrentOrganizationId();
        UUID fileId = UUID.randomUUID();

        // The on-disk location is derived exclusively from the storage root, the tenant id and a
        // server-generated UUID. Nothing the client controls (the declared owner type, the
        // original filename) ever touches the path, so "../" or an absolute path in either field
        // cannot escape the tenant's directory. The original name is kept only as display
        // metadata, sanitized so it cannot smuggle control characters into later responses.
        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path tenantDir = storageRoot.resolve(orgId.toString()).normalize();
        Path target = tenantDir.resolve(fileId.toString()).normalize();
        if (!target.startsWith(storageRoot)) {
            // Unreachable given the inputs above; kept as a hard invariant so a future change to
            // the path scheme cannot silently reintroduce traversal.
            throw new IllegalStateException("Resolved storage path escaped the storage root");
        }

        String safeName = sanitizeFileName(file.getOriginalFilename());

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(tenantDir);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }

        StoredFile storedFile = StoredFile.builder()
                .ownerEntityId(ownerEntityId)
                .ownerEntityType(ownerEntityType)
                .fileName(safeName)
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storagePath(target.toString())
                .uploadedAt(Instant.now())
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
        StoredFile storedFile = getMetadata(fileId);

        // The stored path was written by store() above, but verify containment again before
        // handing bytes back: a legacy row (or a tampered DB) must not be able to read files
        // outside the storage root.
        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path path = Paths.get(storedFile.getStoragePath()).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.exists(path)) {
            throw new ResourceNotFoundException("File not found in storage");
        }
        return new FileSystemResource(path);
    }

    @Override
    public StoredFile getPublicMetadata(UUID fileId) {
        return fileRepository.findPublicById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
    }

    @Override
    public Resource loadPublic(UUID fileId) {
        StoredFile storedFile = getPublicMetadata(fileId);

        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path path = Paths.get(storedFile.getStoragePath()).toAbsolutePath().normalize();
        if (!path.startsWith(storageRoot) || !Files.exists(path)) {
            throw new ResourceNotFoundException("File not found in storage");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(UUID fileId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StoredFile storedFile = fileRepository.findByOrganizationIdAndId(orgId, fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        try {
            Files.deleteIfExists(Paths.get(storedFile.getStoragePath()));
        } catch (NoSuchFileException e) {
            // Blob already gone -- fall through and still drop the row.
        } catch (IOException e) {
            // Surface as a 500 rather than returning 204 while the blob is orphaned on disk.
            throw new RuntimeException("Failed to delete file blob", e);
        }
        fileRepository.delete(storedFile);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File exceeds maximum size of 15 MB");
        }

        String contentType = file.getContentType();
        boolean allowed = false;
        for (String type : ALLOWED_TYPES) {
            if (contentType != null && contentType.startsWith(type)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException("File type not allowed");
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
