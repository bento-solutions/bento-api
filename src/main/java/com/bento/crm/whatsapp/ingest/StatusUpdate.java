package com.bento.crm.whatsapp.ingest;

import com.bento.crm.whatsapp.model.WaMessage;

import java.time.Instant;

/**
 * A provider receipt for an outbound message, normalized across Meta and Baileys.
 *
 * @param status SENT, DELIVERED, READ or FAILED
 * @param at     when the provider says the transition happened
 */
public record StatusUpdate(String wamid, WaMessage.Status status, String errorCode, String errorTitle, Instant at) {
}
