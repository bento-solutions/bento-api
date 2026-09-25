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
        // Each code is a request to WhatsApp's linking service; a few is plenty for a person
        // standing at the phone, and a stream of them looks like an automated takeover attempt.
        maxPairingAttempts: int(env.MAX_PAIRING_ATTEMPTS, 3),
        // Hard backstops, far below anything the CRM's pacing should ever reach: a person does not
        // send more than a handful of messages a minute, or open more than a few chats a day.
        guardPerMinute: int(env.GUARD_PER_MINUTE, 6),
        guardPerHour: int(env.GUARD_PER_HOUR, 60),
        guardNewChatsPerDay: int(env.GUARD_NEW_CHATS_PER_DAY, 15),
        // Read the chat and show "typing…" before each send, as a person would.
        simulateTyping: env.SIMULATE_TYPING !== 'false',
        // How often to ask WhatsApp for the number's restriction and new-chat quota while open.
        safetyRefreshMinutes: int(env.SAFETY_REFRESH_MINUTES, 30),
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
