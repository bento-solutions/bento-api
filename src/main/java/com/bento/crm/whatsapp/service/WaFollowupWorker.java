package com.bento.crm.whatsapp.service;

import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaFollowup;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import com.bento.crm.whatsapp.repository.WaFollowupRepository;
import com.bento.crm.whatsapp.util.BusinessHours;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Transactional half of relance processing.
 *
 * <p>This is a separate bean from {@link WaFollowupScheduler} on purpose: Spring's
 * {@code @Transactional} works through a proxy, and a method calling another method
 * on {@code this} bypasses that proxy entirely. Keeping the scheduled loop and the
 * transactional units in different beans is what makes the transaction boundaries
 * real rather than decorative.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaFollowupWorker {

    private final WaFollowupRepository followupRepository;
    private final WaConversationRepository conversationRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final CampaignRepository campaignRepository;
    private final WaAccountRepository accountRepository;
    private final WhatsAppSendService sendService;
    private final WaFollowupService followupService;
    private final WaOutboxService outboxService;

    @Value("${whatsapp.followup.batch-size:50}")
    private int batchSize;

    @Value("${whatsapp.followup.business-hours.enabled:true}")
    private boolean businessHoursEnabled;

    @Value("${whatsapp.followup.business-hours.zone:Africa/Casablanca}")
    private String businessZone;

    @Value("${whatsapp.followup.business-hours.start:9}")
    private int businessStartHour;

    @Value("${whatsapp.followup.business-hours.end:18}")
    private int businessEndHour;

    @Value("${whatsapp.followup.max-attempts:3}")
    private int maxAttempts;

    /**
     * Phase one: lock due rows with SKIP LOCKED, flip them to CLAIMED, release.
     * Deliberately short — the lock must never span a call to Meta.
     */
    @Transactional
    public List<UUID> claimBatch() {
        Instant now = Instant.now();
        List<WaFollowup> due = followupRepository.claimDueBatch(
                WaFollowup.State.PENDING, now, PageRequest.of(0, batchSize));

        for (WaFollowup followup : due) {
            followup.setState(WaFollowup.State.CLAIMED);
            followup.setClaimedAt(now);
            followup.setAttempts(followup.getAttempts() + 1);
        }
        followupRepository.saveAll(due);
        return due.stream().map(WaFollowup::getId).toList();
    }

    /**
     * Phase two: decide whether the relance should still go out, then send it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(UUID followupId) {
        WaFollowup followup = followupRepository.findById(followupId).orElse(null);
        if (followup == null || followup.getState() != WaFollowup.State.CLAIMED) {
            return;
        }

        UUID orgId = followup.getOrganizationId();
        WaConversation conversation = conversationRepository.findById(followup.getConversationId()).orElse(null);
        CampaignRecipient recipient = recipientRepository.findById(followup.getRecipientId()).orElse(null);
        Campaign campaign = campaignRepository.findByOrganizationIdAndId(orgId, followup.getCampaignId()).orElse(null);

        if (conversation == null || recipient == null || campaign == null) {
            finish(followup, WaFollowup.State.SKIPPED, "Conversation, recipient or campaign no longer exists");
            return;
        }

        // The decisive re-check. Between the poll tick and this moment a contact can
        // reply, and the entire point of a relance is that it only reaches people who
        // did not. Reading inbound state here — inside the claim — closes almost all
        // of that race; what remains is a contact replying mid-send, which no amount
        // of locking prevents.
        if (recipient.getStatus() == CampaignRecipient.Status.REPLIED
                || (conversation.getLastInboundAt() != null
                    && conversation.getLastInboundAt().isAfter(followup.getCreatedAt()))) {
            finish(followup, WaFollowup.State.CANCELLED, "Contact replied before the relance was due");
            return;
        }

        if (conversation.isOptedOut()) {
            finish(followup, WaFollowup.State.CANCELLED, "Contact opted out");
            return;
        }

        // Outside business hours the relance is pushed forward rather than sent. A
        // marketing message landing at 03:00 is how a number earns blocks, and Meta
        // downgrades a number's quality rating on blocks.
        Instant nextWindow = nextBusinessWindow(Instant.now());
        if (nextWindow != null) {
            requeue(followup, nextWindow, null);
            return;
        }

        WaAccount account = accountRepository.findByOrganizationId(orgId).orElse(null);
        if (account == null) {
            finish(followup, WaFollowup.State.FAILED, "Organization has no WhatsApp number connected");
            return;
        }

        if (account.getProvider() == WaAccount.Provider.BAILEYS) {
            // A linked personal number: the relance is plain text through the paced outbox, like
            // the initial send; CampaignOutboxHooks counts it on the recipient once it goes out.
            String text = campaign.getFollowupBody() != null && !campaign.getFollowupBody().isBlank()
                    ? campaign.getFollowupBody()
                    : null;
            if (text == null) {
                finish(followup, WaFollowup.State.SKIPPED, "Campaign has no relance text");
                return;
            }
            WaMessage queued = outboxService.enqueueCampaign(orgId, campaign, recipient, conversation, text,
                    followup.getSequenceStep());
            followup.setSentMessageId(queued.getId());
            finish(followup, WaFollowup.State.SENT, null);
            return;
        }

        String template = campaign.getFollowupTemplateName() != null
                ? campaign.getFollowupTemplateName()
                : campaign.getTemplateName();

        WhatsAppSendService.Outcome outcome = sendService.sendTemplate(
                account, campaign, recipient, conversation, template, followup.getSequenceStep());

        if (outcome.success()) {
            followup.setSentMessageId(outcome.messageId());
            finish(followup, WaFollowup.State.SENT, null);
            return;
        }

        if (outcome.retryable() && followup.getAttempts() < maxAttempts) {
            // Linear backoff: 5 minutes per attempt already made.
            requeue(followup, Instant.now().plusSeconds(300L * followup.getAttempts()), outcome.errorCode());
            return;
        }

        finish(followup, WaFollowup.State.FAILED, outcome.errorCode());
    }

    private void requeue(WaFollowup followup, Instant dueAt, String error) {
        followup.setState(WaFollowup.State.PENDING);
        followup.setDueAt(dueAt);
        followup.setClaimedAt(null);
        followup.setLastError(error);
        followupRepository.save(followup);
    }

    private void finish(WaFollowup followup, WaFollowup.State state, String note) {
        followup.setState(state);
        followup.setLastError(note);
        followupRepository.saveAndFlush(followup);
        completeCampaignIfDone(followup.getOrganizationId(), followup.getCampaignId());
    }

    private void completeCampaignIfDone(UUID orgId, UUID campaignId) {
        followupService.completeCampaignIfDone(orgId, campaignId);
    }

    @Transactional
    public void markFailed(UUID followupId, String error) {
        followupRepository.findById(followupId).ifPresent(f -> {
            f.setState(WaFollowup.State.FAILED);
            f.setLastError(error);
            followupRepository.save(f);
        });
    }

    /**
     * @return when to retry if {@code at} falls outside business hours, or
     *         {@code null} if sending now is fine
     */
    Instant nextBusinessWindow(Instant at) {
        return new BusinessHours(businessHoursEnabled, ZoneId.of(businessZone), businessStartHour, businessEndHour)
                .nextWindow(at);
    }
}
