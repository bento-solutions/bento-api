import crypto from 'node:crypto';
import express from 'express';
import { SendError } from './session.js';
import { SESSION_ID } from './session-manager.js';

/** Constant-time bearer check (hashing first equalises lengths for timingSafeEqual). */
export function bearerMatches(header, apiKey) {
    const presented = typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : '';
    const a = crypto.createHash('sha256').update(presented).digest();
    const b = crypto.createHash('sha256').update(apiKey).digest();
    return presented.length > 0 && crypto.timingSafeEqual(a, b);
}

/**
 * The bot's API. It is only reachable on the internal Docker network (never through Traefik) and
 * every route but /healthz requires the shared API key.
 */
export function createApp({ config, manager, spool, deliverer }) {
    const app = express();
    app.use(express.json({ limit: '64kb' }));

    app.get('/healthz', (req, res) => {
        res.json({ ok: true, sessions: manager.list().length, spool: spool.stats(), webhook: {
            lastSuccessAt: deliverer.lastSuccessAt, lastError: deliverer.lastError,
        } });
    });

    app.use((req, res, next) => {
        if (!bearerMatches(req.headers.authorization, config.apiKey)) {
            return res.status(401).json({ code: 'UNAUTHORIZED' });
        }
        next();
    });

    app.param('id', (req, res, next, id) => {
        if (!SESSION_ID.test(id)) return res.status(400).json({ code: 'INVALID_SESSION_ID' });
        next();
    });

    app.get('/sessions', (req, res) => res.json(manager.list()));

    app.get('/sessions/:id', (req, res) => {
        const session = manager.find(req.params.id);
        res.json(session ? session.status() : { id: req.params.id, state: 'stopped', linked: false });
    });

    app.post('/sessions/:id/start', async (req, res) => {
        try {
            res.json(await manager.get(req.params.id).start({ phoneNumber: req.body?.phoneNumber }));
        } catch (err) {
            res.status(409).json({ code: 'START_FAILED', message: err.message });
        }
    });

    app.post('/sessions/:id/stop', async (req, res) => {
        const session = manager.find(req.params.id);
        res.json(session ? await session.stop() : { id: req.params.id, state: 'stopped' });
    });

    app.post('/sessions/:id/logout', async (req, res) => {
        res.json(await manager.get(req.params.id).logout());
    });

    app.post('/sessions/:id/messages', async (req, res) => {
        const { messageId, to, jid, text } = req.body ?? {};
        if (typeof messageId !== 'string' || !/^[0-9A-Za-z]{8,64}$/.test(messageId)) {
            return res.status(400).json({ code: 'INVALID_MESSAGE_ID', retryable: false });
        }
        if (typeof text !== 'string' || text.trim() === '' || text.length > 4096) {
            return res.status(400).json({ code: 'INVALID_TEXT', retryable: false });
        }
        const session = manager.find(req.params.id);
        if (!session) {
            return res.status(409).json({ code: 'SESSION_NOT_OPEN', message: 'session not started', retryable: true });
        }
        try {
            res.json(await session.sendText({ messageId, to, jid, text }));
        } catch (err) {
            if (err instanceof SendError) {
                return res.status(err.status).json({ code: err.code, message: err.message, retryable: err.retryable });
            }
            res.status(500).json({ code: 'SEND_FAILED', message: err.message, retryable: true });
        }
    });

    return app;
}
