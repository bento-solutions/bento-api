package com.bento.crm.whatsapp.service;

import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.provider.WhatsAppProvider;
import com.bento.crm.whatsapp.provider.WhatsAppProviderRegistry;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sends one templated message to one recipient and records the result.
 *
 * <p>Each send runs in its own transaction ({@code REQUIRES_NEW}) so one contact's
 * failure cannot roll back the rest of a campaign, and so a long campaign never
 * holds a single database transaction open across hundreds of network calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppSendService {

    private final WhatsAppProviderRegistry providerRegistry;
    private final WaMessageRepository messageRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final WaConversationService conversationService;

    /**
     * @param sequenceStep 0 for the initial campaign send, 1 for the J+3 relance
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome sendTemplate(WaAccount account,
                                Campaign campaign,
                                CampaignRecipient callerRecipient,
                                WaConversation conversation,
                                String templateName,
                                int sequenceStep) {

        UUID orgId = account.getOrganizationId();
        Instant now = Instant.now();

        // The caller's instance belongs to another persistence context: campaign dispatch holds
        // it detached, and a relance holds it in the worker's own REQUIRES_NEW transaction.
        // Updating that instance here would save it against a stale @Version (and, for a
        // relance, leave it dirty in the worker's context). Work on this transaction's copy,
        // locked so a concurrent reply or receipt for the same recipient waits for this send.
        CampaignRecipient recipient = recipientRepository.findByIdForUpdate(callerRecipient.getId()).orElse(null);
        if (recipient == null) {
            return new Outcome(false, null, "RECIPIENT_REMOVED", false);
        }

        if (conversation.isOptedOut()) {
            markTerminal(recipient, CampaignRecipient.Status.OPTED_OUT, now,
                    "OPTED_OUT", "Contact sent STOP and cannot receive marketing messages");
            return new Outcome(false, null, "OPTED_OUT", false);
        }

        WaMessage message = new WaMessage();
        message.setOrganizationId(orgId);
        message.setConversationId(conversation.getId());
        message.setCampaignId(campaign.getId());
        message.setRecipientId(recipient.getId());
        message.setDirection(WaMessage.Direction.OUT);
        message.setMessageType("template");
        message.setTemplateName(templateName);
        message.setTemplateParams(campaign.getTemplateParams());
        message.setBody(campaign.getBodyPreview());
        message.setSequenceStep(sequenceStep);
        message.setSource(WaMessage.Source.CAMPAIGN);
        message.setStatus(WaMessage.Status.QUEUED);

        WhatsAppProvider provider = providerRegistry.forAccount(account);
        WhatsAppProvider.SendResult result = provider.sendTemplate(
                account,
                conversation.getPhoneE164(),
                templateName,
                campaign.getTemplateLang(),
                campaign.getTemplateParams());

        if (result.success()) {
            message.setWamid(result.wamid());
            message.setStatus(WaMessage.Status.SENT);
            message.setOccurredAt(now);
            message.setSentAt(now);
            messageRepository.save(message);

            // Delivery states only ever move forward: a contact who already replied
            // to the first message must not be reset to SENT by their relance.
            if (recipient.canAdvanceTo(CampaignRecipient.Status.SENT)) {
                recipient.setStatus(CampaignRecipient.Status.SENT);
                recipient.setSentAt(now);
            }
            if (sequenceStep > 0) {
                recipient.setFollowupCount(recipient.getFollowupCount() + 1);
                recipient.setLastFollowupAt(now);
            }
            recipientRepository.save(recipient);
            conversationService.recordOutbound(conversation.getId(), now, message.getBody());

            return new Outcome(true, message.getId(), null, false);
        }

        message.setStatus(WaMessage.Status.FAILED);
        message.setErrorCode(result.errorCode());
        message.setErrorTitle(result.errorTitle());
        messageRepository.save(message);

        // A retryable failure leaves the recipient alone so the scheduler can try
        // again; only a permanent rejection marks them FAILED in the CRM.
        if (!result.retryable()) {
            markTerminal(recipient, CampaignRecipient.Status.FAILED, now,
                    result.errorCode(), result.errorTitle());
        }

        log.warn("[wa] send failed campaign={} recipient={} code={}",
                campaign.getId(), recipient.getId(), result.errorCode());
        return new Outcome(false, message.getId(), result.errorCode(), result.retryable());
    }

    private void markTerminal(CampaignRecipient recipient, CampaignRecipient.Status status,
                              Instant at, String errorCode, String errorTitle) {
        if (!recipient.canAdvanceTo(status)) {
            return;
        }
        recipient.setStatus(status);
        recipient.setFailedAt(at);
        recipient.setErrorCode(errorCode);
        recipient.setErrorTitle(errorTitle);
        recipientRepository.save(recipient);
    }

    /** Convenience for template params that are not campaign-wide. */
    public List<String> resolveParams(Campaign campaign) {
        return campaign.getTemplateParams() == null ? List.of() : campaign.getTemplateParams();
    }

    public record Outcome(boolean success, UUID messageId, String errorCode, boolean retryable) {
    }
}
