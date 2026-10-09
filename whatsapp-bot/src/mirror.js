import { Spool } from './spool.js';
import { WebhookDeliverer } from './webhook.js';

/**
 * Copies the events of some sessions to a second receiver. The copy has its own spool and its own
 * deliverer, so each receiver acknowledges, retries and falls behind on its own: an outage of one
 * never delays or loses the other's events. The main spool is untouched.
 */
export function startMirror({ spool, file, url, secret, sessionIds, logger, batchSize }) {
    const sessions = new Set(sessionIds);
    const copy = new Spool(file);
    spool.onEnqueue = (sessionId, type, data, now) => {
        if (sessions.has(sessionId)) copy.enqueue(sessionId, type, data, now);
    };
    const deliverer = new WebhookDeliverer({ url, secret, spool: copy, logger, batchSize });
    return { spool: copy, deliverer };
}
