package com.bento.crm.ticket.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.EntityLink;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.common.repository.EntityLinkSpecifications;
import com.bento.crm.notification.event.AssignmentNotificationFactory;
import com.bento.crm.task.dto.CreateTaskRequest;
import com.bento.crm.task.dto.TaskProgress;
import com.bento.crm.task.model.Task;
import com.bento.crm.task.service.TaskService;
import com.bento.crm.ticket.dto.CreateTicketRequest;
import com.bento.crm.ticket.dto.CreateTicketTaskRequest;
import com.bento.crm.ticket.model.Ticket;
import com.bento.crm.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.bento.crm.ticket.repository.TicketCommentRepository ticketCommentRepository;

    @Transactional
    public Ticket createTicket(CreateTicketRequest request) {
        Ticket ticket = new Ticket();
        applyRequest(ticket, request);
        UUID orgId = TenantContext.getCurrentOrganizationId();
        ticket.setOrganizationId(orgId);
        Ticket saved = ticketRepository.save(ticket);
        notifyIfAssigned(orgId, null, saved);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(orgId)
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.TICKET_CREATED)
                .entityType("TICKET")
                .entityId(saved.getId())
                .payload(ticketToPayload(saved))
                .build());
        return saved;
    }

    public Ticket getTicket(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return ticketRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    public Page<Ticket> listTickets(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return ticketRepository.findByOrganizationId(orgId, pageable);
    }

    /**
     * Lists the tickets attached to a given record, e.g. everything opened against one customer.
     * Both filter arguments are optional; with neither set this is equivalent to
     * {@link #listTickets(Pageable)}.
     */
    public Page<Ticket> listTickets(RelatedEntityType relatedEntityType, UUID relatedEntityId, Pageable pageable) {
        if (relatedEntityType == null && relatedEntityId == null) {
            return listTickets(pageable);
        }
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Specification<Ticket> spec = EntityLinkSpecifications.<Ticket>inOrganization(orgId)
                .and(EntityLinkSpecifications.relatedTo(relatedEntityType, relatedEntityId));
        return ticketRepository.findAll(spec, pageable);
    }

    /** Progress of the tasks raised for each given ticket; tickets without tasks are absent. */
    public Map<UUID, TaskProgress> taskProgressFor(Collection<UUID> ticketIds) {
        return taskService.progressFor(RelatedEntityType.TICKET, ticketIds);
    }

    public TaskProgress taskProgressFor(UUID ticketId) {
        return taskProgressFor(List.of(ticketId)).getOrDefault(ticketId, TaskProgress.none(ticketId));
    }

    /** The tasks raised for one ticket. 404s on an unknown ticket rather than returning an empty page. */
    public Page<Task> listTasks(UUID ticketId, Pageable pageable) {
        Ticket ticket = getTicket(ticketId);
        return taskService.listTasks(RelatedEntityType.TICKET, ticket.getId(), pageable);
    }

    /**
     * Raises a task for a ticket. The task is linked back to the ticket and, where the request
     * leaves them blank, takes the ticket's assignee, priority and deadline — so the person
     * working the ticket gets the sub-task by default and it inherits the ticket's urgency.
     */
    @Transactional
    public Task createTask(UUID ticketId, CreateTicketTaskRequest request) {
        Ticket ticket = getTicket(ticketId);

        CreateTaskRequest taskRequest = new CreateTaskRequest();
        taskRequest.setTitle(request.getTitle());
        taskRequest.setDescription(request.getDescription());
        taskRequest.setAssignedTeamId(request.getAssignedTeamId());
        taskRequest.setAssignedToUserId(request.getAssignedToUserId() != null
                ? request.getAssignedToUserId() : ticket.getAssignedToUserId());
        taskRequest.setAssignedByUserId(currentActor());
        taskRequest.setStatus(request.getStatus() != null ? request.getStatus() : Task.TaskStatus.TODO);
        taskRequest.setPriority(request.getPriority() != null
                ? request.getPriority() : taskPriorityFor(ticket.getPriority()));
        taskRequest.setDueDate(request.getDueDate() != null ? request.getDueDate() : ticket.getDeadline());
        taskRequest.setRelatedEntityType(RelatedEntityType.TICKET);
        taskRequest.setRelatedEntityId(ticket.getId());
        return taskService.createTask(taskRequest);
    }

    /** Ticket priorities have one more level than task priorities; HIGH and URGENT both map to URGENT. */
    static Task.Priority taskPriorityFor(Ticket.Priority priority) {
        if (priority == null) {
            return null;
        }
        return switch (priority) {
            case LOW -> Task.Priority.LOW;
            case MEDIUM -> Task.Priority.MEDIUM;
            case HIGH, URGENT -> Task.Priority.URGENT;
        };
    }

    @Transactional
    public Ticket updateTicket(UUID id, CreateTicketRequest request) {
        Ticket ticket = getTicket(id);
        UUID previousAssignee = ticket.getAssignedToUserId();
        applyRequest(ticket, request);
        Ticket saved = ticketRepository.save(ticket);
        notifyIfAssigned(saved.getOrganizationId(), previousAssignee, saved);
        eventPublisher.publishEvent(com.bento.crm.automation.event.EntityChangedEvent.builder()
                .organizationId(saved.getOrganizationId())
                .trigger(com.bento.crm.automation.model.AutomationRule.Trigger.TICKET_UPDATED)
                .entityType("TICKET")
                .entityId(saved.getId())
                .payload(ticketToPayload(saved))
                .build());
        return saved;
    }

    private java.util.Map<String, Object> ticketToPayload(Ticket t) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", t.getId());
        map.put("title", t.getTitle());
        map.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        map.put("priority", t.getPriority() != null ? t.getPriority().name() : null);
        map.put("type", t.getType());
        map.put("partnerId", t.getPartnerId());
        map.put("assignedToUserId", t.getAssignedToUserId());
        return map;
    }

    private void notifyIfAssigned(UUID orgId, UUID previousAssignee, Ticket ticket) {
        UUID current = ticket.getAssignedToUserId();
        if (current == null || Objects.equals(current, previousAssignee)) {
            return;
        }
        eventPublisher.publishEvent(AssignmentNotificationFactory.forTicket(
                orgId, current, currentActor(), ticket.getId(), ticket.getTitle()));
    }

    private static UUID currentActor() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                    : null;
            return principal instanceof String s ? UUID.fromString(s) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyRequest(Ticket ticket, CreateTicketRequest request) {
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setType(request.getType());
        ticket.setRelatedEntity(resolveLink(request));
        ticket.setPartnerId(ticket.getRelatedEntity().idOf(RelatedEntityType.PARTNER));
        ticket.setAssignedToUserId(request.getAssignedToUserId());
        ticket.setStatus(request.getStatus());
        ticket.setPriority(request.getPriority());
        ticket.setDeadline(request.getDeadline());
        ticket.setResolution(request.getResolution());
    }

    /**
     * Reads the ticket's optional link, falling back to the legacy {@code partnerId} shorthand so
     * clients that only know about the partner field keep behaving exactly as before.
     */
    private EntityLink resolveLink(CreateTicketRequest request) {
        EntityLink link = request.toEntityLink();
        return link.isPresent() ? link : EntityLink.of(RelatedEntityType.PARTNER, request.getPartnerId());
    }

    @Transactional
    public void deleteTicket(UUID id) {
        Ticket ticket = getTicket(id);
        ticket.setDeletedAt(java.time.Instant.now());
        ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket restoreTicket(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Ticket ticket = ticketRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        ticket.setDeletedAt(null);
        return ticketRepository.save(ticket);
    }

    public Page<Ticket> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return ticketRepository.findDeletedByOrganizationId(orgId, pageable);
    }

    public List<com.bento.crm.ticket.model.TicketComment> getComments(UUID ticketId) {
        getTicket(ticketId);
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return ticketCommentRepository.findByOrganizationIdAndTicketId(orgId, ticketId);
    }

    @Transactional
    public com.bento.crm.ticket.model.TicketComment addComment(UUID ticketId, com.bento.crm.ticket.dto.CreateTicketCommentRequest request) {
        Ticket ticket = getTicket(ticketId);
        UUID orgId = TenantContext.getCurrentOrganizationId();
        com.bento.crm.ticket.model.TicketComment comment = com.bento.crm.ticket.model.TicketComment.builder()
                .ticketId(ticket.getId())
                .authorId(request.getAuthorId() != null ? request.getAuthorId() : currentActor())
                .authorName(request.getAuthorName() != null ? request.getAuthorName() : "User")
                .authorRole(request.getAuthorRole() != null ? request.getAuthorRole() : "AGENT")
                .content(request.getContent())
                .isInternal(Boolean.TRUE.equals(request.getIsInternal()))
                .build();
        comment.setOrganizationId(orgId);
        return ticketCommentRepository.save(comment);
    }
}
