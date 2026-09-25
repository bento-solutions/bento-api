import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Spool } from '../src/spool.js';
import { RateGuard } from '../src/rate-guard.js';
import { Session, SendError } from '../src/session.js';
import { cappingState, newChatBlock, reachoutState, typingDelayMs } from '../src/safety.js';

const quietLogger = { warn() {}, error() {}, info() {}, debug() {}, child() { return quietLogger; } };

test('typing takes about as long as a person would, within 1.2–8.4 s', () => {
    assert.equal(typingDelayMs('ok', () => 0.5), 1500);
    assert.equal(typingDelayMs('x'.repeat(40), () => 0.5), 3000);
    assert.equal(typingDelayMs('x'.repeat(4000), () => 0.5), 7000);
    assert.equal(typingDelayMs('ok', () => 0), 1200);
    assert.equal(typingDelayMs('x'.repeat(4000), () => 1), 8400);
});

test("WhatsApp's restriction and quota become plain values", () => {
    assert.deepEqual(reachoutState({ isActive: false, enforcementType: 'DEFAULT' }), { active: false, until: null, type: null });
    assert.deepEqual(
        reachoutState({ isActive: true, timeEnforcementEnds: new Date('2026-09-26T09:49:00Z'), enforcementType: 'BIZ_QUALITY' }),
        { active: true, until: '2026-09-26T09:49:00.000Z', type: 'BIZ_QUALITY' });

    assert.deepEqual(
        cappingState({ total_quota: 20, used_quota: '7', capping_status: 'FIRST_WARNING', cycle_end_timestamp: '1790400000' }),
        { totalQuota: 20, usedQuota: 7, status: 'FIRST_WARNING', cycleEndsAt: new Date(1790400000 * 1000).toISOString() });
    assert.equal(cappingState({ cycle_end_timestamp: '1790400000000' }).cycleEndsAt,
        new Date(1790400000000).toISOString(), 'milliseconds are accepted too');
    assert.equal(cappingState(null), null);
});

test('a used-up or CAPPED quota blocks new chats until the cycle ends', () => {
    const now = Date.parse('2026-09-25T12:00:00Z');
    const later = '2026-09-26T00:00:00.000Z';
    assert.deepEqual(newChatBlock({ totalQuota: 10, usedQuota: 10, status: 'NONE', cycleEndsAt: later }, now),
        { blocked: true, until: later });
    assert.deepEqual(newChatBlock({ totalQuota: null, usedQuota: null, status: 'CAPPED', cycleEndsAt: later }, now),
        { blocked: true, until: later });
    assert.equal(newChatBlock({ totalQuota: 10, usedQuota: 4, status: 'SECOND_WARNING', cycleEndsAt: later }, now).blocked, false);
    assert.equal(newChatBlock({ totalQuota: 10, usedQuota: 10, status: 'CAPPED', cycleEndsAt: '2026-09-25T11:00:00.000Z' }, now).blocked,
        false, 'an expired cycle no longer blocks');
    assert.equal(newChatBlock(null, now).blocked, false);
});

test('contacts: a chat counts once toward the daily cap, aliases and known contacts never', () => {
    const spool = new Spool(':memory:');
    spool.rememberContact('s', ['212600000001@s.whatsapp.net', '111@lid'], 'out', 1000);
    spool.rememberContact('s', ['212600000002@s.whatsapp.net'], 'in', 1000);
    spool.rememberContact('s', ['212600000002@s.whatsapp.net', '222@lid'], 'out', 2000);

    assert.equal(spool.isKnownContact('s', [null, '111@lid']), true);
    assert.equal(spool.isKnownContact('s', ['212600000009@s.whatsapp.net']), false);
    assert.equal(spool.isKnownContact('other', ['212600000001@s.whatsapp.net']), false);
    assert.deepEqual(spool.chatsOpenedSince('s', 0), { n: 1, oldest: 1000 });
    assert.deepEqual(spool.chatsOpenedSince('s', 1500), { n: 0, oldest: null });
});

