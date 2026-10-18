package com.bento.crm.checklist.service;

import com.bento.crm.checklist.dto.ChecklistDtos;
import com.bento.crm.checklist.model.DocumentRequest;
import com.bento.crm.checklist.model.DocumentRequestItem;
import com.bento.crm.checklist.repository.DocumentRequestItemRepository;
import com.bento.crm.checklist.repository.DocumentRequestRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.mail.EmailService;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.notification.service.TeamNotifier;
import com.bento.crm.organization.repository.OrganizationRepository;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.whatsapp.service.WaActor;
import com.bento.crm.whatsapp.service.WaOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reminds clients of the documents still missing from their open checklists, by email and/or
 * WhatsApp, every {@code remindEveryDays} days. After {@link #MAX_AUTOMATIC} reminders the
 * client is left alone and the team is told to follow up by phone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistReminderService {

    static final int MAX_AUTOMATIC = 8;

    private final DocumentRequestRepository requestRepository;
    private final DocumentRequestItemRepository itemRepository;
    private final PartnerRepository partnerRepository;
    private final OrganizationRepository organizationRepository;
    private final EmailService emailService;
    private final WaOutboxService outboxService;
    private final TeamNotifier teamNotifier;
    private final JdbcTemplate jdbc;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Scheduled(cron = "${app.checklist.cron:0 0 9 * * MON-SAT}", zone = "Africa/Casablanca")
    public void daily() {
        int sent = run(Instant.now());
        if (sent > 0) log.info("[checklists] {} reminder(s) sent", sent);
    }

    /** Sends every reminder that is due at {@code now}. */
    public int run(Instant now) {
        int sent = 0;
        for (DocumentRequest r : requestRepository.openWithReminders()) {
            if (r.getLastReminderAt() != null
                    && r.getLastReminderAt().isAfter(now.minus(Duration.ofDays(r.getRemindEveryDays())).plus(Duration.ofHours(2)))) {
                continue; // a little slack so a daily run at 9:00 is not skipped by a 9:01 send yesterday
            }
            if (r.getReminderCount() >= MAX_AUTOMATIC) continue;
            TenantContext.setCurrentOrganizationId(r.getOrganizationId());
            try {
                ChecklistDtos.RemindResult res = send(r, false);
                if (res.email() || res.whatsapp()) sent++;
            } catch (RuntimeException e) {
                log.warn("[checklists] reminder for {} not sent: {}", r.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        return sent;
    }

    /** "Remind now" from the app. */
    @Transactional
    public ChecklistDtos.RemindResult remindNow(UUID id) {
        DocumentRequest r = requestRepository.find(TenantContext.getCurrentOrganizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Checklist not found"));
        if (r.getStatus() != DocumentRequest.Status.OPEN) throw new IllegalStateException("This checklist is not open");
        ChecklistDtos.RemindResult res = send(r, true);
        if (!res.email() && !res.whatsapp()) throw new IllegalStateException(res.problem());
        return res;
    }

    private ChecklistDtos.RemindResult send(DocumentRequest r, boolean manual) {
        List<DocumentRequestItem> missing = itemRepository.forRequest(r.getId()).stream()
                .filter(DocumentRequestItem::isRequired)
                .filter(i -> i.getStatus() == DocumentRequestItem.Status.MISSING || i.getStatus() == DocumentRequestItem.Status.REJECTED)
                .toList();
        if (missing.isEmpty()) return new ChecklistDtos.RemindResult(false, false, "Nothing is missing: the documents wait for your check");
        Partner p = partnerRepository.findByOrganizationIdAndId(r.getOrganizationId(), r.getPartnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Client not found"));
        String firm = organizationRepository.findById(r.getOrganizationId()).map(o -> o.getName()).orElse("");
        Optional<String> portalEmail = portalEmail(r);
        String link = portalEmail.isPresent() ? frontendUrl + "/portal/checklists/" + r.getId() : null;
        String email = p.getEmail() != null && !p.getEmail().isBlank() ? p.getEmail().strip() : portalEmail.orElse(null);

        DocumentRequest.Channel ch = manual && r.getRemindChannel() == DocumentRequest.Channel.NONE
                ? DocumentRequest.Channel.EMAIL : r.getRemindChannel();
        boolean wantEmail = ch == DocumentRequest.Channel.EMAIL || ch == DocumentRequest.Channel.BOTH;
        boolean wantWa = ch == DocumentRequest.Channel.WHATSAPP || ch == DocumentRequest.Channel.BOTH;
        boolean sentWa = false, sentEmail = false;
        String problem = null;

        if (wantWa) {
            try {
                sentWa = whatsApp(r, p, firm, missing, link);
            } catch (RuntimeException e) {
                problem = "WhatsApp: " + e.getMessage();
                wantEmail = true; // fall back to email when WhatsApp cannot reach the client
            }
        }
        if (wantEmail) {
            if (email == null) {
                problem = (problem == null ? "" : problem + ". ") + "The client has no email address";
            } else {
                emailService.sendRawHtml(email, "Documents attendus : " + r.getTitle(), html(r, p, firm, missing, link));
                sentEmail = true;
            }
        }
        if (sentEmail || sentWa) {
            jdbc.update("UPDATE document_request SET last_reminder_at = now(), reminder_count = reminder_count + 1 WHERE id = ?", r.getId());
            if (!manual && r.getReminderCount() + 1 == MAX_AUTOMATIC) {
                teamNotifier.notifyManagers(r.getOrganizationId(), "Documents still missing: " + DriveService.displayName(p),
                        r.getTitle() + ": " + MAX_AUTOMATIC + " reminders sent, no more will go out automatically",
                        "DOCUMENT_REQUEST", r.getId());
            }
        }
        return new ChecklistDtos.RemindResult(sentEmail, sentWa, problem);
    }

    private Optional<String> portalEmail(DocumentRequest r) {
        return jdbc.queryForList("SELECT email FROM portal_user WHERE organization_id = ? AND partner_id = ?"
                        + " AND is_active AND deleted_at IS NULL AND password_hash IS NOT NULL ORDER BY created_at LIMIT 1",
                String.class, r.getOrganizationId(), r.getPartnerId()).stream().findFirst();
    }

    private boolean whatsApp(DocumentRequest r, Partner p, String firm, List<DocumentRequestItem> missing, String link) {
        StringBuilder text = new StringBuilder("Bonjour, ").append(firm.isBlank() ? "votre cabinet" : firm)
                .append(" attend encore ces documents pour « ").append(r.getTitle()).append(" » :\n");
        missing.forEach(i -> text.append("• ").append(i.getLabel())
                .append(i.getStatus() == DocumentRequestItem.Status.REJECTED && i.getReviewNote() != null ? " (à renvoyer : " + i.getReviewNote() + ")" : "")
                .append('\n'));
        if (r.getDueDate() != null) text.append("Date limite : ").append(r.getDueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append('\n');
        WaActor system = new WaActor(r.getOrganizationId(), null, null, Set.of("WHATSAPP_SEND", "WHATSAPP_READ_ALL", "WHATSAPP_READ"));
        String ref = "checklist:" + r.getId() + ":" + (r.getReminderCount() + 1);
        try {
            outboxService.enqueue(system, new WaOutboxService.SendCommand(null, p.getId(),
                    link == null ? text.toString() : text + "Envoyez-les ici : " + link, WaOutboxService.Mode.AUTO, ref));
        } catch (IllegalStateException e) {
            if (link == null || !WaOutboxService.LINK_TO_STRANGER.equals(e.getMessage())) throw e;
            // A first message to this number cannot carry a link: send the list alone.
            outboxService.enqueue(system, new WaOutboxService.SendCommand(null, p.getId(), text.toString(),
                    WaOutboxService.Mode.AUTO, ref));
        }
        return true;
    }

    private static String html(DocumentRequest r, Partner p, String firm, List<DocumentRequestItem> missing, String link) {
        StringBuilder b = new StringBuilder("<div style='font-family:sans-serif;line-height:1.6;color:#333;'>");
        b.append("<p>Bonjour ").append(HtmlUtils.htmlEscape(DriveService.displayName(p))).append(",</p>");
        b.append("<p>").append(HtmlUtils.htmlEscape(firm.isBlank() ? "Votre cabinet" : firm))
                .append(" attend encore les documents suivants pour <strong>").append(HtmlUtils.htmlEscape(r.getTitle()))
                .append("</strong>").append(r.getPeriod() == null ? "" : " (" + HtmlUtils.htmlEscape(r.getPeriod()) + ")").append(" :</p><ul>");
        for (DocumentRequestItem i : missing) {
            b.append("<li>").append(HtmlUtils.htmlEscape(i.getLabel()));
            if (i.getStatus() == DocumentRequestItem.Status.REJECTED && i.getReviewNote() != null) {
                b.append(" <em>(à renvoyer : ").append(HtmlUtils.htmlEscape(i.getReviewNote())).append(")</em>");
            }
            b.append("</li>");
        }
        b.append("</ul>");
        if (r.getDueDate() != null) {
            b.append("<p>Date limite : ").append(r.getDueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("</p>");
        }
        if (link != null) {
            b.append("<p><a href='").append(HtmlUtils.htmlEscape(link)).append("'>Déposer les documents sur votre espace client</a></p>");
        } else {
            b.append("<p>Vous pouvez aussi les remettre directement à votre comptable.</p>");
        }
        return b.append("<p>Merci.</p></div>").toString();
    }
}
