package com.bento.crm.checklist.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The documents a client must send for a period, with reminders until they arrive. */
@Entity
@Table(name = "document_request")
@Getter
@Setter
public class DocumentRequest extends BaseTenantEntity {

    @Column(name = "partner_id", nullable = false, columnDefinition = "uuid")
    private UUID partnerId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 20)
    private String period;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "remind_channel", nullable = false, length = 10)
    private Channel remindChannel = Channel.EMAIL;

    @Column(name = "remind_every_days", nullable = false)
    private int remindEveryDays = 3;

    @Column(name = "last_reminder_at")
    private Instant lastReminderAt;

    @Column(name = "reminder_count", nullable = false)
    private int reminderCount;

    @Column(length = 1000)
    private String note;

    public enum Status { OPEN, COMPLETE, CANCELLED }

    public enum Channel { NONE, EMAIL, WHATSAPP, BOTH }
}
