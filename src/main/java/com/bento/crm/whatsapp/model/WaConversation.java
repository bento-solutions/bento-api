package com.bento.crm.whatsapp.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * One conversation per (organization, phone number), independent of how many
 * campaigns that number appears in.
 *
 * <p>{@code windowExpiresAt} tracks WhatsApp's 24-hour customer service window:
 * free-form text may only be sent while it is in the future. Outside it, only an
 * approved template will be accepted by Meta.
 *
 * <p>The activity columns (last message, unread count, window, notification throttle) are
 * maintained by {@link com.bento.crm.whatsapp.repository.WaConversationRepository}'s atomic
 * updates, never through this entity: inbound messages for one conversation can be ingested
 * concurrently, and a read-modify-write would lose increments or fail its version check.
 * {@code @DynamicUpdate} keeps an entity save (linking a partner, say) from rewriting those
 * columns with the stale values it loaded.
 */
@Entity
@DynamicUpdate
@Table(name = "wa_conversation")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaConversation extends BaseTenantEntity {

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    @Column(name = "phone_e164", nullable = false)
    private String phoneE164;

    @Column(name = "last_outbound_at")
    private Instant lastOutboundAt;

    @Column(name = "last_inbound_at")
    private Instant lastInboundAt;

    @Column(name = "window_expires_at")
    private Instant windowExpiresAt;

    @Column(name = "opted_out_at")
    private Instant optedOutAt;

    /** Baileys phone-number JID ({@code <digits>@s.whatsapp.net}); null on Meta. */
    @Column(name = "wa_jid")
    private String waJid;

    /** Baileys LID JID ({@code <id>@lid}), WhatsApp's privacy alias for the contact. */
    @Column(name = "wa_lid")
    private String waLid;

    /** The contact's WhatsApp profile name, as last reported. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "last_message_preview", length = 280)
    private String lastMessagePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_message_direction", length = 3)
    private WaMessage.Direction lastMessageDirection;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    public boolean isWindowOpen() {
        return windowExpiresAt != null && windowExpiresAt.isAfter(Instant.now());
    }

    public boolean isOptedOut() {
        return optedOutAt != null;
    }
}
