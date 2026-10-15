package com.bento.crm.file.service;

import com.bento.crm.file.model.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface FileStorageService {

    StoredFile store(MultipartFile file, String ownerEntityType, UUID ownerEntityId);

    /**
     * Stores bytes that did not come from an HTTP upload (an email attachment, a WhatsApp
     * document), for an explicit organization since there may be no request context.
     */
    StoredFile storeBytes(UUID organizationId, byte[] content, String fileName, String contentType,
                          String ownerEntityType, UUID ownerEntityId, StoredFile.Source source, UUID folderId);

    StoredFile getMetadata(UUID fileId);

    /** The bytes of a file row already loaded (and access-checked) by the caller. */
    Resource open(StoredFile file);

    byte[] readAll(StoredFile file);

    List<StoredFile> listByOwner(String ownerEntityType, UUID ownerEntityId);

    Resource load(UUID fileId);

    StoredFile getPublicMetadata(UUID fileId);

    Resource loadPublic(UUID fileId);

    void delete(UUID fileId);
}
