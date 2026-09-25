package com.bento.crm.whatsapp.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A number the organization chose to ignore ("Ignore number" in the inbox). Messages from or to
 * it are dropped at ingest and never create a lead; ignoring also purges what was already stored.
 */
@Entity
@Table(name = "wa_blocked_number")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaBlockedNumber extends BaseTenantEntity {

    @Column(name = "phone_e164", nullable = false, length = 32)
    private String phoneE164;

    private String reason;
}
