import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/http.js';
import { SendError } from '../src/session.js';

const API_KEY = 'a'.repeat(64);
const SESSION = '0b6f8a2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b';

function server(session) {
    const manager = {
        list: () => [], find: id => (id === SESSION ? session : null), get: () => session,
    };
    const app = createApp({
        config: { apiKey: API_KEY },
        manager,
        spool: { stats: () => ({ events: 0 }) },
        deliverer: {},
    });
    return new Promise(resolve => {
        const s = app.listen(0, () => resolve({ s, url: `http://127.0.0.1:${s.address().port}` }));
    });
}

const auth = { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };

test('rejects missing credentials and malformed session ids', async () => {
    const { s, url } = await server({});
    try {
        assert.equal((await fetch(`${url}/sessions`)).status, 401);
        assert.equal((await fetch(`${url}/healthz`)).status, 200);
        assert.equal((await fetch(`${url}/sessions/..%2F..%2Fetc`, { headers: auth })).status, 400);
    } finally {
        s.close();
    }
});

test('send maps session errors to retryable/permanent codes', async () => {
    let next;
    const { s, url } = await server({ sendText: async () => next() });
    const send = () => fetch(`${url}/sessions/${SESSION}/messages`, {
        method: 'POST', headers: auth, body: JSON.stringify({ messageId: '3EB0ABCDEF1234567890AB', to: '+212600000000', text: 'hi' }),
    });
    try {
        next = () => { throw new SendError('NOT_ON_WHATSAPP', 'no', { retryable: false, status: 422 }); };
        let res = await send();
        assert.equal(res.status, 422);
        assert.deepEqual(await res.json(), { code: 'NOT_ON_WHATSAPP', message: 'no', retryable: false });

        next = () => { throw new SendError('RATE_GUARD', 'slow down', { retryable: true, status: 429 }); };
        assert.equal((await send()).status, 429);

        next = async () => ({ wamid: '3EB0ABCDEF1234567890AB', jid: '212600000000@s.whatsapp.net', timestamp: 't' });
        res = await send();
        assert.equal(res.status, 200);
        assert.equal((await res.json()).wamid, '3EB0ABCDEF1234567890AB');
    } finally {
        s.close();
    }
});
