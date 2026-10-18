package com.bento.crm.checklist.service;

import com.bento.crm.checklist.dto.ChecklistDtos;
import com.bento.crm.checklist.model.DocumentRequest;
import com.bento.crm.checklist.model.DocumentRequestItem;
import com.bento.crm.checklist.repository.DocumentRequestItemRepository;
import com.bento.crm.checklist.repository.DocumentRequestRepository;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.notification.service.TeamNotifier;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lists of documents a client must send for a period ("checklists"). Staff build them from a
 * template or by hand; the client uploads each item from the portal (or staff on their behalf);
 * staff accept or reject each upload. Reminders go out until every required item is accepted.
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    public static final String OWNER = "DOCUMENT_REQUEST";
    static final int MAX_ITEMS = 40;

    private final DocumentRequestRepository requestRepository;
    private final DocumentRequestItemRepository itemRepository;
    private final PartnerRepository partnerRepository;
    private final FileStorageService fileStorageService;
    private final DriveService driveService;
    private final TeamNotifier teamNotifier;
    private final JdbcTemplate jdbc;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    // Reading

    public List<ChecklistDtos.Summary> list(UUID partnerId, DocumentRequest.Status status) {
        StringBuilder sql = new StringBuilder(SUMMARY + " WHERE r.organization_id = ? AND r.deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(org()));
        if (partnerId != null) { sql.append(" AND r.partner_id = ?"); args.add(partnerId); }
        if (status != null) { sql.append(" AND r.status = ?"); args.add(status.name()); }
        sql.append(" ORDER BY r.status = 'OPEN' DESC, r.due_date NULLS LAST, r.created_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), ChecklistService::summary, args.toArray());
    }

    public ChecklistDtos.Detail get(UUID id) {
        return detail(org(), id);
    }

    ChecklistDtos.Detail detail(UUID orgId, UUID id) {
        List<ChecklistDtos.Summary> s = jdbc.query(SUMMARY + " WHERE r.organization_id = ? AND r.id = ? AND r.deleted_at IS NULL",
                ChecklistService::summary, orgId, id);
        if (s.isEmpty()) throw new ResourceNotFoundException("Checklist not found");
        String note = jdbc.queryForObject("SELECT note FROM document_request WHERE id = ?", String.class, id);
        return new ChecklistDtos.Detail(s.get(0), note, items(id));
    }

    List<ChecklistDtos.Item> items(UUID requestId) {
        return jdbc.query("""
                SELECT i.*, f.file_name, f.content_type FROM document_request_item i LEFT JOIN stored_file f ON f.id = i.file_id
                WHERE i.request_id = ? AND i.deleted_at IS NULL ORDER BY i.sort_order, i.created_at
                """, ChecklistService::item, requestId);
    }

    /** A client's checklists, for the portal: cancelled ones are left out. */
    public List<ChecklistDtos.Summary> forPartner(UUID orgId, UUID partnerId) {
        return jdbc.query(SUMMARY + " WHERE r.organization_id = ? AND r.partner_id = ? AND r.deleted_at IS NULL"
                + " AND r.status <> 'CANCELLED' ORDER BY r.status = 'OPEN' DESC, r.due_date NULLS LAST", ChecklistService::summary, orgId, partnerId);
    }

    public ChecklistDtos.Detail forPartner(UUID orgId, UUID partnerId, UUID id) {
        ChecklistDtos.Detail d = detail(orgId, id);
        if (!d.request().partnerId().equals(partnerId) || d.request().status() == DocumentRequest.Status.CANCELLED) {
            throw new ResourceNotFoundException("Checklist not found");
        }
        return d;
    }

    // Changing

    @Transactional
    public ChecklistDtos.Detail create(ChecklistDtos.CreateRequest r) {
        UUID orgId = org();
        if (r.partnerId() == null) throw new IllegalArgumentException("Choose the client");
        partnerRepository.findByOrganizationIdAndId(orgId, r.partnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Client not found"));
        List<String> labels = new ArrayList<>();
        String title = r.title();
        if (r.template() != null && !r.template().isBlank()) {
            ChecklistDtos.Template t = ChecklistTemplates.get(r.template());
            labels.addAll(t.items());
            if (title == null || title.isBlank()) title = t.title();
        }
        if (r.items() != null) r.items().stream().filter(s -> s != null && !s.isBlank()).map(String::strip).forEach(labels::add);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Name the checklist");
        if (labels.isEmpty()) throw new IllegalArgumentException("Add at least one document to ask for");
        if (labels.size() > MAX_ITEMS) throw new IllegalArgumentException("A checklist holds up to " + MAX_ITEMS + " documents");

        DocumentRequest req = new DocumentRequest();
        req.setOrganizationId(orgId);
        req.setPartnerId(r.partnerId());
        req.setTitle(cut(title.strip(), 160));
        apply(req, r.period(), r.dueDate(), r.remindChannel(), r.remindEveryDays(), r.note());
        req = requestRepository.saveAndFlush(req);
        int order = 0;
        for (String label : labels) {
            itemRepository.save(newItem(orgId, req.getId(), label, true, order++));
        }
        itemRepository.flush();
        return detail(orgId, req.getId());
    }

    @Transactional
    public ChecklistDtos.Detail update(UUID id, ChecklistDtos.UpdateRequest r) {
        DocumentRequest req = request(org(), id);
        if (r.title() != null) {
            if (r.title().isBlank()) throw new IllegalArgumentException("Name the checklist");
            req.setTitle(cut(r.title().strip(), 160));
        }
        apply(req, r.period(), r.dueDate(), r.remindChannel(), r.remindEveryDays(), r.note());
        if (r.status() != null && r.status() != req.getStatus()) {
            req.setStatus(r.status());
        }
        requestRepository.saveAndFlush(req);
        return detail(req.getOrganizationId(), id);
    }

    @Transactional
    public void delete(UUID id) {
        DocumentRequest req = request(org(), id);
        req.setDeletedAt(Instant.now());
        requestRepository.save(req);
    }

    @Transactional
    public ChecklistDtos.Detail addItem(UUID requestId, ChecklistDtos.ItemRequest r) {
        UUID orgId = org();
        DocumentRequest req = request(orgId, requestId);
        if (r.label() == null || r.label().isBlank()) throw new IllegalArgumentException("Name the document");
        List<DocumentRequestItem> existing = itemRepository.forRequest(requestId);
        if (existing.size() >= MAX_ITEMS) throw new IllegalArgumentException("A checklist holds up to " + MAX_ITEMS + " documents");
        int order = r.sortOrder() != null ? r.sortOrder() : existing.stream().mapToInt(DocumentRequestItem::getSortOrder).max().orElse(-1) + 1;
        itemRepository.saveAndFlush(newItem(orgId, requestId, r.label(), r.required() == null || r.required(), order));
        reopenIfNeeded(req);
        return detail(orgId, requestId);
    }

    @Transactional
    public ChecklistDtos.Detail updateItem(UUID itemId, ChecklistDtos.ItemRequest r) {
        DocumentRequestItem item = item(org(), itemId);
        if (r.label() != null) {
            if (r.label().isBlank()) throw new IllegalArgumentException("Name the document");
            item.setLabel(cut(r.label().strip(), 160));
        }
        if (r.required() != null) item.setRequired(r.required());
        if (r.sortOrder() != null) item.setSortOrder(r.sortOrder());
        itemRepository.saveAndFlush(item);
        settle(request(item.getOrganizationId(), item.getRequestId()));
        return detail(item.getOrganizationId(), item.getRequestId());
    }

    @Transactional
    public ChecklistDtos.Detail deleteItem(UUID itemId) {
        DocumentRequestItem item = item(org(), itemId);
        item.setDeletedAt(Instant.now());
        itemRepository.saveAndFlush(item);
        settle(request(item.getOrganizationId(), item.getRequestId()));
        return detail(item.getOrganizationId(), item.getRequestId());
    }

    @Transactional
    public ChecklistDtos.Detail review(UUID itemId, ChecklistDtos.Review r) {
        DocumentRequestItem item = item(org(), itemId);
        if (r.status() != DocumentRequestItem.Status.ACCEPTED && r.status() != DocumentRequestItem.Status.REJECTED) {
            throw new IllegalArgumentException("Accept the document or ask for it again");
        }
        if (item.getFileId() == null) throw new IllegalStateException("Nothing was sent for this document yet");
        if (r.status() == DocumentRequestItem.Status.REJECTED && (r.note() == null || r.note().isBlank())) {
            throw new IllegalArgumentException("Say what is wrong so the client can send it again");
        }
        item.setStatus(r.status());
        item.setReviewNote(r.note() == null || r.note().isBlank() ? null : cut(r.note().strip(), 500));
        itemRepository.saveAndFlush(item);
        DocumentRequest req = request(item.getOrganizationId(), item.getRequestId());
        if (r.status() == DocumentRequestItem.Status.REJECTED) {
            // The client hears about it at the next reminder, without waiting out the interval.
            req.setLastReminderAt(null);
            requestRepository.save(req);
        }
        settle(req);
        return detail(item.getOrganizationId(), item.getRequestId());
    }

    /** Staff file a document the client sent another way (email, in person). */
    @Transactional
    public ChecklistDtos.Detail staffUpload(UUID itemId, MultipartFile file) {
        DocumentRequestItem item = item(org(), itemId);
        receive(item, file, CurrentUser.idOrNull());
        item.setStatus(DocumentRequestItem.Status.ACCEPTED);
        itemRepository.saveAndFlush(item);
        settle(request(item.getOrganizationId(), item.getRequestId()));
        return detail(item.getOrganizationId(), item.getRequestId());
    }

    /** The client sends a document from the portal. Staff are told so they can check it. */
    @Transactional
    public ChecklistDtos.Detail portalUpload(UUID orgId, UUID partnerId, UUID itemId, MultipartFile file) {
        DocumentRequestItem item = item(orgId, itemId);
        DocumentRequest req = request(orgId, item.getRequestId());
        if (!req.getPartnerId().equals(partnerId) || req.getStatus() == DocumentRequest.Status.CANCELLED) {
            throw new ResourceNotFoundException("Document not found");
        }
        if (item.getStatus() == DocumentRequestItem.Status.ACCEPTED) {
            throw new IllegalStateException("This document was already accepted");
        }
        receive(item, file, null);
        item.setStatus(DocumentRequestItem.Status.RECEIVED);
        itemRepository.saveAndFlush(item);
        reopenIfNeeded(req);
        String client = partnerRepository.findByOrganizationIdAndId(orgId, partnerId).map(DriveService::displayName).orElse("A client");
        teamNotifier.notifyManagers(orgId, client + " sent a document", item.getLabel() + " (" + req.getTitle() + ")",
                "DOCUMENT_REQUEST", req.getId());
        return detail(orgId, req.getId());
    }

    private void receive(DocumentRequestItem item, MultipartFile file, UUID userId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty");
        DocumentRequest req = request(item.getOrganizationId(), item.getRequestId());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
        UUID folder = driveService.partnerFolder(item.getOrganizationId(), req.getPartnerId()).getId();
        StoredFile f = fileStorageService.storeBytes(item.getOrganizationId(), bytes, file.getOriginalFilename(),
                file.getContentType(), OWNER, req.getId(), StoredFile.Source.UPLOAD, folder);
        f.setPartnerId(req.getPartnerId());
        f.setUploadedByUserId(userId);
        f.setDescription(cut(item.getLabel() + " - " + req.getTitle(), 500));
        item.setFileId(f.getId());
        item.setReceivedAt(Instant.now());
        item.setReviewNote(null);
    }

    /** Complete once every required document is accepted; open again when one is missing. */
    private void settle(DocumentRequest req) {
        if (req.getStatus() == DocumentRequest.Status.CANCELLED) return;
        boolean done = itemRepository.forRequest(req.getId()).stream()
                .filter(DocumentRequestItem::isRequired)
                .allMatch(i -> i.getStatus() == DocumentRequestItem.Status.ACCEPTED);
        DocumentRequest.Status next = done ? DocumentRequest.Status.COMPLETE : DocumentRequest.Status.OPEN;
        if (next != req.getStatus()) {
            req.setStatus(next);
            requestRepository.saveAndFlush(req);
        }
    }

    private void reopenIfNeeded(DocumentRequest req) {
        settle(req);
    }

    private void apply(DocumentRequest req, String period, LocalDate dueDate, DocumentRequest.Channel channel,
                       Integer every, String note) {
        if (period != null) req.setPeriod(period.isBlank() ? null : cut(period.strip(), 20));
        if (dueDate != null) req.setDueDate(dueDate);
        if (channel != null) req.setRemindChannel(channel);
        if (every != null) {
            if (every < 1 || every > 30) throw new IllegalArgumentException("Remind every 1 to 30 days");
            req.setRemindEveryDays(every);
        }
        if (note != null) req.setNote(note.isBlank() ? null : cut(note.strip(), 1000));
    }

    private DocumentRequest request(UUID orgId, UUID id) {
        return requestRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Checklist not found"));
    }

    private DocumentRequestItem item(UUID orgId, UUID id) {
        return itemRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    private static DocumentRequestItem newItem(UUID orgId, UUID requestId, String label, boolean required, int order) {
        DocumentRequestItem i = new DocumentRequestItem();
        i.setOrganizationId(orgId);
        i.setRequestId(requestId);
        i.setLabel(cut(label.strip(), 160));
        i.setRequired(required);
        i.setSortOrder(order);
        return i;
    }

    static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final String SUMMARY = """
            SELECT r.*, p.name AS partner_name, p.company_name AS partner_company,
                   (SELECT count(*) FROM document_request_item i WHERE i.request_id = r.id AND i.deleted_at IS NULL) AS n_items,
                   (SELECT count(*) FROM document_request_item i WHERE i.request_id = r.id AND i.deleted_at IS NULL
                      AND i.status IN ('MISSING', 'REJECTED')) AS n_missing,
                   (SELECT count(*) FROM document_request_item i WHERE i.request_id = r.id AND i.deleted_at IS NULL
                      AND i.status = 'RECEIVED') AS n_check,
                   (SELECT count(*) FROM document_request_item i WHERE i.request_id = r.id AND i.deleted_at IS NULL
                      AND i.status = 'ACCEPTED') AS n_accepted
            FROM document_request r JOIN partner p ON p.id = r.partner_id
            """;

    private static ChecklistDtos.Summary summary(ResultSet rs, int i) throws SQLException {
        LocalDate due = rs.getObject("due_date", LocalDate.class);
        DocumentRequest.Status status = DocumentRequest.Status.valueOf(rs.getString("status"));
        java.sql.Timestamp last = rs.getTimestamp("last_reminder_at");
        String company = rs.getString("partner_company");
        return new ChecklistDtos.Summary(rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class),
                company == null || company.isBlank() ? rs.getString("partner_name") : company,
                rs.getString("title"), rs.getString("period"), due, status,
                DocumentRequest.Channel.valueOf(rs.getString("remind_channel")), rs.getInt("remind_every_days"),
                last == null ? null : last.toInstant(), rs.getInt("reminder_count"), rs.getInt("n_items"),
                rs.getInt("n_missing"), rs.getInt("n_check"), rs.getInt("n_accepted"),
                status == DocumentRequest.Status.OPEN && due != null && due.isBefore(LocalDate.now()));
    }

    private static ChecklistDtos.Item item(ResultSet rs, int i) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp("received_at");
        return new ChecklistDtos.Item(rs.getObject("id", UUID.class), rs.getString("label"), rs.getBoolean("required"),
                DocumentRequestItem.Status.valueOf(rs.getString("status")), rs.getObject("file_id", UUID.class),
                rs.getString("file_name"), t == null ? null : t.toInstant(), rs.getString("review_note"),
                rs.getInt("sort_order"), rs.getString("content_type"));
    }
}