/** A linked, open session on a fake socket that records what it was asked to do. */
function openSession({ guardNewChatsPerDay = 2, simulateTyping = true } = {}) {
    const calls = [];
    const spool = new Spool(':memory:');
    const session = new Session({
        id: 'sess', spool, logger: quietLogger,
        guard: new RateGuard({ perMinute: 100, perHour: 1000 }),
        config: { dataDir: '/tmp/unused', guardNewChatsPerDay, simulateTyping, maxPairingAttempts: 3 },
    });
    session.state = 'open';
    session.sock = {
        onWhatsApp: async jid => { calls.push(['lookup', jid]); return [{ exists: true, jid }]; },
        readMessages: async keys => { calls.push(['read', keys[0].id]); },
        presenceSubscribe: async () => {},
        sendPresenceUpdate: async (type, jid) => { calls.push([type, jid]); },
        sendMessage: async (jid, content, { messageId }) => { calls.push(['send', jid]); return { key: { id: messageId } }; },
    };
    return { session, spool, calls };
}

let seq = 0;
const nextId = () => `3EB0${String(++seq).padStart(18, '0')}`;

test('while WhatsApp restricts new chats, first messages are refused for good but replies still go', async () => {
    const { session, spool } = openSession({ simulateTyping: false });
    spool.rememberContact('sess', ['212600000001@s.whatsapp.net'], 'in');
    session.applyReachout({ isActive: true, timeEnforcementEnds: new Date('2026-09-26T09:49:00Z'), enforcementType: 'BIZ_QUALITY' });

    await assert.rejects(session.sendText({ messageId: nextId(), to: '+212600000009', text: 'Bonjour' }), err => {
        assert.ok(err instanceof SendError);
        assert.equal(err.code, 'REACHOUT_LOCKED');
        assert.equal(err.retryable, false);
        assert.equal(err.until, '2026-09-26T09:49:00.000Z');
        return true;
    });
    const reply = await session.sendText({ messageId: nextId(), to: '+212600000001', text: 'Merci !' });
    assert.equal(reply.jid, '212600000001@s.whatsapp.net');

    const status = spool.due(10).filter(e => e.type === 'session.status').map(e => e.data);
    assert.equal(status.at(-1).reachoutLocked, true, 'the restriction is reported to the CRM');
    assert.equal(status.at(-1).reachoutType, 'BIZ_QUALITY');
});

test('the bot opens at most its daily number of new chats, whatever the CRM asks', async () => {
    const { session } = openSession({ guardNewChatsPerDay: 2, simulateTyping: false });
    await session.sendText({ messageId: nextId(), to: '+212600000011', text: 'a' });
    await session.sendText({ messageId: nextId(), to: '+212600000012', text: 'b' });
    await session.sendText({ messageId: nextId(), to: '+212600000011', text: 'again, same chat' });

    await assert.rejects(session.sendText({ messageId: nextId(), to: '+212600000013', text: 'c' }), err => {
        assert.equal(err.code, 'NEW_CHAT_GUARD');
        assert.equal(err.retryable, true);
        assert.ok(err.retryAfterMs > 23 * 3_600_000);
        return true;
    });
});

test('a reply reads the last message and types before sending', async () => {
    const { session, calls } = openSession();
    session.config.simulateTyping = true;
    const originalSetTimeout = globalThis.setTimeout;
    globalThis.setTimeout = (fn, ms, ...args) => originalSetTimeout(fn, 0, ...args);
    try {
        await session.sendText({ messageId: nextId(), to: '+212600000021', text: 'Oui', readUpTo: { id: 'ABCDEF123456' } });
    } finally {
        globalThis.setTimeout = originalSetTimeout;
    }
    assert.deepEqual(calls.map(c => c[0]), ['lookup', 'read', 'composing', 'paused', 'send']);
});

test('a CAPPED WhatsApp quota holds first messages until the cycle ends', async () => {
    const { session } = openSession({ simulateTyping: false });
    const cycleEnd = Math.floor(Date.now() / 1000) + 3600;
    session.applyCapping({ total_quota: 5, used_quota: 5, capping_status: 'CAPPED', cycle_end_timestamp: String(cycleEnd) });
    await assert.rejects(session.sendText({ messageId: nextId(), to: '+212600000031', text: 'x' }), err => {
        assert.equal(err.code, 'NEW_CHAT_CAP_REACHED');
        assert.equal(err.until, new Date(cycleEnd * 1000).toISOString());
        return true;
    });
});
