package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One automatic reminder for one invoice at one offset. The unique key on
 * {@code (invoice_id, offset_days)} is what stops the same reminder going out twice.
 */
@Entity
@Table(name = "invoice_reminder_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceReminderLog extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID invoiceId;

    @Column(nullable = false)
    private Integer offsetDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceReminderPolicy.Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private String recipient;

    public enum Outcome {
        SENT, SKIPPED_NO_EMAIL
    }
}
