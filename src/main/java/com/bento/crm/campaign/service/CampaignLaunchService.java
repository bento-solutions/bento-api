package com.bento.crm.campaign.service;

import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.repository.WaBlockedNumberRepository;
import com.bento.crm.whatsapp.service.WaConversationService;
import com.bento.crm.whatsapp.service.WaOutboxService;
import com.bento.crm.whatsapp.service.WaFollowupService;
import com.bento.crm.whatsapp.service.WhatsAppSendService;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a set of selected partners into a running WhatsApp or Email campaign.
 *
 * <p>Dispatch happens off the request thread: a campaign of several hundred
 * contacts is several hundred network calls, which must not block the HTTP
 * response. The CRM reflects progress by polling per-recipient status instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignLaunchService {

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final CampaignRecipientService recipientService;
    private final WaAccountRepository accountRepository;
    private final WaConversationService conversationService;
    private final WhatsAppSendService sendService;
    private final WaFollowupService followupService;
    private final TaskExecutor whatsAppTaskExecutor;
    private final EmailService emailService;
    private final PartnerRepository partnerRepository;
    private final WaOutboxService outboxService;
    private final WaBlockedNumberRepository blockedRepository;

    /**
     * Creates a WhatsApp campaign from the /marketing composer and enrols the
     * selected contacts.
     */
    @Transactional
    public Campaign createWhatsAppCampaign(UUID orgId, com.bento.crm.campaign.dto.WhatsAppCampaignRequest request) {
        Campaign campaign = new Campaign();
        campaign.setOrganizationId(orgId);
        campaign.setTitle(request.getTitle());
        campaign.setChannel(Campaign.Channel.WHATSAPP);
        campaign.setStatus(Campaign.Status.DRAFT);
        campaign.setTemplateName(request.getTemplateName());
        campaign.setTemplateLang(request.getTemplateLang() == null ? "fr" : request.getTemplateLang());
        campaign.setTemplateParams(request.getTemplateParams() == null ? List.of() : request.getTemplateParams());
        campaign.setBodyPreview(request.getBodyPreview());
        campaign.setFollowupEnabled(request.isFollowupEnabled());
        campaign.setFollowupDelayDays(request.getFollowupDelayDays() == null ? 3 : request.getFollowupDelayDays());
        campaign.setFollowupTemplateName(request.getFollowupTemplateName());
        campaign.setFollowupBody(blankToNull(request.getFollowupBody()));
        campaign.setFollowupDelayMinutes(request.getFollowupDelayMinutes());
        campaign.setSentCount(0L);

        Campaign saved = campaignRepository.saveAndFlush(campaign);
        addRecipients(orgId, saved.getId(), request.getPartnerIds());
        return saved;
    }

    /**
     * Adds the selected partners to a campaign as recipients. Delegates to
     * {@link CampaignRecipientService#enroll}, which resolves each partner's contact detail for
     * whatever channel this campaign is (this service only ever creates WhatsApp campaigns, but
     * the enrollment step itself is channel-agnostic and shared with Email/SMS campaigns too).
     */
    @Transactional
    public List<CampaignRecipient> addRecipients(UUID orgId, UUID campaignId, List<UUID> partnerIds) {
        return recipientService.enroll(orgId, campaignId, partnerIds);
    }

    /**
     * Marks the campaign as sending and hands dispatch to a background thread.
     */
    @Transactional
    public Campaign launch(UUID orgId, UUID campaignId) {
        Campaign campaign = requireCampaign(orgId, campaignId);

        if (campaign.getStatus() == Campaign.Status.SENDING) {
            throw new IllegalStateException("Campaign is already sending");
        }

        if (campaign.getChannel() == Campaign.Channel.EMAIL) {
            campaign.setStatus(Campaign.Status.SENDING);
            campaign.setLaunchedAt(Instant.now());
            Campaign saved = campaignRepository.save(campaign);
            dispatchAfterCommit(() -> dispatchAllEmail(orgId, campaignId));
            return saved;
        }

        if (campaign.getChannel() != Campaign.Channel.WHATSAPP) {
            throw new IllegalStateException("Only WhatsApp and Email campaigns can be launched through this endpoint");
        }
        WaAccount linked = accountRepository.findByOrganizationId(orgId).orElse(null);
        if (linked != null && linked.getProvider() == WaAccount.Provider.BAILEYS) {
            return launchThroughOutbox(orgId, campaign, linked);
        }
        if (campaign.getTemplateName() == null || campaign.getTemplateName().isBlank()) {
            throw new IllegalStateException("Campaign has no WhatsApp template selected");
        }
        accountRepository.findByOrganizationId(orgId).orElseThrow(() ->
                new IllegalStateException("This organization has no WhatsApp number connected"));

        campaign.setStatus(Campaign.Status.SENDING);
        campaign.setLaunchedAt(Instant.now());
        Campaign saved = campaignRepository.save(campaign);

        // orgId is passed explicitly: TenantContext is a ThreadLocal bound to the
        // request thread and does not survive the hand-off.
        dispatchAfterCommit(() -> dispatchAll(orgId, campaignId));

        return saved;
    }

    /**
     * A linked personal number: no templates, and no burst. Every reachable recipient's message
     * is queued in the outbox (lowest priority, OUTREACH lane) in this transaction, and the paced
     * worker sends them over hours or days as the number's limits allow; CampaignOutboxHooks
     * tracks outcomes and ends SENDING when the queue drains.
     */
    private Campaign launchThroughOutbox(UUID orgId, Campaign campaign, WaAccount account) {
        String text = campaign.getBodyPreview() == null ? "" : campaign.getBodyPreview().strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("Write the message text for this campaign");
        }
        if (!account.isSessionOpen()) {
            throw new IllegalStateException("The WhatsApp number is not connected; link it again in Settings → WhatsApp");
        }
        Instant now = Instant.now();
        int queued = 0;
        for (CampaignRecipient recipient : recipientRepository.findAllByCampaign(orgId, campaign.getId())) {
            if (recipient.getStatus() != CampaignRecipient.Status.PENDING || recipient.getPhoneE164() == null) {
                continue;
            }
            if (blockedRepository.isBlocked(orgId, recipient.getPhoneE164())) {
                recipient.setStatus(CampaignRecipient.Status.SKIPPED);
                recipient.setErrorCode("IGNORED_NUMBER");
                recipient.setErrorTitle("This number is in the ignored list");
                recipientRepository.save(recipient);
                continue;
            }
            WaConversation conversation = conversationService.getOrCreate(
                    orgId, recipient.getPhoneE164(), recipient.getPartnerId());
            if (conversation.isOptedOut()) {
                recipient.setStatus(CampaignRecipient.Status.OPTED_OUT);
                recipient.setErrorCode("OPTED_OUT");
                recipient.setErrorTitle("Contact sent STOP and cannot receive marketing messages");
                recipient.setFailedAt(now);
                recipientRepository.save(recipient);
                continue;
            }
            recipient.setConversationId(conversation.getId());
            recipientRepository.save(recipient);
            outboxService.enqueueCampaign(orgId, campaign, recipient, conversation, text, 0);
            queued++;
        }
        if (queued == 0) {
            throw new IllegalStateException("No recipient of this campaign can be messaged");
        }
        campaign.setStatus(Campaign.Status.SENDING);
        campaign.setLaunchedAt(now);
        log.info("[campaign] {} queued {} message(s) in the outbox", campaign.getId(), queued);
        return campaignRepository.save(campaign);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /**
     * Hands dispatch to the background executor once the launch transaction has committed.
     * Started any earlier, the worker can read the campaign before its SENDING update is
     * visible and then overwrite it with a stale {@code @Version}, which fails the final
     * status update and leaves the campaign stuck in SENDING.
     */
    private void dispatchAfterCommit(Runnable dispatch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            whatsAppTaskExecutor.execute(dispatch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                whatsAppTaskExecutor.execute(dispatch);
            }
        });
    }

    /** Records the dispatch result on a freshly read campaign rather than the one loaded before sending. */
    private void finishDispatch(UUID orgId, UUID campaignId, long sent, Campaign.Status status) {
        campaignRepository.findByOrganizationIdAndId(orgId, campaignId).ifPresent(campaign -> {
            campaign.setSentCount(sent);
            campaign.setStatus(status);
            campaignRepository.save(campaign);
        });
    }

    /**
     * Sends the Email campaign to every PENDING recipient. Runs off-request.
     */
    public void dispatchAllEmail(UUID orgId, UUID campaignId) {
        try {
            Campaign campaign = campaignRepository.findByOrganizationIdAndId(orgId, campaignId).orElse(null);
            if (campaign == null) {
                return;
            }

            List<CampaignRecipient> recipients = recipientRepository.findAllByCampaign(orgId, campaignId);
            long sent = 0;

            for (CampaignRecipient recipient : recipients) {
                if (recipient.getStatus() != CampaignRecipient.Status.PENDING) {
                    continue;
                }
                String email = recipient.getEmail();
                if (email == null || email.isBlank() || !email.contains("@")) {
                    if (recipient.getPartnerId() != null) {
                        var pOpt = partnerRepository.findByOrganizationIdAndId(orgId, recipient.getPartnerId());
                        if (pOpt.isPresent()) {
                            email = pOpt.get().getEmail();
                        }
                    }
                }

                if (email != null && !email.isBlank() && email.contains("@")) {
                    String subject = campaign.getTitle();
                    String body = campaign.getBodyPreview() != null && !campaign.getBodyPreview().isBlank()
                            ? campaign.getBodyPreview()
                            : campaign.getTitle();
                    emailService.sendRawHtml(email, subject, "<div style='font-family:sans-serif;line-height:1.6;color:#333;'>" + body + "</div>");
                    recipient.setStatus(CampaignRecipient.Status.SENT);
                    recipient.setSentAt(Instant.now());
                    recipientRepository.save(recipient);
                    sent++;
                } else {
                    recipient.setStatus(CampaignRecipient.Status.FAILED);
                    recipient.setErrorCode("NO_EMAIL");
                    recipient.setErrorTitle("Missing or invalid recipient email address");
                    recipientRepository.save(recipient);
                }
            }

            finishDispatch(orgId, campaignId, sent, Campaign.Status.COMPLETED);

            log.info("[email] campaign {} dispatched: {}/{} sent", campaignId, sent, recipients.size());
        } catch (Exception e) {
            log.error("[email] dispatch failed for campaign {}", campaignId, e);
        }
    }

    /**
     * Sends the campaign to every PENDING recipient. Runs off-request.
     */
    public void dispatchAll(UUID orgId, UUID campaignId) {
        try {
            Campaign campaign = campaignRepository.findByOrganizationIdAndId(orgId, campaignId).orElse(null);
            WaAccount account = accountRepository.findByOrganizationId(orgId).orElse(null);
            if (campaign == null || account == null) {
                log.error("[wa] cannot dispatch campaign={} org={}: missing campaign or account", campaignId, orgId);
                return;
            }

            List<CampaignRecipient> recipients = recipientRepository.findAllByCampaign(orgId, campaignId);
            long sent = 0;

            for (CampaignRecipient recipient : recipients) {
                if (recipient.getStatus() != CampaignRecipient.Status.PENDING) {
                    continue;
                }
                if (dispatchOne(orgId, campaign, account, recipient)) {
                    sent++;
                }
            }

            // The campaign stays ACTIVE while relances are still queued; only once
            // nothing is pending is it genuinely finished.
            finishDispatch(orgId, campaignId, sent, Boolean.TRUE.equals(campaign.getFollowupEnabled())
                    ? Campaign.Status.ACTIVE
                    : Campaign.Status.COMPLETED);

            log.info("[wa] campaign {} dispatched: {}/{} sent", campaignId, sent, recipients.size());

        } catch (Exception e) {
            log.error("[wa] dispatch failed for campaign {}", campaignId, e);
        }
    }

    private boolean dispatchOne(UUID orgId, Campaign campaign, WaAccount account, CampaignRecipient recipient) {
        try {
            WaConversation conversation = conversationService.getOrCreate(
                    orgId, recipient.getPhoneE164(), recipient.getPartnerId());

            recipient.setConversationId(conversation.getId());
            recipientRepository.save(recipient);

            WhatsAppSendService.Outcome outcome = sendService.sendTemplate(
                    account, campaign, recipient, conversation, campaign.getTemplateName(), 0);

            if (outcome.success() && Boolean.TRUE.equals(campaign.getFollowupEnabled())) {
                followupService.scheduleOrReschedule(
                        orgId, campaign, conversation.getId(), recipient.getId(), outcome.messageId(), 1);
            }
            return outcome.success();

        } catch (Exception e) {
            log.error("[wa] failed to dispatch recipient {}", recipient.getId(), e);
            return false;
        }
    }

    private Campaign requireCampaign(UUID orgId, UUID campaignId) {
        return campaignRepository.findByOrganizationIdAndId(orgId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
    }
}
