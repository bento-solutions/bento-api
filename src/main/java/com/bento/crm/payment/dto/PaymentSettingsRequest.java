package com.bento.crm.payment.dto;

import com.bento.crm.payment.model.PaymentSettings;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PaymentSettingsRequest {
    @NotNull
    private PaymentSettings.Provider provider;
    private Boolean includeInEmails;
}
