package com.bento.crm.whatsapp.service;

import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.Permission;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.service.PartnerService;
import com.bento.crm.whatsapp.dto.BlockedNumberView;
import com.bento.crm.whatsapp.dto.ConversationPage;
import com.bento.crm.whatsapp.dto.ConversationView;
import com.bento.crm.whatsapp.dto.MessagePage;
import com.bento.crm.whatsapp.dto.MessageView;
import com.bento.crm.whatsapp.dto.UnreadSummary;
import com.bento.crm.whatsapp.event.WaChangeEvent;
import com.bento.crm.whatsapp.model.WaBlockedNumber;
import com.bento.crm.whatsapp.model.WaConversation;
import com.bento.crm.whatsapp.model.WaMessage;
import com.bento.crm.whatsapp.repository.WaBlockedNumberRepository;
import com.bento.crm.whatsapp.repository.WaConversationRepository;
import com.bento.crm.whatsapp.repository.WaMessageRepository;
import com.bento.crm.whatsapp.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the inbox plus the conversation-level actions (mark read, link a partner, create
 * a lead, ignore a number). Sending lives in {@link WaOutboxService}.
 *
 * <p>Every query filters on the actor's organization explicitly; the Hibernate tenant filter is
 * not relied on (it is a no-op downstream of the servlet filter, see TenantFilterBehaviourTest).
 */
@Service
@RequiredArgsConstructor
public class WaInboxService {

    public static final int DEFAULT_PAGE = 30;
    public static final int MAX_PAGE = 100;

    public enum Filter {
        ALL, UNREAD, UNANSWERED, MINE
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final WaConversationRepository conversationRepository;
    private final WaMessageRepository messageRepository;
    private final WaBlockedNumberRepository blockedRepository;
    private final WaConversationService conversationService;
    private final WaVisibility visibility;
    private final PartnerService partnerService;
    private final ApplicationEventPublisher events;

    private static final String CONVERSATION_COLUMNS = """
            SELECT c.id, c.phone_e164, c.display_name, c.partner_id, p.name AS partner_name,
                   p.type AS partner_type, p.assigned_to_user_id, c.last_message_at, c.last_message_preview,
                   c.last_message_direction, c.unread_count, c.window_expires_at, c.opted_out_at, c.last_read_at
            FROM wa_conversation c
            LEFT JOIN partner p ON p.id = c.partner_id AND p.organization_id = c.organization_id
                                AND p.deleted_at IS NULL
            """;

    @Transactional(readOnly = true)
    public ConversationPage listConversations(WaActor actor, Filter filter, String query, String cursor, Integer limit) {
        int size = clamp(limit);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", actor.organizationId())
                .addValue("me", actor.userId())
                .addValue("limit", size + 1);
        StringBuilder sql = new StringBuilder(CONVERSATION_COLUMNS).append("""
                WHERE c.organization_id = :orgId AND c.deleted_at IS NULL AND c.last_message_at IS NOT NULL
                """);
        if (!actor.readsAll() || filter == Filter.MINE) {
            sql.append(" AND (p.assigned_to_user_id = :me OR p.owner_id = :me)");
        }
        if (filter == Filter.UNREAD) {
            sql.append(" AND c.unread_count > 0");
        } else if (filter == Filter.UNANSWERED) {
            sql.append(" AND c.last_message_direction = 'IN'");
        }
        if (query != null && !query.isBlank()) {
            String digits = query.replaceAll("\\D", "");
            sql.append(" AND (c.display_name ILIKE :like OR p.name ILIKE :like");
            if (digits.length() >= 3) {
                sql.append(" OR c.phone_e164 LIKE :digits");
                params.addValue("digits", "%" + digits + "%");
            }
            sql.append(")");
            params.addValue("like", "%" + escapeLike(query.strip()) + "%");
        }
        Cursor after = Cursor.decode(cursor);
        if (after != null) {
            sql.append(" AND (c.last_message_at, c.id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", Timestamp.from(after.at())).addValue("cursorId", after.id());
        }
        sql.append(" ORDER BY c.last_message_at DESC, c.id DESC LIMIT :limit");

        List<ConversationView> rows = jdbc.query(sql.toString(), params, (rs, i) -> toView(rs));
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            ConversationView last = rows.get(size - 1);
            next = new Cursor(last.lastMessageAt(), last.id()).encode();
        }
        return new ConversationPage(rows, next);
    }

