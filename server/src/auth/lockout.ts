import type { Clock } from '../util.js';

export const MAX_FAILURES = 5;
export const LOCK_MS = 15 * 60_000;
const MAX_ENTRIES = 10_000;

interface Entry {
  failures: number;
  firstAt: number;
  lockedUntil: number;
}

/**
 * Failed-login tracker (plan section 10: rate limiting and lockout). Keys are `u:<username>` and
 * `ip:<address>`; 5 failures within 15 minutes lock that key for 15 minutes.
 *
 * Kept in memory: a restart clears it, but a Render free instance only restarts after 15 idle
 * minutes, which is as long as any lock lasts anyway.
 */
export class LoginGuard {
  private entries = new Map<string, Entry>();

  constructor(private clock: Clock) {}

  /** Milliseconds until every given key is unlocked; 0 when none is locked. */
  lockedFor(keys: string[]): number {
    const now = this.clock.now();
    let max = 0;
    for (const k of keys) {
      const e = this.entries.get(k);
      if (e && e.lockedUntil > now) max = Math.max(max, e.lockedUntil - now);
    }
    return max;
  }

  fail(keys: string[]): void {
    const now = this.clock.now();
    for (const k of keys) {
      let e = this.entries.get(k);
      if (!e || now - e.firstAt > LOCK_MS) {
        e = { failures: 0, firstAt: now, lockedUntil: e?.lockedUntil ?? 0 };
        this.entries.set(k, e);
      }
      e.failures++;
      if (e.failures >= MAX_FAILURES) {
        e.lockedUntil = now + LOCK_MS;
        e.failures = 0;
        e.firstAt = now;
      }
    }
    if (this.entries.size > MAX_ENTRIES) this.prune();
  }

  succeed(keys: string[]): void {
    for (const k of keys) this.entries.delete(k);
  }

  prune(): void {
    const now = this.clock.now();
    for (const [k, e] of this.entries) {
      if (e.lockedUntil <= now && now - e.firstAt > LOCK_MS) this.entries.delete(k);
    }
  }
}
