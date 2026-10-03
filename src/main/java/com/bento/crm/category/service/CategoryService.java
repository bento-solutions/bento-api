package com.bento.crm.category.service;

import com.bento.crm.category.dto.CategoryRequest;
import com.bento.crm.category.model.Category;
import com.bento.crm.category.repository.CategoryRepository;
import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.task.repository.TaskRepository;
import com.bento.crm.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TicketRepository ticketRepository;
    private final TaskRepository taskRepository;

    public List<Category> list() {
        return categoryRepository.findAllActiveByOrganizationId(TenantContext.getCurrentOrganizationId());
    }

    public Category get(UUID id) {
        return categoryRepository.findByOrganizationIdAndId(TenantContext.getCurrentOrganizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    /**
     * Validates a category id coming from a ticket or task request.
     *
     * @return the id, or {@code null} when none was given (a category is optional)
     * @throws ResourceNotFoundException if the id names no live category of this organization
     */
    public UUID requireId(UUID id) {
        return id == null ? null : get(id).getId();
    }

    @Transactional
    public Category create(CategoryRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Category category = new Category();
        category.setOrganizationId(orgId);
        apply(category, request, null);
        return categoryRepository.save(category);
    }

    @Transactional
    public Category update(UUID id, CategoryRequest request) {
        Category category = get(id);
        apply(category, request, id);
        return categoryRepository.save(category);
    }

    /**
     * Removes the category and takes it off every ticket and task that carried it, so nothing is
     * left pointing at a category that no longer shows up in the list.
     */
    @Transactional
    public void delete(UUID id) {
        Category category = get(id);
        UUID orgId = category.getOrganizationId();
        ticketRepository.clearCategory(orgId, id);
        taskRepository.clearCategory(orgId, id);
        category.setDeletedAt(Instant.now());
        categoryRepository.save(category);
    }

    /** Live ticket count per category id. */
    public Map<UUID, Long> ticketCounts() {
        return toMap(ticketRepository.countByCategory(TenantContext.getCurrentOrganizationId()));
    }

    /** Live task count per category id. */
    public Map<UUID, Long> taskCounts() {
        return toMap(taskRepository.countByCategory(TenantContext.getCurrentOrganizationId()));
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private void apply(Category category, CategoryRequest request, UUID selfId) {
        String name = request.getName().trim();
        categoryRepository.findByOrganizationIdAndNameIgnoreCase(TenantContext.getCurrentOrganizationId(), name)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new IllegalStateException("A category named \"" + existing.getName() + "\" already exists");
                });
        category.setName(name);
        category.setColor(request.getColor());
    }
}
