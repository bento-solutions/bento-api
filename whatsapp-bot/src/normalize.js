import { getContentType, normalizeMessageContent, toNumber } from '@whiskeysockets/baileys';

/** Message kinds that carry no conversation content (key exchange, reactions, edits, deletes). */
const SKIPPED = new Set([
    'protocolMessage',
    'senderKeyDistributionMessage',
    'messageContextInfo',
    'reactionMessage',
    'pollUpdateMessage',
    'keepInChatMessage',
    'editedMessage',
    'encReactionMessage',
    'pinInChatMessage',
]);

/**
 * What a message says, reduced to what the CRM stores: a type, displayable text, media metadata
 * and the id of a quoted message. Returns null for content that is not a conversation message.
 */
export function describeContent(message) {
    const content = normalizeMessageContent(message);
    if (!content) return null;
    const type = getContentType(content);
    if (!type || SKIPPED.has(type)) return null;
    const inner = content[type] ?? {};
    const quotedWamid = inner?.contextInfo?.stanzaId || null;
    const media = (mediaType, fallback) => ({
        type: mediaType,
        body: inner.caption || fallback,
        mediaType,
        mimeType: inner.mimetype || null,
        fileName: inner.fileName || null,
        quotedWamid,
    });

    switch (type) {
        case 'conversation':
            return { type: 'text', body: content.conversation, quotedWamid: null };
        case 'extendedTextMessage':
            return { type: 'text', body: inner.text ?? '', quotedWamid };
        case 'imageMessage':
            return media('image', '[image]');
        case 'videoMessage':
            return media('video', '[video]');
        case 'audioMessage':
            return media('audio', inner.ptt ? '[voice message]' : '[audio]');
        case 'documentMessage':
            return media('document', inner.fileName ? `[document] ${inner.fileName}` : '[document]');
        case 'stickerMessage':
            return media('sticker', '[sticker]');
        case 'locationMessage':
        case 'liveLocationMessage': {
            const where = inner.name || inner.address
                || (inner.degreesLatitude != null ? `${inner.degreesLatitude},${inner.degreesLongitude}` : '');
            return { type: 'location', body: `[location] ${where}`.trim(), quotedWamid };
        }
        case 'contactMessage':
            return { type: 'contact', body: `[contact] ${inner.displayName ?? ''}`.trim(), quotedWamid };
        case 'contactsArrayMessage':
            return { type: 'contact', body: `[contacts] ${inner.displayName ?? ''}`.trim(), quotedWamid };
        case 'buttonsResponseMessage':
            return { type: 'button', body: inner.selectedDisplayText || inner.selectedButtonId || '', quotedWamid };
        case 'templateButtonReplyMessage':
            return { type: 'button', body: inner.selectedDisplayText || inner.selectedId || '', quotedWamid };
        case 'listResponseMessage':
            return { type: 'interactive', body: inner.title || inner.singleSelectReply?.selectedRowId || '', quotedWamid };
        case 'interactiveResponseMessage':
            return { type: 'interactive', body: inner.body?.text || '', quotedWamid };
        default:
            return { type: type.replace(/Message$/, ''), body: `[${type.replace(/Message$/, '')}]`, quotedWamid };
    }
}

/**
 * The provider-neutral part of an upserted message, before the contact's phone number is known
 * (that needs the session's LID mapping, see Session#resolveContactPhone).
 *
 * @param origin 'live' | 'offline' | 'history'
 * @returns null when the message should not reach the CRM
 */
export function toInbound(msg, origin) {
    const key = msg?.key;
    if (!key?.id || !key.remoteJid) return null;
    const described = describeContent(msg.message);
    if (!described) return null;
    const ts = msg.messageTimestamp != null ? toNumber(msg.messageTimestamp) : Math.floor(Date.now() / 1000);
    return {
        wamid: key.id,
        direction: key.fromMe ? 'OUT' : 'IN',
        origin,
        chatJid: key.remoteJid,
        // For a message from the contact this is the contact's other address (PN for a LID chat,
        // LID for a PN chat). For a message the owner sent from their phone it is the owner's own.
        altJid: key.remoteJidAlt || null,
        pushName: key.fromMe ? null : (msg.pushName || null),
        messageType: described.type,
        body: described.body,
        mediaType: described.mediaType || null,
        mimeType: described.mimeType || null,
        fileName: described.fileName || null,
        quotedWamid: described.quotedWamid || null,
        occurredAt: new Date(ts * 1000).toISOString(),
    };
}

/** Baileys receipt status (proto.WebMessageInfo.Status) → CRM status. */
export function receiptStatus(status) {
    switch (status) {
        case 0: return 'FAILED';
        case 2: return 'SENT';
        case 3: return 'DELIVERED';
        case 4:
        case 5: return 'READ';
        default: return null;
    }
}
