import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Spool } from '../src/spool.js';
import { startMirror } from '../src/mirror.js';
import { loadConfig } from '../src/config.js';

const quietLogger = { warn() {}, error() {}, info() {} };
const secret = 'm'.repeat(32);

test('only the named sessions are copied, and the copy is acknowledged on its own', async () => {
    const spool = new Spool(':memory:');
    let received;
    const mirror = startMirror({
        spool, file: ':memory:', url: 'http://orthoflow/webhook', secret, sessionIds: ['shared'], logger: quietLogger, batchSize: 50,
    });
    mirror.deliverer.fetchImpl = async (url, init) => {
        received = JSON.parse(init.body);
        return new Response(JSON.stringify({ failed: [] }), { status: 200 });
    };

    spool.enqueue('shared', 'message.upsert', { wamid: 'A' });
    spool.enqueue('crm-only', 'message.upsert', { wamid: 'B' });
    await mirror.deliverer.deliverOnce();

    assert.deepEqual(received.events.map(e => [e.sessionId, e.data.wamid]), [['shared', 'A']]);
    assert.equal(mirror.spool.due(10).length, 0, 'the mirror acknowledged its copy');
    assert.equal(spool.due(10).length, 2, 'the CRM still has both of its events');
});

test('a mirror that is down keeps its copy for retry without touching the main spool', async () => {
    const spool = new Spool(':memory:');
    const mirror = startMirror({
        spool, file: ':memory:', url: 'http://orthoflow/webhook', secret, sessionIds: ['shared'], logger: quietLogger, batchSize: 50,
    });
    mirror.deliverer.fetchImpl = async () => { throw new Error('connection refused'); };

    spool.enqueue('shared', 'message.upsert', { wamid: 'A' });
    await mirror.deliverer.deliverOnce();

    assert.equal(mirror.spool.due(10, Date.now() + 5000).length, 1);
    assert.equal(spool.due(10).length, 1);
});

test('a mirror is off unless configured, and refused when half configured', () => {
    const base = { API_KEY: 'k'.repeat(32), WEBHOOK_SECRET: 's'.repeat(32), WEBHOOK_URL: 'http://app:8080/hook' };
    assert.deepEqual(loadConfig(base).problems, []);
    assert.equal(loadConfig(base).config.mirrorWebhookUrl, '');
    const half = loadConfig({ ...base, MIRROR_WEBHOOK_URL: 'http://orthoflow:8080/api/v1/webhooks/whatsapp' });
    assert.ok(half.problems.some(p => p.includes('MIRROR_WEBHOOK_SECRET')));
    assert.ok(half.problems.some(p => p.includes('MIRROR_SESSION_IDS')));
    const full = loadConfig({ ...base, MIRROR_WEBHOOK_URL: 'http://orthoflow:8080/api/v1/webhooks/whatsapp',
        MIRROR_WEBHOOK_SECRET: secret, MIRROR_SESSION_IDS: 'a, b' });
    assert.deepEqual(full.problems, []);
    assert.deepEqual(full.config.mirrorSessionIds, ['a', 'b']);
});
