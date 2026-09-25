package com.bento.crm.whatsapp.provider;

import com.bento.crm.whatsapp.model.WaAccount;

import java.util.List;

/**
 * Transport for outbound WhatsApp messages.
 *
 * <p>Two implementations exist: {@link MockWhatsAppProvider}, which simulates the
 * whole lifecycle so campaigns, relances and reply handling can be exercised
 * before any Meta account exists, and {@link MetaCloudWhatsAppProvider}, which
 * calls the Graph API. The one used is chosen per tenant by
 * {@link WaAccount#getProvider()}, so a single deployment can run some
 * organizations on live Meta and others on the mock.
 */
public interface WhatsAppProvider {

    WaAccount.Provider kind();

    /**
     * Sends a pre-approved template. This is the only thing Meta accepts outside the
     * 24-hour customer service window, so campaign sends and relances always take
     * this path.
     */
    SendResult sendTemplate(WaAccount account,
                            String toPhoneE164,
                            String templateName,
                            String languageCode,
                            List<String> params);

    /**
     * Sends free-form text. Only valid while the recipient's 24-hour window is open;
     * outside it Meta rejects the call with error 131047.
     */
    SendResult sendText(WaAccount account, String toPhoneE164, String body);

    /**
     * Sends free-form text under an id the CRM chose. Providers that cannot accept a
     * caller-chosen id ignore it and return their own (see {@link #assignsMessageIds()}).
     *
     * @param clientMessageId the wamid to send under; resending with the same id must not
     *                        deliver twice
     */
    default SendResult sendText(WaAccount account, String toPhoneE164, String body, String clientMessageId) {
        return sendText(account, toPhoneE164, body);
    }

    /**
     * Whether sends must be spaced out and capped by the outbox. True for a personal number
     * linked through Baileys, where WhatsApp bans numbers that behave like bulk senders; Meta's
     * Cloud API does its own rate limiting.
     */
    default boolean paced() {
        return false;
    }

    /**
     * Whether the CRM assigns the wamid before sending. When it does, receipts that arrive before
     * the send call returns can still be matched, and a send whose outcome is unknown (the JVM
     * died mid-call) can be retried under the same id without delivering twice.
     */
    default boolean assignsMessageIds() {
        return false;
    }

    /**
     * Outcome of a send attempt.
     *
     * @param wamid      Meta's message id, used as the idempotency key downstream
     * @param errorCode  Meta's numeric error code as a string, when {@code !success}
     * @param retryable  whether a later retry could plausibly succeed (rate limits,
     *                   transport failures) as opposed to permanent rejections
     *                   (unknown template, number not on WhatsApp)
     */
    record SendResult(boolean success,
                      String wamid,
                      String errorCode,
                      String errorTitle,
                      boolean retryable) {

        public static SendResult ok(String wamid) {
            return new SendResult(true, wamid, null, null, false);
        }

        public static SendResult permanentFailure(String code, String title) {
            return new SendResult(false, null, code, title, false);
        }

        public static SendResult retryableFailure(String code, String title) {
            return new SendResult(false, null, code, title, true);
        }
    }
}
