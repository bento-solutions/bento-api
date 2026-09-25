package com.bento.crm.whatsapp.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the Baileys bot is and the two secrets shared with it: the API key the CRM presents when
 * calling the bot, and the HMAC key the bot signs its webhook calls with.
 */
@Component
@Getter
public class BaileysProperties {

    /** e.g. http://whatsapp-bot:3000 on the internal Docker network. Blank disables Baileys. */
    @Value("${whatsapp.baileys.base-url:}")
    private String baseUrl;

    @Value("${whatsapp.baileys.api-key:}")
    private String apiKey;

    @Value("${whatsapp.baileys.webhook-secret:}")
    private String webhookSecret;

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && apiKey.length() >= 32;
    }
}
