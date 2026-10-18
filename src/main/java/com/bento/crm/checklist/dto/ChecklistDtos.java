package com.bento.crm.checklist.dto;

import com.bento.crm.checklist.model.DocumentRequest;
import com.bento.crm.checklist.model.DocumentRequestItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ChecklistDtos {

    private ChecklistDtos() {}

    public record Summary(UUID id, UUID partnerId, String partnerName, String title, String period, LocalDate dueDate,
                          DocumentRequest.Status status, DocumentRequest.Channel remindChannel, int remindEveryDays,
                          Instant lastReminderAt, int reminderCount, int items, int missing, int toCheck, int accepted,
                          boolean overdue) {}

    public record Detail(Summary request, String note, List<Item> items) {}

    public record Item(UUID id, String label, boolean required, DocumentRequestItem.Status status, UUID fileId,
                       String fileName, Instant receivedAt, String reviewNote, int sortOrder) {}

    public record CreateRequest(UUID partnerId, String title, String period, LocalDate dueDate,
                                DocumentRequest.Channel remindChannel, Integer remindEveryDays, String note,
                                String template, List<String> items) {}

    public record UpdateRequest(String title, String period, LocalDate dueDate, DocumentRequest.Channel remindChannel,
                                Integer remindEveryDays, String note, DocumentRequest.Status status) {}

    public record ItemRequest(String label, Boolean required, Integer sortOrder) {}

    /** ACCEPTED, or REJECTED with what to send again. */
    public record Review(DocumentRequestItem.Status status, String note) {}

    public record Template(String key, String title, List<String> items) {}

    public record RemindResult(boolean email, boolean whatsapp, String problem) {}
}
