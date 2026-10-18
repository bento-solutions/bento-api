package com.bento.crm.checklist.controller;

import com.bento.crm.checklist.dto.ChecklistDtos;
import com.bento.crm.checklist.model.DocumentRequest;
import com.bento.crm.checklist.service.ChecklistReminderService;
import com.bento.crm.checklist.service.ChecklistService;
import com.bento.crm.checklist.service.ChecklistTemplates;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Checklists of documents asked from clients, with reminders. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Document checklists")
public class ChecklistController {

    private static final String READ = "hasAuthority('PARTNERS_READ')";
    private static final String WRITE = "hasAuthority('PARTNERS_WRITE')";

    private final ChecklistService service;
    private final ChecklistReminderService reminders;

    @GetMapping("/document-requests/templates")
    @PreAuthorize(READ)
    public List<ChecklistDtos.Template> templates() {
        return ChecklistTemplates.ALL;
    }

    @GetMapping("/document-requests")
    @PreAuthorize(READ)
    public List<ChecklistDtos.Summary> list(@RequestParam(required = false) UUID partnerId,
                                            @RequestParam(required = false) DocumentRequest.Status status) {
        return service.list(partnerId, status);
    }

    @GetMapping("/document-requests/{id}")
    @PreAuthorize(READ)
    public ChecklistDtos.Detail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/document-requests")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistDtos.Detail create(@RequestBody ChecklistDtos.CreateRequest r) {
        return service.create(r);
    }

    @PutMapping("/document-requests/{id}")
    @PreAuthorize(WRITE)
    public ChecklistDtos.Detail update(@PathVariable UUID id, @RequestBody ChecklistDtos.UpdateRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/document-requests/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/document-requests/{id}/remind")
    @PreAuthorize(WRITE)
    public ChecklistDtos.RemindResult remind(@PathVariable UUID id) {
        return reminders.remindNow(id);
    }

    @PostMapping("/document-requests/{id}/items")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistDtos.Detail addItem(@PathVariable UUID id, @RequestBody ChecklistDtos.ItemRequest r) {
        return service.addItem(id, r);
    }

    @PutMapping("/document-request-items/{id}")
    @PreAuthorize(WRITE)
    public ChecklistDtos.Detail updateItem(@PathVariable UUID id, @RequestBody ChecklistDtos.ItemRequest r) {
        return service.updateItem(id, r);
    }

    @DeleteMapping("/document-request-items/{id}")
    @PreAuthorize(WRITE)
    public ChecklistDtos.Detail deleteItem(@PathVariable UUID id) {
        return service.deleteItem(id);
    }

    @PostMapping("/document-request-items/{id}/review")
    @PreAuthorize(WRITE)
    public ChecklistDtos.Detail review(@PathVariable UUID id, @RequestBody ChecklistDtos.Review r) {
        return service.review(id, r);
    }

    @PostMapping(value = "/document-request-items/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE + " and hasAuthority('FILES_WRITE')")
    public ChecklistDtos.Detail upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return service.staffUpload(id, file);
    }
}
