package com.bento.crm.whatsapp.dto;

import java.time.Instant;
import java.util.UUID;

/** One inbox row: a conversation with just enough of its partner and last message to list it. */
public record ConversationView(
        UUID id,
        String phone,
        String displayName,
        UUID partnerId,
        String partnerName,
        String partnerType,
        UUID assignedToUserId,
        Instant lastMessageAt,
        String lastMessagePreview,
        String lastMessageDirection,
        int unreadCount,
        boolean windowOpen,
        Instant windowExpiresAt,
        boolean optedOut,
        Instant lastReadAt) {
}
