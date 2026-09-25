package com.bento.crm.whatsapp.service;

import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.partner.service.PartnerService;
import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaBlockedNumberRepository;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a message from a number the CRM does not know should create a lead, and
 * creates it. Runs in its own transaction before the message is ingested, so the ingest then
 * finds the lead by phone and links the conversation to it.
 *
 * <p>Never for history-sync messages (the owner's past chats are not leads), never for an
 * ignored number, and only as far as the account's {@code autoCreateLeads} switch allows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaLeadResolver {

    private final WaBlockedNumberRepository blockedRepository;
    private final WaConversationRepository conversationRepository;
    private final PartnerRepository partnerRepository;
    private final PartnerService partnerService;

    /** @return whether a lead was created */
    @Transactional
    public boolean resolve(WaAccount account, InboundMessage message) {
        if (message.isHistory() || message.phoneE164() == null) {
            return false;
        }
        boolean eligible = switch (account.getAutoCreateLeads()) {
            case OFF -> false;
            case INBOUND -> message.direction() == WaMessage.Direction.IN;
            case INBOUND_AND_PHONE -> true;
        };
        if (!eligible) {
            return false;
        }
        var orgId = account.getOrganizationId();
        String phone = message.phoneE164();
        if (blockedRepository.isBlocked(orgId, phone)) {
            return false;
        }
        var existing = conversationRepository.findByOrgAndPhone(orgId, phone);
        if (existing.isPresent() && existing.get().getPartnerId() != null) {
            return false;
        }
        if (partnerRepository.findByOrganizationIdAndPhoneDigits(orgId, phone.replaceAll("\\D", "")).isPresent()) {
            return false;
        }
        partnerService.findOrCreateWhatsAppLead(orgId, phone, message.pushName(), account.getDefaultAssigneeUserId());
        log.info("[wa-leads] created a lead for a new WhatsApp contact in org {}", orgId);
        return true;
    }
}
