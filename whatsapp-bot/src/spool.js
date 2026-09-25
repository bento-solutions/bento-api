import Database from 'better-sqlite3';

const MAX_ATTEMPTS = 200;

/**
 * Durable local state, in one SQLite file on the data volume:
 *
 * - events: everything bound for the CRM (messages, receipts, session changes), kept until the
 *   CRM acknowledges it, so a backend deploy or outage loses nothing;
 * - sent: ids this bot sent (for idempotent retries, echo suppression and Baileys' getMessage);
 * - held: messages from a LID contact whose phone number is not known yet;
 * - dead_letter: events that could not be delivered or resolved, kept for inspection.
 */
export class Spool {
    constructor(file) {
        this.db = new Database(file);
        this.db.pragma('journal_mode = WAL');
        this.db.pragma('synchronous = NORMAL');
        this.db.exec(`
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL,
                last_error TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_events_due ON events (next_attempt_at, id);
            CREATE TABLE IF NOT EXISTS session_seq (session_id TEXT PRIMARY KEY, seq INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS sent (
                message_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                jid TEXT,
                text TEXT,
                result TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS held (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                lid TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_held_lid ON held (session_id, lid);
            CREATE TABLE IF NOT EXISTS dead_letter (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT,
                type TEXT,
                payload TEXT,
                reason TEXT,
                created_at INTEGER NOT NULL
            );
        `);
        this.q = {
            insertEvent: this.db.prepare(
                'INSERT INTO events (session_id, type, payload, created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?)'),
            due: this.db.prepare('SELECT * FROM events WHERE next_attempt_at <= ? ORDER BY id LIMIT ?'),
            ack: this.db.prepare('DELETE FROM events WHERE id = ?'),
            getEvent: this.db.prepare('SELECT * FROM events WHERE id = ?'),
            retry: this.db.prepare('UPDATE events SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?'),
            dead: this.db.prepare(
                'INSERT INTO dead_letter (session_id, type, payload, reason, created_at) VALUES (?, ?, ?, ?, ?)'),
            nextSeq: this.db.prepare(`
                INSERT INTO session_seq (session_id, seq) VALUES (?, 1)
                ON CONFLICT (session_id) DO UPDATE SET seq = seq + 1 RETURNING seq`),
            markSending: this.db.prepare(
                'INSERT OR IGNORE INTO sent (message_id, session_id, jid, text, created_at) VALUES (?, ?, ?, ?, ?)'),
            completeSent: this.db.prepare('UPDATE sent SET result = ?, jid = ? WHERE message_id = ?'),
            getSent: this.db.prepare('SELECT * FROM sent WHERE message_id = ?'),
            forgetSent: this.db.prepare('DELETE FROM sent WHERE message_id = ? AND result IS NULL'),
            pruneSent: this.db.prepare('DELETE FROM sent WHERE created_at < ?'),
            hold: this.db.prepare('INSERT INTO held (session_id, lid, payload, created_at) VALUES (?, ?, ?, ?)'),
            heldFor: this.db.prepare('SELECT * FROM held WHERE session_id = ? AND lid = ? ORDER BY id'),
            heldAll: this.db.prepare('SELECT * FROM held WHERE session_id = ? ORDER BY id'),
            release: this.db.prepare('DELETE FROM held WHERE id = ?'),
            expiredHeld: this.db.prepare('SELECT * FROM held WHERE created_at < ?'),
            counts: this.db.prepare(`SELECT
                (SELECT count(*) FROM events) AS events,
                (SELECT count(*) FROM held) AS held,
                (SELECT count(*) FROM dead_letter) AS deadLetter`),
        };
    }

    enqueue(sessionId, type, data, now = Date.now()) {
        return Number(this.q.insertEvent.run(sessionId, type, JSON.stringify(data), now, now).lastInsertRowid);
    }

    /** Monotonic per-session sequence, persisted so it keeps increasing across restarts. */
    nextSeq(sessionId) {
        return this.q.nextSeq.get(sessionId).seq;
    }

    due(limit, now = Date.now()) {
        return this.q.due.all(now, limit).map(row => ({ ...row, data: JSON.parse(row.payload) }));
    }

    ack(ids) {
        const tx = this.db.transaction(list => list.forEach(id => this.q.ack.run(id)));
        tx(ids);
    }

    /** Backs off exponentially (1s → 10min); gives up after MAX_ATTEMPTS into dead_letter. */
    fail(ids, error, now = Date.now()) {
        const tx = this.db.transaction(list => {
            for (const id of list) {
                const row = this.q.getEvent.get(id);
                if (!row) continue;
                if (row.attempts + 1 >= MAX_ATTEMPTS) {
                    this.q.dead.run(row.session_id, row.type, row.payload, String(error).slice(0, 500), now);
                    this.q.ack.run(id);
                    continue;
                }
                const delay = Math.min(600_000, 1000 * 2 ** Math.min(row.attempts, 10));
                this.q.retry.run(now + delay, String(error).slice(0, 500), id);
            }
        });
        tx(ids);
    }

    /** Records a send before it happens, so its echo (which Baileys emits first) is recognised as ours. */
    markSending(sessionId, messageId, jid, text, now = Date.now()) {
        this.q.markSending.run(messageId, sessionId, jid, text, now);
    }

    completeSent(messageId, result) {
        this.q.completeSent.run(JSON.stringify(result), result.jid, messageId);
    }

    /** Drops the marker of a send that failed before reaching WhatsApp, so a retry is a real send. */
    forgetUnsent(messageId) {
        this.q.forgetSent.run(messageId);
    }

    getSent(messageId) {
        const row = this.q.getSent.get(messageId);
        return row ? { ...row, result: row.result ? JSON.parse(row.result) : null } : null;
    }

    isOwnSend(messageId) {
        return Boolean(this.q.getSent.get(messageId));
    }

    pruneSent(olderThan) {
        return this.q.pruneSent.run(olderThan).changes;
    }

    hold(sessionId, lid, payload, now = Date.now()) {
        this.q.hold.run(sessionId, lid, JSON.stringify(payload), now);
    }

    held(sessionId, lid) {
        const rows = lid ? this.q.heldFor.all(sessionId, lid) : this.q.heldAll.all(sessionId);
        return rows.map(row => ({ ...row, data: JSON.parse(row.payload) }));
    }

    release(id) {
        this.q.release.run(id);
    }

    /** Moves messages whose LID never resolved to dead_letter. */
    expireHeld(olderThan, now = Date.now()) {
        const rows = this.q.expiredHeld.all(olderThan);
        const tx = this.db.transaction(list => {
            for (const row of list) {
                this.q.dead.run(row.session_id, 'held', row.payload, `LID ${row.lid} never resolved`, now);
                this.q.release.run(row.id);
            }
        });
        tx(rows);
        return rows.length;
    }

    stats() {
        return this.q.counts.get();
    }

    close() {
        this.db.close();
    }
}
