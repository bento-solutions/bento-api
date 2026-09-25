import crypto from 'node:crypto';

/** HMAC over "<timestamp>.<body>", the format BaileysWebhookController verifies. */
export function sign(secret, timestamp, body) {
    return 'sha256=' + crypto.createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex');
}

/**
 * Drains the spool to the CRM in batches. The CRM answers with the ids it processed and the ids
 * that failed; processed ones are deleted, failed ones and everything in a batch that got no
 * answer are retried with backoff. Delivery is therefore at-least-once, which the CRM absorbs by
 * deduplicating on wamid and ordering session changes by seq.
 */
export class WebhookDeliverer {
    constructor({ url, secret, spool, logger, batchSize = 50, fetchImpl = fetch, timeoutMs = 15000 }) {
        Object.assign(this, { url, secret, spool, logger, batchSize, fetchImpl, timeoutMs });
        this.timer = null;
        this.running = false;
        this.busy = false;
        this.lastSuccessAt = null;
        this.lastError = null;
    }

    start(intervalMs = 1000) {
        this.running = true;
        const tick = async () => {
            if (!this.running) return;
            try {
                while (await this.deliverOnce()) { /* keep draining while batches are full */ }
            } catch (err) {
                this.logger.error({ err }, 'webhook delivery loop failed');
            }
            this.timer = setTimeout(tick, intervalMs);
        };
        this.timer = setTimeout(tick, 0);
    }

    stop() {
        this.running = false;
        if (this.timer) clearTimeout(this.timer);
    }

    /** @returns whether a full batch was delivered (so there may be more) */
    async deliverOnce(now = Date.now()) {
        if (this.busy) return false;
        this.busy = true;
        try {
            const batch = this.spool.due(this.batchSize, now);
            if (batch.length === 0) return false;
            const body = JSON.stringify({
                events: batch.map(e => ({
                    id: e.id, sessionId: e.session_id, type: e.type, data: e.data, createdAt: e.created_at,
                })),
            });
            const timestamp = Math.floor(Date.now() / 1000);
            const ids = batch.map(e => e.id);
            let response;
            try {
                response = await this.fetchImpl(this.url, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Bento-Timestamp': String(timestamp),
                        'X-Bento-Signature': sign(this.secret, timestamp, body),
                    },
                    body,
                    signal: AbortSignal.timeout(this.timeoutMs),
                });
            } catch (err) {
                this.lastError = err.message;
                this.spool.fail(ids, `transport: ${err.message}`);
                this.logger.warn({ err: err.message, count: ids.length }, 'webhook delivery failed, will retry');
                return false;
            }
            if (!response.ok) {
                this.lastError = `HTTP ${response.status}`;
                this.spool.fail(ids, `HTTP ${response.status}`);
                this.logger.warn({ status: response.status, count: ids.length }, 'webhook rejected batch, will retry');
                return false;
            }
            let result = {};
            try {
                result = await response.json();
            } catch {
                // A 2xx without a body acknowledges everything.
            }
            const failed = new Set((result.failed ?? []).map(f => (typeof f === 'object' ? f.id : f)));
            const processed = Array.isArray(result.processed) ? result.processed : ids.filter(id => !failed.has(id));
            this.spool.ack(processed);
            if (failed.size) this.spool.fail([...failed], 'rejected by CRM');
            this.lastSuccessAt = new Date().toISOString();
            this.lastError = null;
            return batch.length === this.batchSize;
        } finally {
            this.busy = false;
        }
    }
}
