/**
 * Pure helpers behind the bot's anti-spam behaviour: how long to "read" and "type" before a send,
 * and WhatsApp's own restriction and quota signals turned into plain values the CRM can store.
 *
 * WhatsApp exposes two signals to linked devices (Baileys 7):
 * - the reachout timelock: while active, the number may not start new chats (existing chats still
 *   work), until `timeEnforcementEnds`. Retrying a refused first message counts as another reach-out
 *   and lengthens the restriction.
 * - the new-chat message cap: a quota of first messages per cycle, with a status that escalates
 *   NONE → FIRST_WARNING → SECOND_WARNING → CAPPED as the number gets closer to being restricted.
 */

/** Roughly how long a person takes to type `text`: ~45 ms a character after a short start, 1.5–7 s, ±20%. */
export function typingDelayMs(text, random = Math.random) {
    const length = typeof text === 'string' ? text.length : 0;
    const base = Math.min(7000, Math.max(1500, 1200 + length * 45));
    return Math.round(base * (0.8 + random() * 0.4));
}

/** A beat between opening a chat (reading what the contact wrote) and starting to type. */
export function readingPauseMs(random = Math.random) {
    return Math.round(800 + random() * 1700);
}

function iso(date) {
    return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
}

/** WhatsApp timestamps arrive as unix seconds in strings; tolerate milliseconds and junk. */
function fromUnix(value) {
    const n = Number.parseInt(value ?? '', 10);
    if (!Number.isFinite(n) || n <= 0) return null;
    return new Date(n > 1e12 ? n : n * 1000);
}

/** The reachout timelock as `{active, until, type}` (until/type only while active). */
export function reachoutState(lock) {
    if (!lock?.isActive) return { active: false, until: null, type: null };
    const ends = lock.timeEnforcementEnds instanceof Date ? lock.timeEnforcementEnds : fromUnix(lock.timeEnforcementEnds);
    return { active: true, until: iso(ends), type: lock.enforcementType ?? null };
}

/** The new-chat message cap as `{totalQuota, usedQuota, status, cycleEndsAt}`, or null if unusable. */
export function cappingState(info) {
    if (!info || typeof info !== 'object') return null;
    const num = value => (value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
        ? null : Number(value));
    return {
        totalQuota: num(info.total_quota),
        usedQuota: num(info.used_quota),
        status: typeof info.capping_status === 'string' ? info.capping_status : null,
        cycleEndsAt: iso(fromUnix(info.cycle_end_timestamp)),
    };
}

/** Whether WhatsApp's quota still allows opening a chat now; `until` is when the cycle resets. */
export function newChatBlock(capping, now = Date.now()) {
    if (!capping) return { blocked: false, until: null };
    const cycleEnds = capping.cycleEndsAt ? Date.parse(capping.cycleEndsAt) : null;
    if (cycleEnds !== null && cycleEnds <= now) return { blocked: false, until: null };
    const exhausted = capping.totalQuota !== null && capping.usedQuota !== null && capping.usedQuota >= capping.totalQuota;
    return capping.status === 'CAPPED' || exhausted
        ? { blocked: true, until: capping.cycleEndsAt }
        : { blocked: false, until: null };
}
