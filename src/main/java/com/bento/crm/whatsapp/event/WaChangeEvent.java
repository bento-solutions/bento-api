package com.bento.crm.whatsapp.event;

import java.util.UUID;

/**
 * Something in an organization's WhatsApp inbox changed. Published inside the transaction that
 * made the change and delivered to SSE subscribers only after it commits.
 *
 * <p>Deliberately a hint, not a payload: subscribers refetch through the normal endpoints, which
 * apply the viewer's own permissions and visibility, so the stream never carries message text.
 */
public record WaChangeEvent(UUID organizationId, Type type, UUID conversationId, UUID messageId) {

    public enum Type {
        MESSAGE_CREATED,
        MESSAGE_UPDATED,
        CONVERSATION_UPDATED,
        CONVERSATION_REMOVED,
        SESSION_UPDATED
    }

    public static WaChangeEvent messageCreated(UUID orgId, UUID conversationId, UUID messageId) {
        return new WaChangeEvent(orgId, Type.MESSAGE_CREATED, conversationId, messageId);
    }

    public static WaChangeEvent messageUpdated(UUID orgId, UUID conversationId, UUID messageId) {
        return new WaChangeEvent(orgId, Type.MESSAGE_UPDATED, conversationId, messageId);
    }

    public static WaChangeEvent conversationUpdated(UUID orgId, UUID conversationId) {
        return new WaChangeEvent(orgId, Type.CONVERSATION_UPDATED, conversationId, null);
    }
}
