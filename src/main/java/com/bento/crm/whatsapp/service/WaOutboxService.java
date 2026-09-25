package com.bento.crm.whatsapp.service;

import com.bento.crm.apitoken.repository.ApiTokenRepository;
import com.bento.crm.apitoken.service.ApiTokenService;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.Permission;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.whatsapp.event.WaChangeEvent;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import com.bento.crm.whatsapp.repository.WaBlockedNumberRepository;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import com.bento.crm.whatsapp.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Accepts outbound messages into the queue. Nothing here talks to a provider: a message is
 * persisted first (QUEUED, or DRAFT for a human to approve) and {@link WaOutboxWorker} sends it,
 * so a send survives a crash, is paced per account, and every source — the inbox, an AI agent,
 * a campaign — goes through the same rules.
 */
@Service
@RequiredArgsConstructor
public class WaOutboxService {

    public static final int MAX_TEXT_LENGTH = 4096;

    /** Queue order within an account: a person waiting on a reply goes before an agent, then campaigns. */
    public static final int PRIORITY_HUMAN = 30;
    public static final int PRIORITY_AGENT = 20;
    public static final int PRIORITY_CAMPAIGN = 10;

    private final WaMessageRepository messageRepository;
    private final WaAccountRepository accountRepository;
    private final ApiTokenRepository tokenRepository;
    private final WaBlockedNumberRepository blockedRepository;
    private final WaConversationService conversationService;
    private final WaVisibility visibility;
    private final WaOutboxScheduler scheduler;
    private final ApplicationEventPublisher events;

    public enum Mode {
        /** Queue for sending (subject to pacing). */
        AUTO,
        /** Store as a draft that a human approves, edits or discards in the inbox. */
        DRAFT
    }

    /**
     * @param conversationId an existing conversation, or null to address {@code partnerId}
     * @param partnerId      the partner to message when no conversation is given (uses their phone)
     * @param clientRef      optional idempotency key: repeating a request with the same key returns
     *                       the message it created instead of sending again
     */
    public record SendCommand(UUID conversationId, UUID partnerId, String text, Mode mode, String clientRef) {
    }

    @Transactional
    public WaMessage enqueue(WaActor actor, SendCommand command) {
        String text = command.text() == null ? "" : command.text().strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Message text is required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Message text is longer than " + MAX_TEXT_LENGTH + " characters");
        }
        Mode mode = effectiveMode(actor, command.mode());
        UUID orgId = actor.organizationId();
        if (actor.isAgent()) {
            enforceTokenCap(actor);
        }

        String clientRef = blankToNull(command.clientRef());
        if (clientRef != null) {
            var existing = messageRepository.findByOrgAndClientRef(orgId, clientRef);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        WaAccount account = accountRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new IllegalStateException("This organization has no WhatsApp number connected"));
        WaConversation conversation = resolveConversation(actor, command);

        if (blockedRepository.isBlocked(orgId, conversation.getPhoneE164())) {
            throw new IllegalStateException("This number is ignored; remove it from ignored numbers to message it");
        }
        Instant now = Instant.now();
        boolean windowOpen = conversation.getLastInboundAt() != null
                && conversation.getLastInboundAt().isAfter(now.minus(WaConversationService.SERVICE_WINDOW));
        if (conversation.isOptedOut() && (actor.isAgent() || !windowOpen)) {
            throw new IllegalStateException("This contact opted out of WhatsApp messages");
        }
        if (account.getProvider() == WaAccount.Provider.META && !conversation.isWindowOpen()) {
            throw new IllegalStateException(
                    "The contact has not written in the last 24 hours; WhatsApp only allows an approved template");
        }

        WaMessage message = new WaMessage();
        message.setOrganizationId(orgId);
        message.setConversationId(conversation.getId());
        message.setDirection(WaMessage.Direction.OUT);
        message.setMessageType("text");
        message.setBody(text);
        message.setSource(actor.isAgent() ? WaMessage.Source.AGENT : WaMessage.Source.HUMAN);
        message.setPriority(actor.isAgent() ? PRIORITY_AGENT : PRIORITY_HUMAN);
        message.setLane(windowOpen ? WaMessage.Lane.REPLY : WaMessage.Lane.OUTREACH);
        message.setNewChat(!messageRepository.hasOutbound(orgId, conversation.getId()));
        message.setStatus(mode == Mode.DRAFT ? WaMessage.Status.DRAFT : WaMessage.Status.QUEUED);
        message.setSentByUserId(actor.userId());
        message.setApiTokenId(actor.apiTokenId());
        message.setClientRef(clientRef);
        message.setOccurredAt(now);
        message.setNotBefore(now);
        message = messageRepository.save(message);

