package com.bento.crm.drive.dto;

import com.bento.crm.drive.model.DriveFolder;
import com.bento.crm.file.model.StoredFile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DriveDtos {

    private DriveDtos() {}

    public record FolderRequest(@NotBlank @Size(max = 120) String name, UUID parentId, UUID partnerId) {}

    public record FolderResponse(UUID id, String name, UUID parentId, UUID partnerId, String systemKey, boolean locked) {
        public static FolderResponse of(DriveFolder f) {
            return new FolderResponse(f.getId(), f.getName(), f.getParentId(), f.getPartnerId(), f.getSystemKey(),
                    f.getSystemKey() != null);
        }
    }

    /** One level of the drive: where we are, the sub-folders, and the files. */
    public record Listing(FolderResponse folder, List<FolderResponse> path, List<FolderResponse> folders,
                          List<FileResponse> files, long totalFiles) {}

    public record FileResponse(UUID id, String fileName, String contentType, Long sizeBytes, UUID folderId,
                               List<String> tags, String description, UUID partnerId, String partnerName,
                               String ownerEntityType, UUID ownerEntityId, String source, Instant uploadedAt) {
        public static FileResponse of(StoredFile f, String partnerName) {
            return new FileResponse(f.getId(), f.getFileName(), f.getContentType(), f.getSizeBytes(), f.getFolderId(),
                    f.getTags() == null ? List.of() : List.of(f.getTags()), f.getDescription(), f.getPartnerId(),
                    partnerName, f.getOwnerEntityType(), f.getOwnerEntityId(),
                    f.getSource() == null ? null : f.getSource().name(), f.getUploadedAt());
        }
    }

    /**
     * Changes to a file. Absent fields are left as they are; {@code moveToRoot} takes the file out
     * of its folder and {@code unlinkPartner} removes the partner.
     */
    public record FileUpdateRequest(@Size(max = 255) String fileName, UUID folderId, Boolean moveToRoot,
                                    List<@Size(max = 40) String> tags, @Size(max = 500) String description,
                                    UUID partnerId, Boolean unlinkPartner) {}

    public record ShareRequest(@Min(1) @Max(90) Integer expiresInDays) {}

    public record ShareResponse(UUID id, String url, Instant expiresAt, boolean active, int downloadCount, Instant createdAt) {}

    public record PublicFile(String fileName, String contentType, Long sizeBytes, Instant expiresAt) {}
}
