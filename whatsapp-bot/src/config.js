import path from 'node:path';

const int = (value, fallback) => {
    const n = Number.parseInt(value ?? '', 10);
    return Number.isFinite(n) ? n : fallback;
};

/**
 * Reads configuration from the environment. Secrets are validated up front: a bot that started
 * with an empty API key would accept sends from anyone who can reach it, and one with an empty
 * webhook secret would deliver unsigned (so rejected) events forever.
 */
export function loadConfig(env = process.env) {
    const config = {
        host: env.HOST || '0.0.0.0',
        port: int(env.PORT, 3000),
        apiKey: env.API_KEY || '',
        webhookUrl: env.WEBHOOK_URL || '',
        webhookSecret: env.WEBHOOK_SECRET || '',
        dataDir: path.resolve(env.DATA_DIR || '/data'),
        logLevel: env.LOG_LEVEL || 'info',
        maxPairingAttempts: int(env.MAX_PAIRING_ATTEMPTS, 5),
        guardPerMinute: int(env.GUARD_PER_MINUTE, 20),
        guardPerHour: int(env.GUARD_PER_HOUR, 200),
        sentRetentionDays: int(env.SENT_RETENTION_DAYS, 7),
        lidHoldHours: int(env.LID_HOLD_HOURS, 24),
        webhookBatchSize: int(env.WEBHOOK_BATCH_SIZE, 50),
    };
    const problems = [];
    if (config.apiKey.length < 32) problems.push('API_KEY must be at least 32 characters (openssl rand -hex 32)');
    if (config.webhookSecret.length < 32) problems.push('WEBHOOK_SECRET must be at least 32 characters');
    if (!/^https?:\/\//.test(config.webhookUrl)) problems.push('WEBHOOK_URL must be an http(s) URL');
    return { config, problems };
}
