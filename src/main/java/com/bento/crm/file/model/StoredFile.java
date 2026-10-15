package com.bento.crm.file.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "stored_file")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredFile extends BaseTenantEntity {

    @Column(columnDefinition = "uuid")
    private UUID ownerEntityId;

    private String ownerEntityType;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    private Long sizeBytes;

    @Column(nullable = false)
    private String storagePath;

    @Column(columnDefinition = "uuid")
    private UUID uploadedByUserId;

    private java.time.Instant uploadedAt;

    /** Drive folder; null for files that only hang off an owner entity, or at the drive root. */
    @Column(name = "folder_id", columnDefinition = "uuid")
    private UUID folderId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]", nullable = false)
    @Builder.Default
    private String[] tags = new String[0];

    @Column(length = 500)
    private String description;

    /** The partner the document belongs to (a client's file, a supplier's invoice). */
    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Source source = Source.UPLOAD;

    /** SHA-256 of the content, to skip documents that arrive twice. */
    @Column(length = 64)
    private String sha256;

    public enum Source { UPLOAD, EMAIL, WHATSAPP, SYSTEM }
}
