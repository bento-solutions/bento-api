import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
    DisconnectReason,
    fetchLatestBaileysVersion,
    makeCacheableSignalKeyStore,
    makeWASocket,
    useMultiFileAuthState,
} from '@whiskeysockets/baileys';
import {
    areJidsSameUser,
    e164ToPnJid,
    isIgnoredJid,
    isLidUser,
    isPnUser,
    jidNormalizedUser,
    pnJidToE164,
} from './jid.js';
import { receiptStatus, toInbound } from './normalize.js';

const INSTANCE_ID = crypto.randomUUID();
const LOCK_STALE_MS = 90_000;
const LOCK_HEARTBEAT_MS = 30_000;
/** WhatsApp drops an unused pairing code after roughly this long. */
const PAIRING_CODE_TTL_MS = 3.5 * 60_000;

let cachedVersion = null;
async function waVersion() {
    if (!cachedVersion) {
        cachedVersion = (await fetchLatestBaileysVersion()).version;
        setTimeout(() => { cachedVersion = null; }, 6 * 3_600_000).unref();
    }
    return cachedVersion;
}

export class SendError extends Error {
    constructor(code, message, { retryable, status }) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.status = status;
    }
}

/**
 * One linked WhatsApp device, i.e. one organization's WaAccount. Its credentials live in
 * `<dataDir>/sessions/<id>/`, which holds long-lived Signal private keys: it never leaves the
 * data volume.
 *
 * States: stopped, needs_pairing, connecting, pairing, open, reconnecting, logged_out, replaced,
 * pairing_failed, error. Every change is spooled to the CRM as a `session.status` event carrying
 * a per-session sequence number, so the CRM can apply them in order.
 */
export class Session {
    constructor({ id, config, spool, guard, logger }) {
        this.id = id;
        this.dir = path.join(config.dataDir, 'sessions', id);
        this.config = config;
        this.spool = spool;
        this.guard = guard;
        this.logger = logger.child({ session: id });
        this.state = 'stopped';
        this.sock = null;
        this.phoneNumber = null;
        this.pairing = null;
        this.pairingAttempts = 0;
        this.reconnectAttempts = 0;
        this.lastError = null;
        this.stopping = false;
        this.lockTimer = null;
        this.reconnectTimer = null;
    }

    get linked() {
        return Boolean(this.auth?.creds?.account);
    }

    get me() {
        const id = this.auth?.creds?.me?.id;
        return id ? pnJidToE164(jidNormalizedUser(id)) : null;
    }

