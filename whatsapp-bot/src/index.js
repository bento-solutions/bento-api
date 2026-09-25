import fs from 'node:fs/promises';
import path from 'node:path';
import pino from 'pino';
import { loadConfig } from './config.js';
import { Spool } from './spool.js';
import { WebhookDeliverer } from './webhook.js';
import { RateGuard } from './rate-guard.js';
import { SessionManager } from './session-manager.js';
import { createApp } from './http.js';

const { config, problems } = loadConfig();
const logger = pino({ level: config.logLevel });
if (problems.length) {
    problems.forEach(p => logger.fatal(p));
    process.exit(78);
}

await fs.mkdir(config.dataDir, { recursive: true, mode: 0o700 });
const spool = new Spool(path.join(config.dataDir, 'bot.sqlite'));
const deliverer = new WebhookDeliverer({
    url: config.webhookUrl, secret: config.webhookSecret, spool, logger, batchSize: config.webhookBatchSize,
});
const guard = new RateGuard({ perMinute: config.guardPerMinute, perHour: config.guardPerHour });
const manager = new SessionManager({ config, spool, guard, logger });

const housekeeping = setInterval(() => {
    const day = 86_400_000;
    spool.pruneSent(Date.now() - config.sentRetentionDays * day);
    const expired = spool.expireHeld(Date.now() - config.lidHoldHours * 3_600_000);
    if (expired) logger.warn({ expired }, 'dead-lettered messages whose LID never resolved');
    for (const session of manager.sessions.values()) {
        if (session.state === 'open') session.retryHeld();
    }
}, 5 * 60_000);
housekeeping.unref();

const server = createApp({ config, manager, spool, deliverer }).listen(config.port, config.host, () => {
    logger.info({ host: config.host, port: config.port }, 'bot API listening');
});
deliverer.start();
await manager.resumeAll();

let shuttingDown = false;
for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, async () => {
        if (shuttingDown) return;
        shuttingDown = true;
        logger.info({ signal }, 'shutting down');
        server.close();
        // Close sockets cleanly so no credential write is cut off mid-file, then flush the spool.
        await manager.stopAll();
        await deliverer.deliverOnce().catch(() => undefined);
        deliverer.stop();
        spool.close();
        process.exit(0);
    });
}
