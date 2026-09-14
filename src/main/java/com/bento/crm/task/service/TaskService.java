package com.bento.crm.task.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.common.repository.EntityLinkSpecifications;
import com.bento.crm.notification.event.AssignmentNotificationFactory;
import com.bento.crm.task.dto.CreateTaskRequest;
import com.bento.crm.task.dto.TaskProgress;
import com.bento.crm.task.model.Task;
import com.bento.crm.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Task createTask(CreateTaskRequest request) {
        Task task = new Task();
        applyRequest(task, request);
        UUID orgId = TenantContext.getCurrentOrganizationId();
        task.setOrganizationId(orgId);
        Task saved = taskRepository.save(task);
        notifyIfAssigned(orgId, null, saved);
        return saved;
    }

    public Task getTask(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return taskRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
    }

    public Page<Task> listTasks(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return taskRepository.findByOrganizationId(orgId, pageable);
    }

    /**
     * Lists the tasks attached to a given record, e.g. everything raised for one customer.
     * Both filter arguments are optional; with neither set this is equivalent to
     * {@link #listTasks(Pageable)}.
     */
    public Page<Task> listTasks(RelatedEntityType relatedEntityType, UUID relatedEntityId, Pageable pageable) {
        if (relatedEntityType == null && relatedEntityId == null) {
            return listTasks(pageable);
        }
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Specification<Task> spec = EntityLinkSpecifications.<Task>inOrganization(orgId)
                .and(EntityLinkSpecifications.relatedTo(relatedEntityType, relatedEntityId));
        return taskRepository.findAll(spec, pageable);
    }

    /**
     * Task progress for each of the given records, keyed by record id. Records without tasks are
     * absent from the map, so look them up with {@code getOrDefault(id, TaskProgress.none(id))}.
     */
    public Map<UUID, TaskProgress> progressFor(RelatedEntityType type, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return taskRepository.progressByRelatedEntity(orgId, type, ids, Task.TaskStatus.DONE).stream()
                .collect(Collectors.toMap(TaskProgress::relatedEntityId, Function.identity()));
    }

    @Transactional
    public Task updateTask(UUID id, CreateTaskRequest request) {
        Task task = getTask(id);
        UUID previousAssignee = task.getAssignedToUserId();
        applyRequest(task, request);
        Task saved = taskRepository.save(task);
        notifyIfAssigned(saved.getOrganizationId(), previousAssignee, saved);
        return saved;
    }

    /**
     * Notifies the new assignee only — skips unassignments and unchanged assignees
     * so unrelated edits stay silent.
     */
    private void notifyIfAssigned(UUID orgId, UUID previousAssignee, Task task) {
        UUID current = task.getAssignedToUserId();
        if (current == null || Objects.equals(current, previousAssignee)) {
            return;
        }
        eventPublisher.publishEvent(AssignmentNotificationFactory.forTask(
                orgId, current, currentActor(), task.getId(), task.getTitle()));
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

    private void applyRequest(Task task, CreateTaskRequest request) {
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setAssignedTeamId(request.getAssignedTeamId());
        task.setAssignedToUserId(request.getAssignedToUserId());
        task.setAssignedByUserId(request.getAssignedByUserId());
        task.setStatus(request.getStatus());
        task.setPriority(request.getPriority());
        task.setDueDate(request.getDueDate());
        task.setRelatedEntity(request.toEntityLink());
    }

    @Transactional
    public void deleteTask(UUID id) {
        Task task = getTask(id);
        task.setDeletedAt(java.time.Instant.now());
        taskRepository.save(task);
    }

    @Transactional
    public Task restoreTask(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Task task = taskRepository.findByOrganizationIdAndIdIncludingDeleted(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        task.setDeletedAt(null);
        return taskRepository.save(task);
    }

    public Page<Task> listDeleted(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return taskRepository.findDeletedByOrganizationId(orgId, pageable);
    }
}
