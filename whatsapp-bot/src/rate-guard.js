/**
 * Last line of defence against a runaway sender: a hard per-session cap on sends per minute and
 * per hour, independent of (and looser than) the CRM outbox pacing. If the CRM ever misbehaves,
 * this is what stops a personal number from being flooded into a ban.
 */
export class RateGuard {
    constructor({ perMinute, perHour }) {
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.history = new Map();
    }

    /** @returns {{ok: true} | {ok: false, retryAfterMs: number}} and records the send when ok */
    tryAcquire(sessionId, now = Date.now()) {
        const list = (this.history.get(sessionId) ?? []).filter(t => t > now - 3_600_000);
        const lastMinute = list.filter(t => t > now - 60_000);
        if (lastMinute.length >= this.perMinute) {
            this.history.set(sessionId, list);
            return { ok: false, retryAfterMs: lastMinute[0] + 60_000 - now };
        }
        if (list.length >= this.perHour) {
            this.history.set(sessionId, list);
            return { ok: false, retryAfterMs: list[0] + 3_600_000 - now };
        }
        list.push(now);
        this.history.set(sessionId, list);
        return { ok: true };
    }
}
