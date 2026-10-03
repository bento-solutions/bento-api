package com.bento.crm.whatsapp.dto;

import com.bento.crm.whatsapp.service.WaOutboxService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** Request bodies for the inbox endpoints. */
public final class InboxRequests {

    private InboxRequests() {
    }

    @Schema(description = "A reply in an existing conversation")
    public record SendMessage(
            @Schema(description = "The message, plain text, at most 4096 characters", example = "Hello Karim, thanks for your message! We can deliver on Thursday.", requiredMode = Schema.RequiredMode.REQUIRED)
            String text,
            @Schema(description = "`DRAFT` (recommended): saved for a person to approve in the inbox, nothing is sent. "
                    + "`AUTO`: queued for sending. Tokens with only the draft scope always draft. Default AUTO.",
                    example = "DRAFT", defaultValue = "AUTO")
            WaOutboxService.Mode mode,
            @Schema(description = "Your own unique key for this message. Retrying with the same key returns the first "
                    + "message instead of creating a second one.", example = "reply-2026-10-03-karim-001")
            String clientRef) {
    }

    @Schema(description = "A message to a lead or customer, starting the conversation if needed")
    public record StartMessage(
            @Schema(description = "Lead or customer id (from GET /partners). Their saved phone number is used.", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID partnerId,
            @Schema(description = "The message, plain text, at most 4096 characters", example = "Hello! Following up on your quote request.", requiredMode = Schema.RequiredMode.REQUIRED)
            String text,
            @Schema(description = "`DRAFT` (recommended): saved for a person to approve. `AUTO`: queued for sending. Default AUTO.",
                    example = "DRAFT", defaultValue = "AUTO")
            WaOutboxService.Mode mode,
            @Schema(description = "Your own unique key; a retry with the same key never sends twice")
            String clientRef) {
    }

    public record Approve(String text) {
    }

    public record LinkPartner(UUID partnerId) {
    }

    public record CreateLead(String name) {
    }
}
