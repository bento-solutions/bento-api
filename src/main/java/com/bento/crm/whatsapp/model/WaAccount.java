package com.bento.crm.whatsapp.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's WhatsApp sending identity. One row per organization.
 *
 * <p>{@code phoneNumberId} is unique across all organizations because it is the only
 * key Meta's inbound webhook carries — it is how an unauthenticated callback is
 * resolved back to a tenant.
 */
@Entity
@Table(name = "wa_account")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaAccount extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(name = "phone_number_id", nullable = false)
    private String phoneNumberId;

    @Column(name = "waba_id")
    private String wabaId;

    @Column(name = "display_phone_number")
    private String displayPhoneNumber;

    @Column(name = "access_token", columnDefinition = "text")
    private String accessToken;

    @Column(name = "app_secret")
    private String appSecret;

    @Column(name = "verify_token")
    private String verifyToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "quality_rating")
    private String qualityRating;

    // --- Baileys session (provider BAILEYS) ---------------------------------------------------

    /** Last state the bot reported: stopped, needs_pairing, pairing, open, reconnecting, … */
    @Column(name = "session_state", length = 30)
    private String sessionState;

    /** Sequence of the last applied bot status event; older events are ignored. */
    @Column(name = "session_seq", nullable = false)
    private long sessionSeq;

    @Column(name = "pairing_code", length = 20)
    private String pairingCode;

    @Column(name = "pairing_expires_at")
    private Instant pairingExpiresAt;

    @Column(name = "pairing_attempt")
    private Integer pairingAttempt;

    /** The number an admin asked to link (E.164). */
    @Column(name = "requested_phone", length = 32)
    private String requestedPhone;

    /** The number actually linked, as WhatsApp reports it (E.164). */
    @Column(name = "linked_phone", length = 32)
    private String linkedPhone;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "session_error", length = 500)
    private String sessionError;

    // --- Organization settings ----------------------------------------------------------------

    @Column(name = "reply_min_gap_seconds")
    private Integer replyMinGapSeconds;

    @Column(name = "outreach_min_gap_seconds")
    private Integer outreachMinGapSeconds;

    @Column(name = "outreach_per_hour")
    private Integer outreachPerHour;

    @Column(name = "new_chats_per_day")
    private Integer newChatsPerDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_create_leads", nullable = false, length = 30)
    @Builder.Default
    private AutoCreateLeads autoCreateLeads = AutoCreateLeads.OFF;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Visibility visibility = Visibility.ASSIGNED;

    /** Who automatically created leads are assigned to; null leaves them unassigned. */
    @Column(name = "default_assignee_user_id", columnDefinition = "uuid")
    private UUID defaultAssigneeUserId;

    public boolean isSessionOpen() {
        return "open".equals(sessionState);
    }

    public enum Provider {
        /** Simulated transport: no Meta account required, used to exercise the full flow. */
        MOCK,
        META,
        /** A personal number linked as a device through the self-hosted Baileys bot. */
        BAILEYS
    }

    /** Whether a message from a number not in the CRM creates a lead. */
    public enum AutoCreateLeads {
        OFF,
        /** When the contact writes first. */
        INBOUND,
        /** Also when the owner writes to an unknown number from the phone. */
        INBOUND_AND_PHONE
    }

    public enum Visibility {
        /** Users see conversations of partners assigned to or owned by them. */
        ASSIGNED,
        /** Everyone with WHATSAPP_READ sees every conversation. */
        ALL
    }

    public enum Status {
        CONNECTED, DISCONNECTED
    }
}
