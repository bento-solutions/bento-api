import fs from 'node:fs/promises';
import path from 'node:path';
import { Session } from './session.js';

/** Session ids are WaAccount UUIDs; anything else is refused before it can touch the filesystem. */
export const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class SessionManager {
    constructor({ config, spool, guard, logger }) {
        Object.assign(this, { config, spool, guard, logger });
        this.sessions = new Map();
    }

    get(id) {
        if (!SESSION_ID.test(id)) throw new Error('invalid session id');
        let session = this.sessions.get(id);
        if (!session) {
            session = new Session({ id, config: this.config, spool: this.spool, guard: this.guard, logger: this.logger });
            this.sessions.set(id, session);
        }
        return session;
    }

    find(id) {
        return SESSION_ID.test(id) ? this.sessions.get(id) ?? null : null;
    }

    list() {
        return [...this.sessions.values()].map(s => s.status());
    }

    /** Reconnects every linked session found on the data volume (after a restart or deploy). */
    async resumeAll() {
        const root = path.join(this.config.dataDir, 'sessions');
        await fs.mkdir(root, { recursive: true, mode: 0o700 });
        for (const id of await fs.readdir(root)) {
            if (!SESSION_ID.test(id)) continue;
            try {
                const creds = JSON.parse(await fs.readFile(path.join(root, id, 'creds.json'), 'utf8'));
                if (!creds.account) continue;
                await this.get(id).start();
                this.logger.info({ session: id }, 'resumed linked session');
            } catch (err) {
                this.logger.warn({ session: id, err: err.message }, 'could not resume session');
            }
        }
    }

    async stopAll() {
        await Promise.allSettled([...this.sessions.values()].map(s => s.stop()));
    }
}
