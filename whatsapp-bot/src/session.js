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
import { cappingState, newChatBlock, reachoutState, readingPauseMs, typingDelayMs } from './safety.js';

const INSTANCE_ID = crypto.randomUUID();
const LOCK_STALE_MS = 90_000;
const LOCK_HEARTBEAT_MS = 30_000;
/** WhatsApp drops an unused pairing code after roughly this long. */
const PAIRING_CODE_TTL_MS = 3.5 * 60_000;
const DAY_MS = 24 * 3_600_000;

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

let cachedVersion = null;
async function waVersion() {
    if (!cachedVersion) {
        cachedVersion = (await fetchLatestBaileysVersion()).version;
        setTimeout(() => { cachedVersion = null; }, 6 * 3_600_000).unref();
    }
    return cachedVersion;
}

export class SendError extends Error {
    /**
     * @param until        when the refusal ends (ISO), for restrictions with a known end
     * @param retryAfterMs how long to wait before a retry can succeed, for rate limits
     */
    constructor(code, message, { retryable, status, until = null, retryAfterMs = null }) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.status = status;
        this.until = until;
        this.retryAfterMs = retryAfterMs;
    }
}

/**
 * One linked WhatsApp device, i.e. one organization's WaAccount. Its credentials live in
 * `<dataDir>/sessions/<id>/`, which holds long-lived Signal private keys: it never leaves the
 * data volume.
 *
 * States: stopped, needs_pairing, connecting, pairing, open, reconnecting, logged_out, replaced,
 * forbidden, pairing_failed, error. Every change is spooled to the CRM as a `session.status` event
 * carrying a per-session sequence number, so the CRM can apply them in order. The same event also
 * carries WhatsApp's restriction and new-chat quota for the number whenever they change.
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
        /** WhatsApp's reachout timelock: {active, until, type}. */
        this.reachout = null;
        /** WhatsApp's new-chat quota: {totalQuota, usedQuota, status, cycleEndsAt}. */
        this.capping = null;
        this.safetyTimer = null;
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
            reachoutLocked: Boolean(this.reachout?.active),
            reachoutUntil: this.reachout?.until ?? null,
            reachoutType: this.reachout?.type ?? null,
            newChatQuota: this.capping?.totalQuota ?? null,
            newChatUsed: this.capping?.usedQuota ?? null,
            newChatCapStatus: this.capping?.status ?? null,
            newChatCycleEndsAt: this.capping?.cycleEndsAt ?? null,
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
        this.stopSafetyWatch();
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
        this.stopSafetyWatch();
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
            const { connection, lastDisconnect, qr, reachoutTimeLock } = update;
            // Pushed by WhatsApp when a restriction starts or ends (and emitted after each query).
            if (reachoutTimeLock) this.applyReachout(reachoutTimeLock);

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
                this.startSafetyWatch();
                return;
            }
            if (connection !== 'close' || this.stopping) return;
            this.stopSafetyWatch();

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
            if (code === DisconnectReason.forbidden) {
                // WhatsApp refuses this account outright (a ban). Reconnecting in a loop would only
                // add to what got it banned; a person has to look at the phone first.
                this.sock = null;
                this.releaseLock();
                this.lastError = 'WhatsApp refused the connection (403): the number may be banned';
                this.setState('forbidden');
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
                const event = { wamid: key.id, status, at: at.toISOString() };
                if (status === 'FAILED') {
                    // WhatsApp refused the message itself, e.g. 463 when the number may not start
                    // new chats. Pass the code on, and re-read the restriction it may signal.
                    const [errorCode, errorTitle] = update.messageStubParameters ?? [];
                    if (errorCode) event.errorCode = String(errorCode);
                    if (errorTitle) event.errorTitle = String(errorTitle);
                    this.refreshSafety();
                }
                this.spool.enqueue(this.id, 'message.status', event);
            }
        });

        sock.ev.on('message-capping.update', info => this.applyCapping(info));

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
        this.rememberInbound(inbound, phone);
        this.spool.enqueue(this.id, 'message.upsert', this.toEvent(inbound, phone));
    }

    /** A chat with a message from the contact, or one the owner wrote from the phone, is known. */
    rememberInbound(inbound, phone) {
        const source = inbound.direction === 'OUT' ? 'phone' : 'in';
        this.spool.rememberContact(this.id, [e164ToPnJid(phone), jidNormalizedUser(inbound.chatJid)], source);
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
            this.rememberInbound(row.data, phone);
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
     *
     * A first message to a contact this number has never exchanged a message with is what WhatsApp
     * polices, so it is refused while WhatsApp restricts new chats or its quota is used up, and
     * capped per day by the bot itself whatever the CRM asks. Before sending, the bot reads the
     * contact's last message (when replying) and shows "typing…" for about as long as a person would.
     *
     * @param newChat  the CRM's view that this message opens the conversation
     * @param readUpTo the contact's latest message ({id}), marked read before replying
     */
    async sendText({ messageId, to, jid, text, newChat = false, readUpTo = null }) {
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

        const pnJid = to ? e164ToPnJid(to) : null;
        const opensChat = newChat || !this.spool.isKnownContact(this.id, [pnJid, jid && jidNormalizedUser(jid)]);
        if (opensChat) this.assertMayOpenChat();

        const guard = this.guard.tryAcquire(this.id);
        if (!guard.ok) {
            throw new SendError('RATE_GUARD', `bot safety limit reached; retry in ${Math.ceil(guard.retryAfterMs / 1000)}s`,
                { retryable: true, status: 429, retryAfterMs: guard.retryAfterMs });
        }

        let target = jid && (isPnUser(jid) || isLidUser(jid)) ? jid : null;
        if (!target) {
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
            await this.actLikeAPerson(target, text, readUpTo);
            const sent = await this.sock.sendMessage(target, { text }, { messageId });
            const result = {
                wamid: sent?.key?.id ?? messageId,
                jid: target,
                timestamp: new Date().toISOString(),
            };
            this.spool.completeSent(messageId, result);
            // Only a chat that was new counts toward the daily cap; a known one just gains an alias.
            this.spool.rememberContact(this.id, [pnJid ?? jidNormalizedUser(target), jidNormalizedUser(target)], 'out');
            return result;
        } catch (err) {
            this.spool.forgetUnsent(messageId);
            throw new SendError('SEND_FAILED', err.message, { retryable: true, status: 502 });
        }
    }

    /** Throws unless WhatsApp and the bot's own daily cap both allow opening another chat now. */
    assertMayOpenChat(now = Date.now()) {
        if (this.reachout?.active) {
            // Retrying a refused first message counts as another reach-out: never retry these.
            throw new SendError('REACHOUT_LOCKED',
                `WhatsApp restricted this number from starting new chats${this.reachout.until ? ` until ${this.reachout.until}` : ''}`,
                { retryable: false, status: 423, until: this.reachout.until });
        }
        const block = newChatBlock(this.capping, now);
        if (block.blocked) {
            throw new SendError('NEW_CHAT_CAP_REACHED', "WhatsApp's new-chat quota for this number is used up",
                { retryable: true, status: 429, until: block.until });
        }
        const opened = this.spool.chatsOpenedSince(this.id, now - DAY_MS);
        if (opened.n >= this.config.guardNewChatsPerDay) {
            throw new SendError('NEW_CHAT_GUARD',
                `bot safety limit of ${this.config.guardNewChatsPerDay} new chats a day reached`,
                { retryable: true, status: 429, retryAfterMs: Math.max(60_000, opened.oldest + DAY_MS - now) });
        }
    }

    /** Reads the contact's last message when replying, then shows "typing…" before the send. */
    async actLikeAPerson(target, text, readUpTo) {
        if (!this.config.simulateTyping) return;
        const sock = this.sock;
        try {
            if (readUpTo?.id) {
                await sock.readMessages([{ remoteJid: target, id: readUpTo.id, fromMe: false }]);
                await sleep(readingPauseMs());
            }
            await sock.presenceSubscribe(target);
            await sock.sendPresenceUpdate('composing', target);
            await sleep(typingDelayMs(text));
            await sock.sendPresenceUpdate('paused', target);
        } catch (err) {
            this.logger.debug({ err: err.message }, 'read/typing simulation failed; sending anyway');
        }
    }

    // ── WhatsApp's restriction and quota ─────────────────────────────────

    applyReachout(lock) {
        const next = reachoutState(lock);
        const changed = JSON.stringify(next) !== JSON.stringify(this.reachout ?? reachoutState(null));
        this.reachout = next;
        if (!changed) return;
        this.logger.warn({ reachout: next }, next.active ? 'WhatsApp restricted new chats' : 'WhatsApp lifted the new-chat restriction');
        this.publishStatus();
    }

    applyCapping(info) {
        const next = cappingState(info);
        if (!next || JSON.stringify(next) === JSON.stringify(this.capping)) return;
        this.capping = next;
        this.logger.info({ capping: next }, 'new-chat quota update');
        this.publishStatus();
    }

    /** Asks WhatsApp for the number's restriction and new-chat quota; failures are logged, never thrown. */
    async refreshSafety() {
        const sock = this.sock;
        if (!sock || this.state !== 'open') return;
        try {
            this.applyReachout(await sock.fetchAccountReachoutTimelock());
        } catch (err) {
            this.logger.warn({ err: err.message }, 'restriction query failed');
        }
        try {
            this.applyCapping(await sock.fetchNewChatMessageCap());
        } catch (err) {
            this.logger.warn({ err: err.message }, 'new-chat quota query failed');
        }
    }

    startSafetyWatch() {
        this.stopSafetyWatch();
        this.refreshSafety();
        this.safetyTimer = setInterval(() => this.refreshSafety(), this.config.safetyRefreshMinutes * 60_000);
        this.safetyTimer.unref();
    }

    stopSafetyWatch() {
        clearInterval(this.safetyTimer);
        this.safetyTimer = null;
    }

    // ── state + lock ─────────────────────────────────────────────────────

    setState(state) {
        const changed = state !== this.state || state === 'pairing';
        this.state = state;
        if (changed) this.publishStatus();
    }

    /** Reports the session to the CRM: on every state change, and when the restriction or quota moves. */
    publishStatus() {
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
