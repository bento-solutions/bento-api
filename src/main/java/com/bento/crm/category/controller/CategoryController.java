package com.bento.crm.category.controller;

import com.bento.crm.category.dto.CategoryRequest;
import com.bento.crm.category.dto.CategoryResponse;
import com.bento.crm.category.model.Category;
import com.bento.crm.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Single-word coloured labels (CRMbento, Orthoflow…) marking which product a ticket or task belongs to")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('TICKETS_READ','TASKS_READ')")
    @Operation(summary = "List categories", description = "Every category of the organization, with how many tickets and tasks carry it")
    public ResponseEntity<List<CategoryResponse>> list() {
        Map<UUID, Long> tickets = categoryService.ticketCounts();
        Map<UUID, Long> tasks = categoryService.taskCounts();
        return ResponseEntity.ok(categoryService.list().stream()
                .map(c -> CategoryResponse.fromEntity(c, tickets.getOrDefault(c.getId(), 0L), tasks.getOrDefault(c.getId(), 0L)))
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('TICKETS_WRITE','TASKS_WRITE')")
    @Operation(summary = "Create category", description = "409 when a category with the same name (any case) already exists")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        Category created = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.fromEntity(created, 0, 0));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TICKETS_WRITE','TASKS_WRITE')")
    @Operation(summary = "Update category", description = "Rename or recolour; every ticket and task using it follows")
    public ResponseEntity<CategoryResponse> update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        Category updated = categoryService.update(id, request);
        return ResponseEntity.ok(CategoryResponse.fromEntity(updated,
                categoryService.ticketCounts().getOrDefault(id, 0L), categoryService.taskCounts().getOrDefault(id, 0L)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TICKETS_DELETE','TASKS_DELETE')")
    @Operation(summary = "Delete category", description = "Removes the category and clears it from every ticket and task that carried it")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
