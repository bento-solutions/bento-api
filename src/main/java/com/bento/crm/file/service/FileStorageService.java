package com.bento.crm.file.service;

import com.bento.crm.file.model.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface FileStorageService {

    StoredFile store(MultipartFile file, String ownerEntityType, UUID ownerEntityId);

    StoredFile getMetadata(UUID fileId);

    List<StoredFile> listByOwner(String ownerEntityType, UUID ownerEntityId);

    Resource load(UUID fileId);

    StoredFile getPublicMetadata(UUID fileId);

    Resource loadPublic(UUID fileId);

    void delete(UUID fileId);
}
