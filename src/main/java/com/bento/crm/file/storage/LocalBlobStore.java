package com.bento.crm.file.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Files on the server's disk, under {@code FILE_STORAGE_PATH/<organization>/<file id>}. Nothing
 * the client sends (owner type, file name) is part of the path, so it cannot escape the root.
 */
@Component
public class LocalBlobStore implements BlobStore {

    private final Path root;

    public LocalBlobStore(@Value("${FILE_STORAGE_PATH:/data/uploads}") String storagePath) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String put(UUID organizationId, UUID fileId, InputStream in, long size, String contentType) throws IOException {
        Path tenantDir = root.resolve(organizationId.toString()).normalize();
        Path target = tenantDir.resolve(fileId.toString()).normalize();
        if (!target.startsWith(root)) {
            // Unreachable given the inputs above; kept as a hard invariant so a future change to
            // the path scheme cannot silently reintroduce traversal.
            throw new IllegalStateException("Resolved storage path escaped the storage root");
        }
        Files.createDirectories(tenantDir);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
    }

    @Override
    public boolean owns(String location) {
        return location != null && !location.startsWith(S3BlobStore.PREFIX);
    }

    @Override
    public Resource open(String location) {
        // Verify containment again before handing bytes back: a legacy row (or a tampered DB)
        // must not be able to read files outside the storage root.
        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.exists(path)) {
            return null;
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String location) throws IOException {
        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (path.startsWith(root)) {
            Files.deleteIfExists(path);
        }
    }
}
