package com.bento.crm.whatsapp.provider;

import com.bento.crm.whatsapp.model.WaAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

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
        if (clientMessageId == null) {
            return sendText(account, toPhoneE164, body);
        }
        try {
            BaileysBotClient.SendResponse response = bot.sendText(account.getId(), clientMessageId, toPhoneE164, body);
            return SendResult.ok(response.wamid() != null ? response.wamid() : clientMessageId);
        } catch (BaileysBotClient.BotException e) {
            log.warn("[baileys] send {} failed: {} {} (retryable={})",
                    clientMessageId, e.code(), e.getMessage(), e.retryable());
            return e.retryable()
                    ? SendResult.retryableFailure(e.code(), e.getMessage())
                    : SendResult.permanentFailure(e.code(), e.getMessage());
        }
    }
}
