package com.bento.crm.whatsapp.ingest;

import com.bento.crm.whatsapp.model.WaMessage;

import java.time.Instant;

/**
 * A message the CRM learned about from a provider, normalized so Meta's webhook and the Baileys
 * bot feed the same ingest path.
 *
 * <p>Despite the name this is not always from the contact: with a linked personal number, a
 * message the owner typed on their own phone arrives the same way, with {@code direction = OUT}.
 *
 * @param wamid       WhatsApp's message id; the per-organization idempotency key
 * @param direction   IN from the contact, OUT from the account owner's own device
 * @param origin      how the provider saw it, which decides what side effects it may trigger
 * @param phoneE164   the contact's number (never the account's own)
 * @param jid         Baileys phone-number JID of the contact, when known
 * @param lid         Baileys LID of the contact, when known
 * @param pushName    the contact's WhatsApp profile name, when the provider reports it
 * @param messageType text, image, audio, … as the provider names it
 * @param body        displayable text, or a placeholder such as {@code [image]}
 * @param occurredAt  when it happened on WhatsApp
 */
public record InboundMessage(
        String wamid,
        WaMessage.Direction direction,
        Origin origin,
        String phoneE164,
        String jid,
        String lid,
        String pushName,
        String messageType,
        String body,
        String mediaType,
        String mimeType,
        String fileName,
        String quotedWamid,
        Instant occurredAt) {

    public enum Origin {
        /** Delivered in real time. */
        LIVE,
        /** Arrived while the session was offline and was delivered on reconnect. */
        OFFLINE,
        /**
         * Replayed by WhatsApp's history sync. Stored for context only: it never creates a lead,
         * notifies anyone, counts as unread or touches campaigns.
         */
        HISTORY
    }

    public boolean isHistory() {
        return origin == Origin.HISTORY;
    }

    /** A text message from the contact, for the Meta webhook and tests. */
    public static InboundMessage text(String wamid, String phoneE164, String body, Instant occurredAt) {
        return new InboundMessage(wamid, WaMessage.Direction.IN, Origin.LIVE, phoneE164, null, null, null,
                "text", body, null, null, null, null, occurredAt);
    }
}
