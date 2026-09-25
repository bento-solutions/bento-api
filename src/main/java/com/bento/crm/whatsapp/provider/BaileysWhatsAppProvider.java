package com.bento.crm.whatsapp.provider;

import com.bento.crm.whatsapp.model.WaAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A personal number linked as a device through the Baileys bot.
 *
 * <p>Paced (WhatsApp bans personal numbers that behave like bulk senders) and CRM-assigned ids:
 * the outbox picks the wamid before sending, so receipts that race the send's return still match
 * and a send with an unknown outcome is retried under the same id — the bot answers a repeated
 * id with the stored result instead of sending twice.
 *
 * <p>No templates: a personal number sends plain text, and WhatsApp's 24-hour rule does not apply.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BaileysWhatsAppProvider implements WhatsAppProvider {

    /** Bot answers that hold a send back rather than fail it: WhatsApp's restriction or quota, the bot's own limits. */
    static final Set<String> DEFERRALS = Set.of("REACHOUT_LOCKED", "NEW_CHAT_CAP_REACHED", "NEW_CHAT_GUARD", "RATE_GUARD");
    private static final Duration DEFAULT_DEFERRAL = Duration.ofMinutes(15);

    private final BaileysBotClient bot;

    @Override
    public WaAccount.Provider kind() {
        return WaAccount.Provider.BAILEYS;
    }

    @Override
    public boolean paced() {
        return true;
    }

    @Override
    public boolean assignsMessageIds() {
        return true;
    }

    @Override
    public SendResult sendTemplate(WaAccount account, String toPhoneE164, String templateName,
                                   String languageCode, List<String> params) {
        return SendResult.permanentFailure("TEMPLATE_UNSUPPORTED",
                "A linked personal number sends plain text; templates are a Meta Cloud API feature");
    }

    @Override
    public SendResult sendText(WaAccount account, String toPhoneE164, String body) {
        return SendResult.permanentFailure("MESSAGE_ID_REQUIRED",
                "Baileys sends go through the outbox, which assigns the message id");
    }

    @Override
    public SendResult sendText(WaAccount account, String toPhoneE164, String body, String clientMessageId) {
        return sendText(account, toPhoneE164, body, clientMessageId, SendHints.NONE);
    }

    @Override
    public SendResult sendText(WaAccount account, String toPhoneE164, String body, String clientMessageId,
                               SendHints hints) {
        if (clientMessageId == null) {
            return sendText(account, toPhoneE164, body);
        }
        try {
            BaileysBotClient.SendResponse response = bot.sendText(account.getId(), clientMessageId, toPhoneE164, body,
                    hints.newChat(), hints.readUpToWamid());
            return SendResult.ok(response.wamid() != null ? response.wamid() : clientMessageId);
        } catch (BaileysBotClient.BotException e) {
            log.warn("[baileys] send {} failed: {} {} (retryable={})",
                    clientMessageId, e.code(), e.getMessage(), e.retryable());
            if (DEFERRALS.contains(e.code())) {
                // WhatsApp's restriction or a limit, not a problem with this message: it waits.
                return SendResult.deferred(e.code(), e.getMessage(),
                        e.retryAt() != null ? e.retryAt() : Instant.now().plus(DEFAULT_DEFERRAL));
            }
            return e.retryable()
                    ? SendResult.retryableFailure(e.code(), e.getMessage())
                    : SendResult.permanentFailure(e.code(), e.getMessage());
        }
    }
}
