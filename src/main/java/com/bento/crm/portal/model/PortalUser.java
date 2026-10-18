package com.bento.crm.portal.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A person at a client company who signs in to the client portal. Never a staff account. */
@Entity
@Table(name = "portal_user")
@Getter
@Setter
public class PortalUser extends BaseTenantEntity {

    @Column(name = "partner_id", nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "invite_token_hash", length = 64)
    private String inviteTokenHash;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;
}
