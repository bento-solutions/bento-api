package com.bento.crm.task.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.common.model.RelatedEntityType;
import com.bento.crm.task.dto.CreateTaskRequest;
import com.bento.crm.task.dto.TaskResponse;
import com.bento.crm.task.model.Task;
import com.bento.crm.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@Tag(name = "Tasks", description = "Task management endpoints")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TASKS_CREATE')")
    @Operation(summary = "Create task", description = "Create a new task")
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request) {
        Task created = taskService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.fromEntity(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TASKS_READ')")
    @Operation(summary = "Get task by ID", description = "Retrieve task details")
    public ResponseEntity<TaskResponse> getTask(@PathVariable UUID id) {
        Task task = taskService.getTask(id);
        return ResponseEntity.ok(TaskResponse.fromEntity(task));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TASKS_READ')")
    @Operation(summary = "List tasks",
            description = "List tasks in the organization, optionally narrowed to those linked to a "
                    + "given record (e.g. every task raised for one customer or deal).")
    public ResponseEntity<PageResponse<TaskResponse>> listTasks(
            @Parameter(description = "Only tasks linked to this kind of record")
            @RequestParam(required = false) RelatedEntityType relatedEntityType,
            @Parameter(description = "Only tasks linked to this record id")
            @RequestParam(required = false) UUID relatedEntityId,
            Pageable pageable) {
        Page<Task> page = taskService.listTasks(relatedEntityType, relatedEntityId, pageable);
        Page<TaskResponse> dtoPage = page.map(TaskResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(dtoPage));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('TASKS_WRITE')")
    @Operation(summary = "Update task", description = "Update task information")
    public ResponseEntity<TaskResponse> updateTask(@PathVariable UUID id, @Valid @RequestBody CreateTaskRequest request) {
        Task task = taskService.updateTask(id, request);
        return ResponseEntity.ok(TaskResponse.fromEntity(task));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('TASKS_DELETE')")
    @Operation(summary = "Delete task", description = "Soft delete task record")
    public ResponseEntity<Void> deleteTask(@PathVariable UUID id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('TASKS_DELETE')")
    @Operation(summary = "Restore task", description = "Undo a soft delete on a task")
    public ResponseEntity<TaskResponse> restoreTask(@PathVariable UUID id) {
        return ResponseEntity.ok(TaskResponse.fromEntity(taskService.restoreTask(id)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('TASKS_DELETE')")
    @Operation(summary = "List deleted tasks", description = "Soft-deleted tasks still inside the retention window")
    public ResponseEntity<PageResponse<TaskResponse>> listDeleted(Pageable pageable) {
        Page<TaskResponse> page = taskService.listDeleted(pageable).map(TaskResponse::fromEntity);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }
}
