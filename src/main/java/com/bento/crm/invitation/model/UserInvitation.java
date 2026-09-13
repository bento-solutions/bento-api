package com.bento.crm.invitation.model;

import com.bento.crm.common.model.BaseTenantEntity;
import com.bento.crm.common.model.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_invitation")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInvitation extends BaseTenantEntity {

    @Column(nullable = false)
    private String email;

    /** Pre-assigned by the inviting admin; the invitee cannot change it when accepting. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(columnDefinition = "uuid")
    private UUID teamId;

    private String jobTitle;

    /** Suggested name, overridable on the acceptance form. */
    private String displayName;

    @Column(nullable = false, length = 5)
    private String language;

    /** SHA-256 of the token that went out in the email. */
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** The plaintext token kept while pending so an admin can copy and share the link manually. */
    @Column(length = 255)
    private String rawToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;

    private Instant revokedAt;

    @Column(columnDefinition = "uuid")
    private UUID acceptedUserId;

    private Instant lastSentAt;

    @Column(nullable = false)
    private Integer sendCount;

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    /** The status the UI should show, folding the derived expiry in. */
    public String displayStatus() {
        if (status == InvitationStatus.PENDING && isExpired()) {
            return "EXPIRED";
        }
        return status.name();
    }
}
