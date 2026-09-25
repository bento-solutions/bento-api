package com.bento.crm.whatsapp.service;

import com.bento.crm.campaign.model.Campaign;
import com.bento.crm.campaign.model.CampaignRecipient;
import com.bento.crm.campaign.repository.CampaignRecipientRepository;
import com.bento.crm.campaign.repository.CampaignRepository;
import com.bento.crm.notification.model.Notification;
import com.bento.crm.notification.service.NotificationService;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.whatsapp.ingest.InboundMessage;
import com.bento.crm.whatsapp.ingest.StatusUpdate;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single, provider-neutral path by which WhatsApp traffic enters the CRM: Meta's webhook and
 * the Baileys bot both normalize into {@link InboundMessage} / {@link StatusUpdate} and call here.
 *
 * <p>Tenancy: callers resolve the organization themselves (from Meta's phone_number_id, or the
 * bot session's account) and pass it explicitly; nothing here reads {@code TenantContext}.
 *
 * <p>Each call is one transaction. The conversation's activity columns and message statuses are
 * changed with single-statement updates and campaign recipients under a row lock, so concurrent
 * deliveries for the same contact serialize instead of failing a version check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaIngestService {

    /** A contact typing any of these is a legal opt-out and must be honoured immediately. */
    static final Pattern OPT_OUT = Pattern.compile(
            "^\\s*(stop|stop\\s*pub|arret|arr[êe]t|d[ée]sabonner|desabonnement|unsubscribe|cancel)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** At most one "new message" notification per conversation per this interval, unless it was read. */
    static final Duration NOTIFY_THROTTLE = Duration.ofMinutes(10);

    private static final Set<WaMessage.Status> PRE_SENT =
            EnumSet.of(WaMessage.Status.QUEUED, WaMessage.Status.SENDING);

    private final WaMessageRepository messageRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final CampaignRepository campaignRepository;
    private final PartnerRepository partnerRepository;
    private final WaConversationService conversationService;
    private final WaFollowupService followupService;
    private final NotificationService notificationService;

    public enum Outcome {
        STORED, DUPLICATE, IGNORED
    }

    /**
     * Stores one message and applies what it implies: a reply cancels relances and marks campaign
     * recipients REPLIED, STOP opts the contact out, and the right user is notified.
     */
    @Transactional
    public Outcome ingest(UUID orgId, InboundMessage message) {
        if (message.wamid() == null || message.phoneE164() == null) {
            return Outcome.IGNORED;
        }
        // Providers redeliver aggressively. wamid is the idempotency key that makes a redelivered
        // message a no-op rather than a duplicate timeline entry and a second notification.
        if (messageRepository.existsByOrgAndWamid(orgId, message.wamid())) {
            log.debug("[wa-ingest] duplicate {}, ignoring", message.wamid());
            return Outcome.DUPLICATE;
        }

        Instant occurredAt = message.occurredAt() != null ? message.occurredAt() : Instant.now();
        Partner partner = partnerRepository
                .findByOrganizationIdAndPhoneDigits(orgId, message.phoneE164().replaceAll("\\D", ""))
                .orElse(null);
        WaConversation conversation = conversationService.getOrCreate(
                orgId, message.phoneE164(), partner == null ? null : partner.getId());
        conversationService.recordIdentity(conversation.getId(), message.jid(), message.lid(),
                message.direction() == WaMessage.Direction.IN ? message.pushName() : null);

        boolean fromContact = message.direction() == WaMessage.Direction.IN;

        WaMessage stored = new WaMessage();
        stored.setOrganizationId(orgId);
        stored.setConversationId(conversation.getId());
        stored.setDirection(message.direction());
        stored.setWamid(message.wamid());
        stored.setMessageType(message.messageType() == null ? "text" : message.messageType());
        stored.setBody(message.body());
        stored.setMediaType(message.mediaType());
        stored.setMimeType(message.mimeType());
        stored.setFileName(message.fileName());
        stored.setQuotedWamid(message.quotedWamid());
        stored.setOccurredAt(occurredAt);
        if (fromContact) {
            stored.setSource(WaMessage.Source.CONTACT);
            stored.setStatus(WaMessage.Status.RECEIVED);
        } else {
            // Typed by the account owner on their own phone.
            stored.setSource(WaMessage.Source.PHONE);
            stored.setStatus(WaMessage.Status.SENT);
            stored.setSentAt(occurredAt);
        }
        messageRepository.save(stored);

        if (!fromContact) {
            conversationService.recordOutbound(conversation.getId(), occurredAt, message.body());
            if (!message.isHistory()) {
                // Answering from the phone means the owner has read the chat.
                conversationService.markRead(conversation.getId(), occurredAt);
            }
            return Outcome.STORED;
        }

        conversationService.recordInbound(conversation.getId(), occurredAt, message.body(), !message.isHistory());
        if (message.isHistory()) {
            return Outcome.STORED;
        }

        if (message.body() != null && OPT_OUT.matcher(message.body()).matches()) {
            handleOptOut(conversation, occurredAt);
            return Outcome.STORED;
        }

        // A reply cancels every pending relance on this conversation, including for other
        // campaigns the contact happens to be enrolled in — someone who answered should not then
        // be chased by an unrelated sequence.
        int cancelled = followupService.cancelForConversation(conversation.getId());

        List<CampaignRecipient> recipients = recipientRepository.findByConversationForUpdate(conversation.getId());
        for (CampaignRecipient recipient : recipients) {
            if (recipient.canAdvanceTo(CampaignRecipient.Status.REPLIED)) {
                recipient.setStatus(CampaignRecipient.Status.REPLIED);
                recipient.setRepliedAt(occurredAt);
                recipientRepository.save(recipient);
            }
        }
        recipientRepository.flush();

        // Cancelling this contact's relance may have been the last one outstanding, which
        // finishes the campaign.
        recipients.stream()
                .map(CampaignRecipient::getCampaignId)
                .distinct()
                .forEach(campaignId -> followupService.completeCampaignIfDone(orgId, campaignId));

        if (conversationService.claimNotification(conversation.getId(), Instant.now(), NOTIFY_THROTTLE)) {
            notifyAssignedUser(orgId, partner, conversation, message, recipients);
        }

        log.info("[wa-ingest] inbound on conversation {} ({} relance(s) cancelled, {} recipient(s) marked replied)",
                conversation.getId(), cancelled, recipients.size());
        return Outcome.STORED;
    }

    private void handleOptOut(WaConversation conversation, Instant at) {
        conversationService.optOut(conversation.getId(), at);
        followupService.cancelForConversation(conversation.getId());

        for (CampaignRecipient recipient : recipientRepository.findByConversationForUpdate(conversation.getId())) {
            // Not canAdvanceTo: its ranking puts OPTED_OUT (a contact skipped before sending)
            // below SENT, and anyone replying STOP has necessarily been sent something. A STOP
            // is a terminal legal state and overrides whatever delivery state came before.
            if (recipient.getStatus() != CampaignRecipient.Status.OPTED_OUT) {
                recipient.setStatus(CampaignRecipient.Status.OPTED_OUT);
                recipient.setErrorCode("OPTED_OUT");
                recipient.setErrorTitle("Contact sent STOP");
                recipientRepository.save(recipient);
            }
        }
        log.info("[wa-ingest] opt-out recorded for conversation {}", conversation.getId());
    }

    /**
     * Applies a delivery receipt to the outbound message and its campaign recipient. Receipts
     * only move forward: a late 'delivered' after 'read' fills in its timestamp but does not
     * walk the status back.
     *
     * @return whether the message is known to this organization
     */
    @Transactional
    public boolean applyStatus(UUID orgId, StatusUpdate update) {
        if (update.wamid() == null || update.status() == null) {
            return false;
        }
        Instant at = update.at() != null ? update.at() : Instant.now();

        Set<WaMessage.Status> from = switch (update.status()) {
            case SENT -> PRE_SENT;
            case DELIVERED -> EnumSet.of(WaMessage.Status.QUEUED, WaMessage.Status.SENDING, WaMessage.Status.SENT);
            case READ -> EnumSet.of(WaMessage.Status.QUEUED, WaMessage.Status.SENDING, WaMessage.Status.SENT,
                    WaMessage.Status.DELIVERED);
            // A failure reported after delivery is noise; the contact has the message.
            case FAILED -> EnumSet.of(WaMessage.Status.QUEUED, WaMessage.Status.SENDING, WaMessage.Status.SENT);
            default -> Set.of();
        };
        if (from.isEmpty()) {
            return false;
        }

        boolean failed = update.status() == WaMessage.Status.FAILED;
        boolean advanced = messageRepository.advanceStatus(orgId, update.wamid(), update.status(), from,
                failed ? update.errorCode() : null, failed ? update.errorTitle() : null) == 1;
        boolean known = messageRepository.recordReceiptTimes(orgId, update.wamid(),
                failed ? null : at,
                update.status() == WaMessage.Status.DELIVERED || update.status() == WaMessage.Status.READ ? at : null,
                update.status() == WaMessage.Status.READ ? at : null) == 1;
        if (!known) {
            log.debug("[wa-ingest] status for unknown wamid {}", update.wamid());
            return false;
        }

        WaMessage message = messageRepository.findByOrgAndWamid(orgId, update.wamid()).orElse(null);
        if (message == null || message.getRecipientId() == null || !advanced) {
            return true;
        }
        advanceRecipient(message, update.status(), at);
        return true;
    }

    private void advanceRecipient(WaMessage message, WaMessage.Status status, Instant at) {
        recipientRepository.findByIdForUpdate(message.getRecipientId()).ifPresent(recipient -> {
            CampaignRecipient.Status next = switch (status) {
                case DELIVERED -> CampaignRecipient.Status.DELIVERED;
                case READ -> CampaignRecipient.Status.READ;
                case FAILED -> CampaignRecipient.Status.FAILED;
                default -> CampaignRecipient.Status.SENT;
            };
            // Receipts are not ordered: canAdvanceTo keeps the CRM from walking a recipient
            // backwards (a 'delivered' landing after 'read', or after they replied).
            if (!recipient.canAdvanceTo(next)) {
                return;
            }
            recipient.setStatus(next);
            switch (next) {
                case DELIVERED -> recipient.setDeliveredAt(at);
                case READ -> recipient.setReadAt(at);
                case FAILED -> {
                    recipient.setFailedAt(at);
                    recipient.setErrorCode(message.getErrorCode());
                    recipient.setErrorTitle(message.getErrorTitle());
                }
                default -> recipient.setSentAt(at);
            }
            recipientRepository.save(recipient);
        });
    }

    /**
     * Notifies the CRM user who should see the message.
     *
     * <p>Preference order is assignee, then owner, then whoever launched the campaign. The last
     * fallback matters more than it looks: contacts imported in bulk routinely have no assignee,
     * and without it a reply — the entire point of the campaign — would be recorded and silently
     * shown to nobody.
     */
    private void notifyAssignedUser(UUID orgId, Partner partner, WaConversation conversation,
                                    InboundMessage message, List<CampaignRecipient> recipients) {
        UUID recipientUserId = partner != null ? partner.getAssignedToUserId() : null;
        if (recipientUserId == null && partner != null) {
            recipientUserId = partner.getOwnerId();
        }
        if (recipientUserId == null) {
            recipientUserId = recipients.stream()
                    .map(r -> campaignRepository.findByOrganizationIdAndId(orgId, r.getCampaignId()).orElse(null))
                    .filter(Objects::nonNull)
                    .map(Campaign::getCreatedBy)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        if (recipientUserId == null) {
            log.warn("[wa-ingest] inbound on conversation {} has no assignee, owner or campaign creator: "
                    + "no one will be notified", conversation.getId());
            return;
        }

        String who = partner != null ? partner.getName()
                : message.pushName() != null ? message.pushName() : conversation.getPhoneE164();
        String body = message.body();
        String preview = body == null ? "(no text)" : (body.length() > 160 ? body.substring(0, 160) + "…" : body);

        Notification notification = new Notification();
        notification.setOrganizationId(orgId);
        notification.setRecipientUserId(recipientUserId);
        notification.setType(Notification.NotificationType.WHATSAPP);
        notification.setTitle(who + " sent a WhatsApp message");
        notification.setMessage(preview);
        notification.setRelatedEntityType("WA_CONVERSATION");
        notification.setRelatedEntityId(conversation.getId());
        notification.setIsRead(false);

        notificationService.createForOrganization(orgId, notification);
    }
}
