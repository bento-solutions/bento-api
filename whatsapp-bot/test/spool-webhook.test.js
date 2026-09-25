import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { Spool } from '../src/spool.js';
import { WebhookDeliverer, sign } from '../src/webhook.js';
import { RateGuard } from '../src/rate-guard.js';
import { bearerMatches } from '../src/http.js';

const quietLogger = { warn() {}, error() {}, info() {} };

test('signature is HMAC-SHA256 over "<timestamp>.<body>"', () => {
    const expected = crypto.createHmac('sha256', 's3cret').update('1700000000.{"a":1}').digest('hex');
    assert.equal(sign('s3cret', 1700000000, '{"a":1}'), 'sha256=' + expected);
});

test('processed events are removed, failed ones retried with backoff', async () => {
    const spool = new Spool(':memory:');
    const a = spool.enqueue('s1', 'message.upsert', { wamid: 'A' });
    const b = spool.enqueue('s1', 'message.upsert', { wamid: 'B' });
    let sent;
    const deliverer = new WebhookDeliverer({
        url: 'http://crm/webhook', secret: 'x'.repeat(32), spool, logger: quietLogger,
        fetchImpl: async (url, init) => {
            sent = init;
            return new Response(JSON.stringify({ processed: [a], failed: [{ id: b, error: 'boom' }] }), { status: 200 });
        },
    });
    await deliverer.deliverOnce();

    const body = JSON.parse(sent.body);
    assert.deepEqual(body.events.map(e => e.data.wamid), ['A', 'B']);
    assert.equal(sent.headers['X-Bento-Signature'], sign('x'.repeat(32), sent.headers['X-Bento-Timestamp'], sent.body));
    assert.deepEqual(spool.due(10).map(e => e.id), [], 'B is backing off');
    assert.deepEqual(spool.due(10, Date.now() + 5000).map(e => e.data.wamid), ['B']);
});

test('a failed request keeps the whole batch for retry', async () => {
    const spool = new Spool(':memory:');
    spool.enqueue('s1', 'message.status', { wamid: 'A', status: 'READ' });
    const deliverer = new WebhookDeliverer({
        url: 'http://crm/webhook', secret: 'x'.repeat(32), spool, logger: quietLogger,
        fetchImpl: async () => { throw new Error('ECONNREFUSED'); },
    });
    await deliverer.deliverOnce();
    assert.equal(spool.stats().events, 1);
    assert.equal(deliverer.lastError, 'ECONNREFUSED');
});

test('session sequence numbers survive across calls', () => {
    const spool = new Spool(':memory:');
    assert.deepEqual([spool.nextSeq('s1'), spool.nextSeq('s1'), spool.nextSeq('s2')], [1, 2, 1]);
});

test('own sends are recognised before the send returns, and results are idempotent', () => {
    const spool = new Spool(':memory:');
    spool.markSending('s1', '3EB0AAA', '212600000000@s.whatsapp.net', 'hello');
    assert.equal(spool.isOwnSend('3EB0AAA'), true, 'echo suppressed while in flight');
    spool.completeSent('3EB0AAA', { wamid: '3EB0AAA', jid: '212600000000@s.whatsapp.net', timestamp: 't' });
    assert.equal(spool.getSent('3EB0AAA').result.wamid, '3EB0AAA');
    spool.markSending('s1', '3EB0BBB', 'j', 'x');
    spool.forgetUnsent('3EB0BBB');
    assert.equal(spool.isOwnSend('3EB0BBB'), false, 'a send that never reached WhatsApp can be retried');
});

test('held LID messages are released when resolved and dead-lettered when they never are', () => {
    const spool = new Spool(':memory:');
    spool.hold('s1', '99@lid', { wamid: 'L1' }, 1000);
    spool.hold('s1', '98@lid', { wamid: 'L2' }, Date.now());
    const [row] = spool.held('s1', '99@lid');
    assert.equal(row.data.wamid, 'L1');
    assert.equal(spool.expireHeld(Date.now() - 3_600_000), 1);
    assert.deepEqual(spool.held('s1').map(r => r.data.wamid), ['L2']);
    assert.equal(spool.stats().deadLetter, 1);
});

test('rate guard caps per minute and per hour', () => {
    const guard = new RateGuard({ perMinute: 2, perHour: 3 });
    const t = 1_000_000;
    assert.equal(guard.tryAcquire('s', t).ok, true);
    assert.equal(guard.tryAcquire('s', t + 1).ok, true);
    const blocked = guard.tryAcquire('s', t + 2);
    assert.equal(blocked.ok, false);
    assert.ok(blocked.retryAfterMs > 59_000);
    assert.equal(guard.tryAcquire('s', t + 61_000).ok, true);
    assert.equal(guard.tryAcquire('s', t + 122_000).ok, false, 'hourly cap of 3');
    assert.equal(guard.tryAcquire('other', t).ok, true, 'per session');
});

test('bearer comparison', () => {
    const key = 'k'.repeat(64);
    assert.equal(bearerMatches(`Bearer ${key}`, key), true);
    assert.equal(bearerMatches(`Bearer ${key}x`, key), false);
    assert.equal(bearerMatches(undefined, key), false);
    assert.equal(bearerMatches('Bearer ', key), false);
});
