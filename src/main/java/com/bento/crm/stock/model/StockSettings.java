package com.bento.crm.stock.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "stock_settings")
@Getter
@Setter
public class StockSettings extends BaseTenantEntity {

    /** When stock leaves the default warehouse without anyone recording it by hand. */
    public enum IssueTrigger {
        /** When a deal is confirmed: suits businesses that reserve goods at order time. */
        DEAL_WON,
        /** When the customer invoice is sent: suits shops and distributors. */
        INVOICE_SENT,
        /** Never automatically: issues are recorded by hand. */
        MANUAL
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssueTrigger issueTrigger = IssueTrigger.INVOICE_SENT;

    /** Whether a delivered purchase order adds its goods to the default warehouse. */
    @Column(nullable = false)
    private Boolean receiveOnDelivery = true;

    /**
     * The default for a new organization, from the industry picked at signup: logistics and
     * distribution reserve stock when the order is confirmed, service businesses rarely hold
     * stock, and everyone else ships when they invoice.
     */
    public static IssueTrigger defaultFor(String industry) {
        if (industry == null) return IssueTrigger.INVOICE_SENT;
        return switch (industry.trim().toLowerCase()) {
            case "logistics" -> IssueTrigger.DEAL_WON;
            case "consulting", "finance", "education", "healthcare" -> IssueTrigger.MANUAL;
            default -> IssueTrigger.INVOICE_SENT;
        };
    }
}