    status() {
        return {
            id: this.id,
            state: this.state,
            linked: this.linked,
            phoneNumber: this.me,
            pairingCode: this.state === 'pairing' ? this.pairing?.code ?? null : null,
            pairingExpiresAt: this.state === 'pairing' ? this.pairing?.expiresAt ?? null : null,
            pairingAttempt: this.pairingAttempts,
            maxPairingAttempts: this.config.maxPairingAttempts,
            error: this.lastError,
        };
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /**
     * Connects a linked session, or starts linking one: with a phone number and no credentials,
     * WhatsApp is asked for a pairing code the owner types on the phone.
     */
    async start({ phoneNumber } = {}) {
        if (['connecting', 'pairing', 'open', 'reconnecting'].includes(this.state)) return this.status();
        this.stopping = false;
        this.lastError = null;
        this.pairingAttempts = 0;
        this.reconnectAttempts = 0;
        this.phoneNumber = phoneNumber ? String(phoneNumber).replace(/\D/g, '') : null;
        await fs.mkdir(this.dir, { recursive: true, mode: 0o700 });
        this.acquireLock();
        await this.loadAuthState();
        if (!this.linked && !this.phoneNumber) {
            this.setState('needs_pairing');
            this.releaseLock();
            return this.status();
        }
        await this.connect();
        return this.status();
    }

    /** Closes the connection but keeps the credentials, so start() resumes without pairing. */
    async stop() {
        this.stopping = true;
        clearTimeout(this.reconnectTimer);
        this.sock?.end(undefined);
        this.sock = null;
        this.releaseLock();
        this.setState('stopped');
        return this.status();
    }

    /** Unlinks the device from the phone and deletes the credentials. */
    async logout() {
        this.stopping = true;
        clearTimeout(this.reconnectTimer);
        try {
            if (this.sock && this.state === 'open') await this.sock.logout();
        } catch (err) {
            this.logger.warn({ err: err.message }, 'logout call failed; deleting credentials anyway');
        }
        this.sock?.end(undefined);
        this.sock = null;
        this.releaseLock();
        await fs.rm(this.dir, { recursive: true, force: true });
        this.auth = null;
        this.setState('logged_out');
        return this.status();
    }

    async loadAuthState() {
        let loaded = await useMultiFileAuthState(this.dir);
        // requestPairingCode() persists creds.me before the pairing completes. A directory left in
        // that state would make the next connection a login instead of a registration, which fails
        // exactly like being logged out.
        if (!loaded.state.creds.account && loaded.state.creds.me) {
            this.logger.info('discarding an unfinished pairing attempt');
            await fs.rm(this.dir, { recursive: true, force: true });
            await fs.mkdir(this.dir, { recursive: true, mode: 0o700 });
            loaded = await useMultiFileAuthState(this.dir);
        }
        await fs.chmod(this.dir, 0o700);
        this.auth = loaded.state;
        this.saveCreds = loaded.saveCreds;
    }

    async connect() {
        this.setState(this.linked ? 'connecting' : 'pairing');
        const version = await waVersion();
        const baileysLogger = this.logger.child({ class: 'baileys' });
        baileysLogger.level = this.config.logLevel === 'debug' ? 'debug' : 'warn';
        const sock = makeWASocket({
            version,
            logger: baileysLogger,
            auth: { creds: this.auth.creds, keys: makeCacheableSignalKeyStore(this.auth.keys, baileysLogger) },
            // Keep the phone as the active device so its notifications keep working.
            markOnlineOnConnect: false,
            // History is only ever pulled at first pairing; the CRM stores it without side effects.
            syncFullHistory: false,
            shouldIgnoreJid: jid => isIgnoredJid(jid),
            // Recipients that failed to decrypt ask for a resend; answer from our own send log.
            getMessage: async key => {
                const sent = this.spool.getSent(key.id);
                return sent?.text ? { conversation: sent.text } : undefined;
            },
            qrTimeout: 60_000,
        });
        this.sock = sock;
        let pairingRequested = false;

        sock.ev.on('creds.update', this.saveCreds);

        sock.ev.on('connection.update', async update => {
            if (sock !== this.sock) return;
            const { connection, lastDisconnect, qr } = update;

            // The first qr event is the signal that the socket can request a pairing code.
            if (qr && !this.auth.creds.registered && this.phoneNumber && !pairingRequested) {
                pairingRequested = true;
                this.pairingAttempts++;
                try {
                    const code = await sock.requestPairingCode(this.phoneNumber);
                    this.pairing = {
                        code: code.match(/.{1,4}/g).join('-'),
                        expiresAt: new Date(Date.now() + PAIRING_CODE_TTL_MS).toISOString(),
                    };
                    this.setState('pairing');
                } catch (err) {
                    this.lastError = `pairing code request failed: ${err.message}`;
                    this.logger.error({ err: err.message }, 'pairing code request failed');
                }
            }

            if (connection === 'open') {
                this.pairing = null;
                this.pairingAttempts = 0;
                this.reconnectAttempts = 0;
                this.lastError = null;
                this.setState('open');
                this.logger.info({ me: this.me }, 'connection open');
                this.retryHeld();
                return;
            }
            if (connection !== 'close' || this.stopping) return;

            const code = lastDisconnect?.error?.output?.statusCode;
            this.logger.warn({ code, reason: lastDisconnect?.error?.message }, 'connection closed');

            if (code === DisconnectReason.loggedOut) {
                // Revoked from the phone. The credentials are dead; linking again starts from zero.
                this.sock = null;
                this.releaseLock();
                await fs.rm(this.dir, { recursive: true, force: true });
                this.auth = null;
                this.setState('logged_out');
                return;
            }
            if (code === DisconnectReason.connectionReplaced) {
                // Another process opened this session. Fighting over it gets the number flagged.
                this.sock = null;
                this.releaseLock();
                this.setState('replaced');
                return;
            }
            if (!this.linked && this.pairingAttempts >= this.config.maxPairingAttempts) {
                this.sock = null;
                this.releaseLock();
                this.lastError = `no pairing after ${this.pairingAttempts} codes`;
                this.setState('pairing_failed');
                return;
            }

            // restartRequired is the normal handshake right after a successful link.
            const delay = code === DisconnectReason.restartRequired
                ? 0
                : Math.min(60_000, 2000 * 2 ** this.reconnectAttempts++);
            if (this.linked) this.setState('reconnecting');
            if (!this.linked) await this.loadAuthState();
            this.reconnectTimer = setTimeout(() => {
                this.connect().catch(err => {
                    this.lastError = err.message;
                    this.setState('error');
                });
            }, delay);
        });

        sock.ev.on('messages.upsert', async ({ messages, type }) => {
            for (const msg of messages) {
                // Baileys uses 'append' both for our own sends echoed back and for messages that
                // arrived while we were offline, so own sends are told apart by id, not by type.
                if (msg.key?.fromMe && this.spool.isOwnSend(msg.key.id)) continue;
                await this.forward(msg, type === 'notify' ? 'live' : 'offline');
            }
        });

        sock.ev.on('messaging-history.set', async ({ messages }) => {
            for (const msg of messages ?? []) await this.forward(msg, 'history');
        });

        sock.ev.on('messages.update', updates => {
            for (const { key, update } of updates) {
                if (!key?.fromMe || update?.status == null || isIgnoredJid(key.remoteJid)) continue;
                const status = receiptStatus(update.status);
                if (!status) continue;
                const at = update.messageTimestamp ? new Date(Number(update.messageTimestamp) * 1000) : new Date();
                this.spool.enqueue(this.id, 'message.status', { wamid: key.id, status, at: at.toISOString() });
            }
        });

        sock.ev.on('lid-mapping.update', ({ lid }) => {
            if (lid) this.retryHeld(lid);
        });
    }

    // ── inbound ──────────────────────────────────────────────────────────

    async forward(msg, origin) {
        if (isIgnoredJid(msg.key?.remoteJid)) return;
        const inbound = toInbound(msg, origin);
        if (!inbound) return;
        if (this.isOwnChat(inbound.chatJid)) return;
        const phone = await this.resolveContactPhone(inbound);
        if (!phone) {
            this.spool.hold(this.id, inbound.chatJid, inbound);
            this.logger.info({ lid: inbound.chatJid, wamid: inbound.wamid }, 'holding message until its LID resolves');
            return;
        }
        this.spool.enqueue(this.id, 'message.upsert', this.toEvent(inbound, phone));
    }

    toEvent(inbound, phone) {
        const { chatJid, altJid, ...rest } = inbound;
        return {
            ...rest,
            phoneE164: phone,
            jid: isPnUser(chatJid) ? jidNormalizedUser(chatJid) : (altJid && isPnUser(altJid) && !this.isOwnChat(altJid) ? jidNormalizedUser(altJid) : null),
            lid: isLidUser(chatJid) ? jidNormalizedUser(chatJid) : null,
        };
    }

    /**
     * The contact's phone number: the chat JID itself if it is a phone-number JID, the message's
     * alternate address for a LID chat (only for inbound — on a message sent from the owner's
     * phone that field is the owner's own address), or the session's LID→PN mapping.
     */
    async resolveContactPhone({ chatJid, altJid, direction }) {
        if (isPnUser(chatJid)) return pnJidToE164(jidNormalizedUser(chatJid));
        if (!isLidUser(chatJid)) return null;
        if (direction === 'IN' && altJid && isPnUser(altJid) && !this.isOwnChat(altJid)) {
            return pnJidToE164(jidNormalizedUser(altJid));
        }
        try {
            const pn = await this.sock?.signalRepository?.lidMapping?.getPNForLID(chatJid);
            return pn ? pnJidToE164(jidNormalizedUser(pn)) : null;
        } catch {
            return null;
        }
    }

    /** Re-tries held messages (all of them, or those of one LID once its mapping arrived). */
    async retryHeld(lid) {
        for (const row of this.spool.held(this.id, lid)) {
            const phone = await this.resolveContactPhone(row.data);
            if (!phone) continue;
            this.spool.enqueue(this.id, 'message.upsert', this.toEvent(row.data, phone));
            this.spool.release(row.id);
        }
    }

    isOwnChat(jid) {
        const me = this.auth?.creds?.me;
        if (!jid || !me) return false;
        return areJidsSameUser(jid, me.id) || (me.lid ? areJidsSameUser(jid, me.lid) : false);
    }

    // ── outbound ─────────────────────────────────────────────────────────

    /**
     * Sends text under the CRM-assigned id. Idempotent: a repeated messageId returns the stored
     * result instead of sending again, which is what makes the CRM's retries safe.
     */
    async sendText({ messageId, to, jid, text }) {
        const previous = this.spool.getSent(messageId);
        if (previous?.result) return previous.result;
        if (previous && !previous.result) {
            // A send with this id is in flight (or died mid-call); do not risk a duplicate.
            throw new SendError('SEND_IN_PROGRESS', 'a send with this id is already in progress',
                { retryable: true, status: 409 });
        }
        if (this.state !== 'open' || !this.sock) {
            throw new SendError('SESSION_NOT_OPEN', `session is ${this.state}`, { retryable: true, status: 409 });
        }
        const guard = this.guard.tryAcquire(this.id);
        if (!guard.ok) {
            throw new SendError('RATE_GUARD', `bot safety limit reached; retry in ${Math.ceil(guard.retryAfterMs / 1000)}s`,
                { retryable: true, status: 429 });
        }

        let target = jid && (isPnUser(jid) || isLidUser(jid)) ? jid : null;
        if (!target) {
            const pnJid = e164ToPnJid(to);
            if (!pnJid) throw new SendError('INVALID_NUMBER', 'not a valid phone number', { retryable: false, status: 422 });
            let lookup;
            try {
                [lookup] = await this.sock.onWhatsApp(pnJid);
            } catch (err) {
                throw new SendError('SEND_FAILED', `number lookup failed: ${err.message}`, { retryable: true, status: 502 });
            }
            if (!lookup?.exists) {
                throw new SendError('NOT_ON_WHATSAPP', 'this number is not on WhatsApp', { retryable: false, status: 422 });
            }
            target = lookup.jid;
        }

        this.spool.markSending(this.id, messageId, target, text);
        try {
            const sent = await this.sock.sendMessage(target, { text }, { messageId });
            const result = {
                wamid: sent?.key?.id ?? messageId,
                jid: target,
                timestamp: new Date().toISOString(),
            };
            this.spool.completeSent(messageId, result);
            return result;
        } catch (err) {
            this.spool.forgetUnsent(messageId);
            throw new SendError('SEND_FAILED', err.message, { retryable: true, status: 502 });
        }
    }

    // ── state + lock ─────────────────────────────────────────────────────

    setState(state) {
        const changed = state !== this.state || state === 'pairing';
        this.state = state;
        if (!changed) return;
        this.spool.enqueue(this.id, 'session.status', {
            ...this.status(),
            seq: this.spool.nextSeq(this.id),
            at: new Date().toISOString(),
        });
    }

    /**
     * A heartbeat lock file keeps two processes (say, a stray old container and a new one on the
     * same volume) from driving one session at once, which WhatsApp answers with 440 and the two
     * then knock each other off in a loop.
     */
    acquireLock() {
        const file = path.join(this.dir, '.lock');
        try {
            const stat = fsSync.statSync(file);
            const owner = fsSync.readFileSync(file, 'utf8').trim();
            if (owner !== INSTANCE_ID && Date.now() - stat.mtimeMs < LOCK_STALE_MS) {
                throw new Error(`session ${this.id} is locked by another bot instance (${owner})`);
            }
        } catch (err) {
            if (err.code !== 'ENOENT') throw err;
        }
        fsSync.writeFileSync(file, INSTANCE_ID, { mode: 0o600 });
        clearInterval(this.lockTimer);
        this.lockTimer = setInterval(() => {
            try {
                const now = new Date();
                fsSync.utimesSync(file, now, now);
            } catch { /* directory removed by logout */ }
        }, LOCK_HEARTBEAT_MS);
        this.lockTimer.unref();
    }

    releaseLock() {
        clearInterval(this.lockTimer);
        this.lockTimer = null;
        try {
            const file = path.join(this.dir, '.lock');
            if (fsSync.readFileSync(file, 'utf8').trim() === INSTANCE_ID) fsSync.rmSync(file);
        } catch { /* already gone */ }
    }
}