    @Transactional(readOnly = true)
    public ConversationView getConversation(WaActor actor, UUID conversationId) {
        visibility.requireConversation(actor, conversationId);
        return viewOf(actor.organizationId(), conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationView findByPartner(WaActor actor, UUID partnerId) {
        visibility.requirePartner(actor, partnerId);
        WaConversation conversation = conversationRepository.findByOrgAndPartner(actor.organizationId(), partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("No WhatsApp conversation with this partner"));
        return viewOf(actor.organizationId(), conversation.getId());
    }

    @Transactional(readOnly = true)
    public ConversationView findByPhone(WaActor actor, String phone) {
        String e164 = PhoneNumbers.toE164(phone)
                .orElseThrow(() -> new IllegalArgumentException("Not a usable phone number"));
        WaConversation conversation = conversationRepository.findByOrgAndPhone(actor.organizationId(), e164)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        visibility.requireConversation(actor, conversation.getId());
        return viewOf(actor.organizationId(), conversation.getId());
    }

    @Transactional(readOnly = true)
    public MessagePage listMessages(WaActor actor, UUID conversationId, String before, Integer limit) {
        visibility.requireConversation(actor, conversationId);
        int size = clamp(limit);
        Cursor cursor = Cursor.decode(before);
        PageRequest page = PageRequest.of(0, size + 1);
        List<WaMessage> messages = cursor == null
                ? messageRepository.findThreadPage(actor.organizationId(), conversationId, page)
                : messageRepository.findThreadPageBefore(actor.organizationId(), conversationId,
                cursor.at(), cursor.id(), page);
        String next = null;
        if (messages.size() > size) {
            messages = messages.subList(0, size);
            WaMessage oldest = messages.get(size - 1);
            next = new Cursor(oldest.getOccurredAt(), oldest.getId()).encode();
        }
        return new MessagePage(messages.stream().map(MessageView::from).toList(), next);
    }

    @Transactional
    public ConversationView markRead(WaActor actor, UUID conversationId) {
        visibility.requireConversation(actor, conversationId);
        conversationService.markRead(conversationId, Instant.now());
        events.publishEvent(WaChangeEvent.conversationUpdated(actor.organizationId(), conversationId));
        return viewOf(actor.organizationId(), conversationId);
    }

    /** Points the conversation at an existing partner (or, with null, detaches it). */
    @Transactional
    public ConversationView linkPartner(WaActor actor, UUID conversationId, UUID partnerId) {
        requireAny(actor, Permission.WHATSAPP_SEND);
        visibility.requireConversation(actor, conversationId);
        if (partnerId != null) {
            visibility.requirePartner(actor, partnerId);
        }
        conversationRepository.setPartner(actor.organizationId(), conversationId, partnerId);
        events.publishEvent(WaChangeEvent.conversationUpdated(actor.organizationId(), conversationId));
        return viewOf(actor.organizationId(), conversationId);
    }

    /** Creates a lead for an unlinked number, assigned to the person creating it, and links it. */
    @Transactional
    public ConversationView createLead(WaActor actor, UUID conversationId, String name) {
        requireAny(actor, Permission.PARTNERS_CREATE);
        WaConversation conversation = visibility.requireConversation(actor, conversationId);
        if (conversation.getPartnerId() != null) {
            throw new IllegalStateException("This conversation is already linked to a partner");
        }
        String leadName = name != null && !name.isBlank() ? name : conversation.getDisplayName();
        Partner lead = partnerService.findOrCreateWhatsAppLead(
                actor.organizationId(), conversation.getPhoneE164(), leadName, actor.userId());
        conversationRepository.setPartner(actor.organizationId(), conversationId, lead.getId());
        events.publishEvent(WaChangeEvent.conversationUpdated(actor.organizationId(), conversationId));
        return viewOf(actor.organizationId(), conversationId);
    }

    /**
     * "Ignore number": blocks the number for this organization and deletes everything stored for
     * it (messages and pending relances go with the conversation). For personal chats that should
     * never have been in the CRM.
     */
    @Transactional
    public void ignore(WaActor actor, UUID conversationId) {
        requireAny(actor, Permission.WHATSAPP_READ_ALL);
        WaConversation conversation = visibility.requireConversation(actor, conversationId);
        if (blockedRepository.findByOrgAndPhone(actor.organizationId(), conversation.getPhoneE164()).isEmpty()) {
            WaBlockedNumber blocked = new WaBlockedNumber();
            blocked.setOrganizationId(actor.organizationId());
            blocked.setPhoneE164(conversation.getPhoneE164());
            blocked.setReason("Ignored from the inbox");
            blockedRepository.save(blocked);
        }
        conversationRepository.delete(conversation);
        events.publishEvent(new WaChangeEvent(actor.organizationId(), WaChangeEvent.Type.CONVERSATION_REMOVED,
                conversationId, null));
    }

    @Transactional(readOnly = true)
    public UnreadSummary unreadSummary(WaActor actor) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) AS conversations, coalesce(sum(c.unread_count), 0) AS messages
                FROM wa_conversation c
                LEFT JOIN partner p ON p.id = c.partner_id AND p.organization_id = c.organization_id
                                    AND p.deleted_at IS NULL
                WHERE c.organization_id = :orgId AND c.deleted_at IS NULL AND c.unread_count > 0
                """);
        if (!actor.readsAll()) {
            sql.append(" AND (p.assigned_to_user_id = :me OR p.owner_id = :me)");
        }
        return jdbc.queryForObject(sql.toString(),
                new MapSqlParameterSource("orgId", actor.organizationId()).addValue("me", actor.userId()),
                (rs, i) -> new UnreadSummary(rs.getLong("conversations"), rs.getLong("messages")));
    }

    @Transactional(readOnly = true)
    public List<BlockedNumberView> listBlocked(WaActor actor) {
        requireAny(actor, Permission.WHATSAPP_ADMIN);
        return blockedRepository.findAllForOrg(actor.organizationId()).stream()
                .map(b -> new BlockedNumberView(b.getId(), b.getPhoneE164(), b.getReason(), b.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void unblock(WaActor actor, UUID blockedId) {
        requireAny(actor, Permission.WHATSAPP_ADMIN);
        WaBlockedNumber blocked = blockedRepository.findById(blockedId)
                .filter(b -> b.getOrganizationId().equals(actor.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Ignored number not found"));
        blockedRepository.delete(blocked);
    }

    private ConversationView viewOf(UUID orgId, UUID conversationId) {
        List<ConversationView> rows = jdbc.query(CONVERSATION_COLUMNS
                        + " WHERE c.organization_id = :orgId AND c.id = :id",
                new MapSqlParameterSource("orgId", orgId).addValue("id", conversationId), (rs, i) -> toView(rs));
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return rows.get(0);
    }

    private static ConversationView toView(ResultSet rs) throws SQLException {
        Instant windowExpiresAt = instant(rs, "window_expires_at");
        return new ConversationView(
                rs.getObject("id", UUID.class),
                rs.getString("phone_e164"),
                rs.getString("display_name"),
                rs.getObject("partner_id", UUID.class),
                rs.getString("partner_name"),
                rs.getString("partner_type"),
                rs.getObject("assigned_to_user_id", UUID.class),
                instant(rs, "last_message_at"),
                rs.getString("last_message_preview"),
                rs.getString("last_message_direction"),
                rs.getInt("unread_count"),
                windowExpiresAt != null && windowExpiresAt.isAfter(Instant.now()),
                windowExpiresAt,
                rs.getTimestamp("opted_out_at") != null,
                instant(rs, "last_read_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static void requireAny(WaActor actor, Permission permission) {
        if (!actor.can(permission)) {
            throw new AccessDeniedException("Not allowed: requires " + permission.getAuthority());
        }
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE;
        }
        return Math.min(limit, MAX_PAGE);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Keyset position ({@code at}, {@code id}), opaque to clients. */
    record Cursor(Instant at, UUID id) {

        String encode() {
            String raw = at.getEpochSecond() + "." + at.getNano() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                String[] time = parts[0].split("\\.", 2);
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])),
                        UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
