package com.bento.crm.whatsapp.dto;

import com.bento.crm.whatsapp.service.WaOutboxService;

import java.util.UUID;

/** Request bodies for the inbox endpoints. */
public final class InboxRequests {

    private InboxRequests() {
    }

    public record SendMessage(String text, WaOutboxService.Mode mode, String clientRef) {
    }

    public record StartMessage(UUID partnerId, String text, WaOutboxService.Mode mode, String clientRef) {
    }

    public record Approve(String text) {
    }

    public record LinkPartner(UUID partnerId) {
    }

    public record CreateLead(String name) {
    }
}
