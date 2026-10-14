package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * When an organization's unpaid customer invoices are chased automatically. One row per
 * organization; until it exists, or while {@code enabled} is false, nothing is sent.
 */
@Entity
@Table(name = "invoice_reminder_policy")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceReminderPolicy extends BaseTenantEntity {

    public static final List<Integer> DEFAULT_OFFSETS = List.of(-3, 1, 7, 15);

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    /** Days relative to the due date: -3 is three days before it, 7 a week after. Sorted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<Integer> offsetsDays = new ArrayList<>(DEFAULT_OFFSETS);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Channel channel = Channel.EMAIL;

    /** Replaces the default reminder wording when set. */
    @Column(columnDefinition = "text")
    private String message;

    public enum Channel {
        EMAIL
    }
}
