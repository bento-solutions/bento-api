package com.bento.crm.invoice.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** The daily "supplier bills due soon or overdue" alert an organization received. Unique per day. */
@Entity
@Table(name = "bill_alert_digest")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillAlertDigest extends BaseTenantEntity {

    @Column(nullable = false)
    private LocalDate alertDate;

    @Column(nullable = false)
    private Integer dueSoonCount;

    @Column(nullable = false)
    private Integer overdueCount;
}
