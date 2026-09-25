import {
    areJidsSameUser,
    isJidBroadcast,
    isJidGroup,
    isJidNewsletter,
    isJidStatusBroadcast,
    isLidUser,
    isPnUser,
    jidDecode,
    jidNormalizedUser,
} from '@whiskeysockets/baileys';

export { isLidUser, isPnUser, jidNormalizedUser, areJidsSameUser };

/**
 * Chats the CRM never sees: groups, broadcast lists, status updates and newsletters. Passed to
 * Baileys as shouldIgnoreJid, so their messages are not even decrypted.
 */
export function isIgnoredJid(jid) {
    if (!jid) return true;
    return Boolean(isJidGroup(jid) || isJidBroadcast(jid) || isJidStatusBroadcast(jid) || isJidNewsletter(jid))
        || jid.endsWith('@bot') || jid.endsWith('@call');
}

/**
 * A phone-number JID (with or without a device suffix) as E.164. Returns null for anything that
 * is not a plausible international number.
 *
 * Deliberately no country-code guessing: WhatsApp JIDs are always full international numbers,
 * and prefixing a default country (as CRM phone normalization does) would corrupt them.
 */
export function pnJidToE164(jid) {
    if (!jid || !isPnUser(jid)) return null;
    const user = jidDecode(jid)?.user;
    if (!user || !/^\d{8,15}$/.test(user)) return null;
    return '+' + user;
}

/** "+212 612-345678" or "212612345678" → "212612345678@s.whatsapp.net". */
export function e164ToPnJid(e164) {
    const digits = String(e164 ?? '').replace(/\D/g, '');
    if (!/^\d{8,15}$/.test(digits)) return null;
    return `${digits}@s.whatsapp.net`;
}
