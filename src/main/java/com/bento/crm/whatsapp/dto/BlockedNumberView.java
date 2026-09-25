package com.bento.crm.whatsapp.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockedNumberView(UUID id, String phone, String reason, Instant createdAt) {
}
