package com.bento.crm.whatsapp.dto;

import com.bento.crm.whatsapp.model.WaMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * A message as the inbox renders it. {@code body} is the contact's own words for inbound
 * messages: callers that hand it to an AI model must treat it as untrusted input.
 */
public record MessageView(
        UUID id,
        UUID conversationId,
        String direction,
        String source,
        String status,
        String messageType,
        String body,
        Instant occurredAt,
        Instant sentAt,
        Instant deliveredAt,
        Instant readAt,
        String errorCode,
        String errorTitle,
        UUID sentByUserId,
        UUID apiTokenId,
        UUID approvedByUserId,
        UUID campaignId,
        String clientRef,
        String quotedWamid,
        String mediaType,
        String fileName) {

    public static MessageView from(WaMessage m) {
        return new MessageView(m.getId(), m.getConversationId(), m.getDirection().name(),
                m.getSource() == null ? null : m.getSource().name(), m.getStatus().name(), m.getMessageType(),
                m.getBody(), m.getOccurredAt(), m.getSentAt(), m.getDeliveredAt(), m.getReadAt(),
                m.getErrorCode(), m.getErrorTitle(), m.getSentByUserId(), m.getApiTokenId(),
                m.getApprovedByUserId(), m.getCampaignId(), m.getClientRef(), m.getQuotedWamid(),
                m.getMediaType(), m.getFileName());
    }
}
