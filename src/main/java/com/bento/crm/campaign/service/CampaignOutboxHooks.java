package com.bento.crm.campaign.service;

import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.whatsapp.model.WaFollowup;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaFollowupRepository;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import com.bento.crm.whatsapp.service.WaFollowupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a campaign learns from the outbox when one of its messages is sent or fails (paced
 * accounts, where campaign messages go through the outbox rather than straight to the provider).
 *
 * <p>Advances the recipient, schedules the relance after the initial send, and moves the
 * campaign out of SENDING once none of its messages is left in the queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignOutboxHooks {

    private static final Set<CampaignRecipient.Status> DELIVERED_OR_BETTER = EnumSet.of(
            CampaignRecipient.Status.SENT, CampaignRecipient.Status.DELIVERED, CampaignRecipient.Status.READ,
            CampaignRecipient.Status.REPLIED);

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final WaMessageRepository messageRepository;
    private final WaFollowupRepository followupRepository;
    private final WaFollowupService followupService;

    @Transactional
    public void onSent(WaMessage message, Instant at) {
        Campaign campaign = campaignRepository.findByOrganizationIdAndId(message.getOrganizationId(), message.getCampaignId())
                .orElse(null);
        if (campaign == null || message.getRecipientId() == null) {
            return;
        }
        int step = message.getSequenceStep() == null ? 0 : message.getSequenceStep();
        recipientRepository.findByIdForUpdate(message.getRecipientId()).ifPresent(recipient -> {
            if (recipient.canAdvanceTo(CampaignRecipient.Status.SENT)) {
                recipient.setStatus(CampaignRecipient.Status.SENT);
                recipient.setSentAt(at);
            }
            if (step > 0) {
                recipient.setFollowupCount(recipient.getFollowupCount() + 1);
                recipient.setLastFollowupAt(at);
            }
            recipientRepository.save(recipient);
        });
        if (step == 0 && Boolean.TRUE.equals(campaign.getFollowupEnabled())) {
            followupService.scheduleOrReschedule(message.getOrganizationId(), campaign, message.getConversationId(),
                    message.getRecipientId(), message.getId(), 1);
        }
        finishIfDrained(campaign);
    }

    @Transactional
    public void onFailed(WaMessage message, String errorCode, String errorTitle, Instant at) {
        Campaign campaign = campaignRepository.findByOrganizationIdAndId(message.getOrganizationId(), message.getCampaignId())
                .orElse(null);
        if (campaign == null || message.getRecipientId() == null) {
            return;
        }
        recipientRepository.findByIdForUpdate(message.getRecipientId()).ifPresent(recipient -> {
            if (recipient.canAdvanceTo(CampaignRecipient.Status.FAILED)) {
                recipient.setStatus(CampaignRecipient.Status.FAILED);
                recipient.setFailedAt(at);
                recipient.setErrorCode(errorCode);
                recipient.setErrorTitle(errorTitle);
                recipientRepository.save(recipient);
            }
        });
        finishIfDrained(campaign);
    }

    /** SENDING ends when the queue is empty: ACTIVE while relances are pending, else COMPLETED. */
    void finishIfDrained(Campaign campaign) {
        if (campaign.getStatus() != Campaign.Status.SENDING
                || messageRepository.countPendingForCampaign(campaign.getId()) > 0) {
            return;
        }
        long sent = recipientRepository.findAllByCampaign(campaign.getOrganizationId(), campaign.getId()).stream()
                .filter(r -> DELIVERED_OR_BETTER.contains(r.getStatus()))
                .count();
        long relancesPending = followupRepository.countForCampaignInState(
                campaign.getOrganizationId(), campaign.getId(), WaFollowup.State.PENDING);
        campaign.setSentCount(sent);
        campaign.setStatus(relancesPending > 0 ? Campaign.Status.ACTIVE : Campaign.Status.COMPLETED);
        campaignRepository.save(campaign);
        log.info("[campaign] {} finished sending: {} sent, {} relance(s) pending", campaign.getId(), sent, relancesPending);
    }
}