        if (mode == Mode.AUTO) {
            conversationService.recordOutbound(conversation.getId(), now, text);
            scheduler.kickAfterCommit(orgId);
        }
        events.publishEvent(WaChangeEvent.messageCreated(orgId, conversation.getId(), message.getId()));
        return message;
    }

    /**
     * Puts an agent's draft in the queue, optionally with the approver's edits. Only a person can
     * approve: an API token that could approve its own drafts would make draft mode meaningless.
     */
    @Transactional
    public WaMessage approve(WaActor actor, UUID messageId, String editedText) {
        if (actor.isAgent()) {
            throw new AccessDeniedException("Drafts can only be approved by a person signed in to the CRM");
        }
        if (!actor.can(Permission.WHATSAPP_SEND)) {
            throw new AccessDeniedException("Sending WhatsApp messages is not allowed for this role");
        }
        WaMessage draft = requireVisibleMessage(actor, messageId);
        String body = editedText == null || editedText.isBlank() ? null : editedText.strip();
        if (body != null && body.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Message text is longer than " + MAX_TEXT_LENGTH + " characters");
        }
        Instant now = Instant.now();
        if (messageRepository.approveDraft(actor.organizationId(), messageId, body, actor.userId(), now) == 0) {
            throw new IllegalStateException("This message is no longer a draft");
        }
        conversationService.recordOutbound(draft.getConversationId(), now, body != null ? body : draft.getBody());
        scheduler.kickAfterCommit(actor.organizationId());
        events.publishEvent(WaChangeEvent.messageUpdated(actor.organizationId(), draft.getConversationId(), messageId));
        return messageRepository.findByOrgAndId(actor.organizationId(), messageId).orElseThrow();
    }

    /** Discards a draft, or withdraws a queued message that has not been handed to WhatsApp yet. */
    @Transactional
    public WaMessage cancel(WaActor actor, UUID messageId) {
        WaMessage message = requireVisibleMessage(actor, messageId);
        if (actor.isAgent() && !actor.apiTokenId().equals(message.getApiTokenId())) {
            throw new AccessDeniedException("An API token can only cancel messages it created");
        }
        if (!actor.isAgent() && !actor.can(Permission.WHATSAPP_SEND) && !actor.can(Permission.WHATSAPP_DRAFT)) {
            throw new AccessDeniedException("Sending WhatsApp messages is not allowed for this role");
        }
        if (messageRepository.cancelIfUnsent(actor.organizationId(), messageId) == 0) {
            throw new IllegalStateException("This message has already been sent");
        }
        events.publishEvent(WaChangeEvent.messageUpdated(actor.organizationId(), message.getConversationId(), messageId));
        return messageRepository.findByOrgAndId(actor.organizationId(), messageId).orElseThrow();
    }

    /**
     * An agent can be steered by what contacts write to it (prompt injection), so each token has
     * a hard hourly budget of messages, drafts included, on top of the account's pacing.
     */
    private void enforceTokenCap(WaActor actor) {
        int cap = tokenRepository.findById(actor.apiTokenId())
                .map(t -> t.getMaxSendsPerHour() == null ? ApiTokenService.DEFAULT_MAX_SENDS_PER_HOUR : t.getMaxSendsPerHour())
                .orElse(0);
        long lastHour = messageRepository.countByApiTokenSince(actor.apiTokenId(), Instant.now().minus(Duration.ofHours(1)));
        if (lastHour >= cap) {
            throw new IllegalStateException("This API token reached its limit of " + cap + " messages per hour");
        }
    }

    public WaMessage requireVisibleMessage(WaActor actor, UUID messageId) {
        WaMessage message = messageRepository.findByOrgAndId(actor.organizationId(), messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        visibility.requireConversation(actor, message.getConversationId());
        return message;
    }

    /**
     * A token or role that may only draft always drafts, whatever it asked for. A person without
     * send rights is refused outright rather than silently downgraded.
     */
    private Mode effectiveMode(WaActor actor, Mode requested) {
        Mode mode = requested == null ? Mode.AUTO : requested;
        if (actor.can(Permission.WHATSAPP_SEND)) {
            return mode;
        }
        if (actor.can(Permission.WHATSAPP_DRAFT) && (mode == Mode.DRAFT || actor.isAgent())) {
            return Mode.DRAFT;
        }
        throw new AccessDeniedException("Sending WhatsApp messages is not allowed for this role");
    }

    private WaConversation resolveConversation(WaActor actor, SendCommand command) {
        if (command.conversationId() != null) {
            return visibility.requireConversation(actor, command.conversationId());
        }
        if (command.partnerId() == null) {
            throw new IllegalArgumentException("Either a conversation or a partner is required");
        }
        Partner partner = visibility.requirePartner(actor, command.partnerId());
        String phone = PhoneNumbers.toE164(partner.getPhone())
                .orElseThrow(() -> new IllegalStateException("This contact has no usable phone number"));
        return conversationService.getOrCreate(actor.organizationId(), phone, partner.getId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
