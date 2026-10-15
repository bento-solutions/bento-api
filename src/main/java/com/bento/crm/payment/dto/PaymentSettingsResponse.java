package com.bento.crm.payment.dto;

import com.bento.crm.payment.model.PaymentSettings;

import java.util.List;

/**
 * @param available providers this server can run (Stripe needs its keys; the mock can be switched off)
 */
public record PaymentSettingsResponse(PaymentSettings.Provider provider, boolean includeInEmails,
                                      List<PaymentSettings.Provider> available) {}
