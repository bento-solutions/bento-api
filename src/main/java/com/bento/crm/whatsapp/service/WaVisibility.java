package com.bento.crm.whatsapp.service;

import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may see which conversation. A personal number carries personal chats, so the default is
 * narrow: a user sees the conversations of partners assigned to or owned by them, and only
 * holders of {@code WHATSAPP_READ_ALL} (admins, managers) see everything — including numbers not
 * linked to any partner yet.
 *
 * <p>An invisible conversation is reported as not found rather than forbidden, so ids cannot be
 * probed for existence.
 */
@Component
@RequiredArgsConstructor
public class WaVisibility {

    private final WaConversationRepository conversationRepository;
    private final PartnerRepository partnerRepository;

    public WaConversation requireConversation(WaActor actor, UUID conversationId) {
        WaConversation conversation = conversationRepository
                .findByOrganizationIdAndId(actor.organizationId(), conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        if (!canSee(actor, conversation)) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return conversation;
    }

    public Partner requirePartner(WaActor actor, UUID partnerId) {
        Partner partner = partnerRepository.findByOrganizationIdAndId(actor.organizationId(), partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
        if (!canSee(actor, partner)) {
            throw new ResourceNotFoundException("Partner not found");
        }
        return partner;
    }

    public boolean canSee(WaActor actor, WaConversation conversation) {
        if (!actor.organizationId().equals(conversation.getOrganizationId())) {
            return false;
        }
        if (actor.readsAll()) {
            return true;
        }
        if (conversation.getPartnerId() == null) {
            return false;
        }
        return partnerRepository.findByOrganizationIdAndId(actor.organizationId(), conversation.getPartnerId())
                .map(p -> canSee(actor, p))
                .orElse(false);
    }

    public boolean canSee(WaActor actor, Partner partner) {
        return actor.readsAll()
                || actor.userId().equals(partner.getAssignedToUserId())
                || actor.userId().equals(partner.getOwnerId());
    }
}
