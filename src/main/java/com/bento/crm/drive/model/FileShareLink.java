package com.bento.crm.drive.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A link anyone can open to download one file until it expires or is revoked. */
@Entity
@Table(name = "file_share_link")
@Getter
@Setter
public class FileShareLink extends BaseTenantEntity {

    @Column(name = "file_id", nullable = false, columnDefinition = "uuid")
    private UUID fileId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "download_count", nullable = false)
    private int downloadCount;

    public boolean isActive(Instant now) {
        return revokedAt == null && getDeletedAt() == null && expiresAt.isAfter(now);
    }
}
