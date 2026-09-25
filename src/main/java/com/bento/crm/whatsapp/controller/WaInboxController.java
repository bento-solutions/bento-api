package com.bento.crm.whatsapp.controller;

import com.bento.crm.whatsapp.dto.BlockedNumberView;
import com.bento.crm.whatsapp.dto.ConversationPage;
import com.bento.crm.whatsapp.dto.ConversationView;
import com.bento.crm.whatsapp.dto.InboxRequests;
import com.bento.crm.whatsapp.dto.MessagePage;
import com.bento.crm.whatsapp.dto.MessageView;
import com.bento.crm.whatsapp.dto.UnreadSummary;
import com.bento.crm.whatsapp.service.WaActor;
import com.bento.crm.whatsapp.service.WaInboxService;
import com.bento.crm.whatsapp.service.WaOutboxService;
import com.bento.crm.whatsapp.service.WaStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * The WhatsApp inbox: conversations, threads, sending, drafts, and the live change stream.
 */
@RestController
@RequestMapping("/whatsapp")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Inbox", description = "Conversations, messages and drafts")
public class WaInboxController {

    private final WaInboxService inboxService;
    private final WaOutboxService outboxService;
    private final WaStreamService streamService;

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    @Operation(summary = "List conversations, newest activity first")
    public ConversationPage list(@RequestParam(defaultValue = "all") String filter,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        WaInboxService.Filter parsed;
        try {
            parsed = WaInboxService.Filter.valueOf(filter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("filter must be one of all, unread, unanswered, mine");
        }
        return inboxService.listConversations(WaActor.current(), parsed, q, cursor, limit);
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public ConversationView get(@PathVariable UUID id) {
        return inboxService.getConversation(WaActor.current(), id);
    }

    @GetMapping("/conversations/by-partner/{partnerId}")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public ConversationView byPartner(@PathVariable UUID partnerId) {
        return inboxService.findByPartner(WaActor.current(), partnerId);
    }

    @GetMapping("/conversations/lookup")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public ConversationView lookup(@RequestParam String phone) {
        return inboxService.findByPhone(WaActor.current(), phone);
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    @Operation(summary = "Thread messages, newest first; pass nextCursor as 'before' for older ones")
    public MessagePage messages(@PathVariable UUID id,
                                @RequestParam(required = false) String before,
                                @RequestParam(required = false) Integer limit) {
        return inboxService.listMessages(WaActor.current(), id, before, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAnyAuthority('WHATSAPP_SEND', 'WHATSAPP_DRAFT')")
    @Operation(summary = "Send (queue) or draft a reply in a conversation")
    public ResponseEntity<MessageView> send(@PathVariable UUID id, @RequestBody InboxRequests.SendMessage request) {
        var message = outboxService.enqueue(WaActor.current(), new WaOutboxService.SendCommand(
                id, null, request.text(), request.mode(), request.clientRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageView.from(message));
    }

    @PostMapping("/messages")
    @PreAuthorize("hasAnyAuthority('WHATSAPP_SEND', 'WHATSAPP_DRAFT')")
    @Operation(summary = "Message a partner, starting the conversation if needed")
    public ResponseEntity<MessageView> start(@RequestBody InboxRequests.StartMessage request) {
        var message = outboxService.enqueue(WaActor.current(), new WaOutboxService.SendCommand(
                null, request.partnerId(), request.text(), request.mode(), request.clientRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageView.from(message));
    }

    @GetMapping("/messages/{id}")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public MessageView message(@PathVariable UUID id) {
        return MessageView.from(outboxService.requireVisibleMessage(WaActor.current(), id));
    }

    @PostMapping("/messages/{id}/approve")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    @Operation(summary = "Approve a draft (optionally edited) into the send queue")
    public MessageView approve(@PathVariable UUID id, @RequestBody(required = false) InboxRequests.Approve request) {
        return MessageView.from(outboxService.approve(WaActor.current(), id, request == null ? null : request.text()));
    }

    @DeleteMapping("/messages/{id}")
    @PreAuthorize("hasAnyAuthority('WHATSAPP_SEND', 'WHATSAPP_DRAFT')")
    @Operation(summary = "Discard a draft or withdraw a message that has not been sent yet")
    public MessageView cancel(@PathVariable UUID id) {
        return MessageView.from(outboxService.cancel(WaActor.current(), id));
    }

    @PostMapping("/conversations/{id}/read")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public ConversationView read(@PathVariable UUID id) {
        return inboxService.markRead(WaActor.current(), id);
    }

    @PutMapping("/conversations/{id}/partner")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    public ConversationView linkPartner(@PathVariable UUID id, @RequestBody InboxRequests.LinkPartner request) {
        return inboxService.linkPartner(WaActor.current(), id, request.partnerId());
    }

    @PostMapping("/conversations/{id}/create-lead")
    @PreAuthorize("hasAuthority('PARTNERS_CREATE')")
    public ConversationView createLead(@PathVariable UUID id,
                                       @RequestBody(required = false) InboxRequests.CreateLead request) {
        return inboxService.createLead(WaActor.current(), id, request == null ? null : request.name());
    }

    @PostMapping("/conversations/{id}/ignore")
    @PreAuthorize("hasAuthority('WHATSAPP_READ_ALL')")
    @Operation(summary = "Ignore this number: block it and delete its stored messages")
    public ResponseEntity<Void> ignore(@PathVariable UUID id) {
        inboxService.ignore(WaActor.current(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread-summary")
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public UnreadSummary unreadSummary() {
        return inboxService.unreadSummary(WaActor.current());
    }

    @GetMapping("/blocked")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    public List<BlockedNumberView> blocked() {
        return inboxService.listBlocked(WaActor.current());
    }

    @DeleteMapping("/blocked/{id}")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    public ResponseEntity<Void> unblock(@PathVariable UUID id) {
        inboxService.unblock(WaActor.current(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Live change hints for the caller's organization. EventSource cannot set headers, so the
     * browser passes its access token as {@code ?token=}.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('WHATSAPP_READ')")
    public SseEmitter stream() {
        return streamService.subscribe(WaActor.current().organizationId());
    }
}
