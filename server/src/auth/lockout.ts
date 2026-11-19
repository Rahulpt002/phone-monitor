import type { Clock } from '../util.js';

export const MAX_FAILURES = 5;
export const LOCK_MS = 15 * 60_000;
/** Reported wait while the remaining attempts of a key are all still being checked. */
const IN_FLIGHT_WAIT_MS = 60_000;
const MAX_ENTRIES = 10_000;

interface Entry {
  failures: number;
  firstAt: number;
  lockedUntil: number;
  /** Checks started (reserve) and not finished yet; each may still become a failure. */
  pending: number;
}

/**
 * Failed-attempt tracker (plan section 10: rate limiting and lockout). Login uses the keys
 * `u:<username>` and `ip:<address>`; re-authentication inside a session uses `s:<session hash>`.
 * 5 failures within 15 minutes lock that key for 15 minutes.
 *
 * A password check awaits scrypt, so a caller reserves its attempt before awaiting (reserve) and
 * releases it afterwards. Attempts in flight count toward the limit: a burst of concurrent wrong
 * passwords gets at most MAX_FAILURES evaluations, not one per request.
 *
 * Kept in memory: a restart clears it, but a Render free instance only restarts after 15 idle
 * minutes, which is as long as any lock lasts anyway.
 */
export class LoginGuard {
  private entries = new Map<string, Entry>();

  constructor(private clock: Clock) {}

  private recentFailures(e: Entry, now: number): number {
    return now - e.firstAt > LOCK_MS ? 0 : e.failures;
  }

  /** Milliseconds until every given key is unlocked; 0 when none is locked. */
  lockedFor(keys: string[]): number {
    const now = this.clock.now();
    let max = 0;
    for (const k of keys) {
      const e = this.entries.get(k);
      if (!e) continue;
      if (e.lockedUntil > now) max = Math.max(max, e.lockedUntil - now);
      else if (this.recentFailures(e, now) + e.pending >= MAX_FAILURES) max = Math.max(max, IN_FLIGHT_WAIT_MS);
    }
    return max;
  }

  /**
   * Counts one attempt as in flight on every key until the returned function is called (exactly
   * once, after the check, before or together with fail/succeed).
   */
  reserve(keys: string[]): () => void {
    const now = this.clock.now();
    for (const k of keys) {
      let e = this.entries.get(k);
      if (!e) {
        e = { failures: 0, firstAt: now, lockedUntil: 0, pending: 0 };
        this.entries.set(k, e);
      }
      e.pending++;
    }
    if (this.entries.size > MAX_ENTRIES) this.prune();
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const at = this.clock.now();
      // Entries with pending > 0 are never deleted, so the key still holds this attempt.
      for (const k of keys) {
        const e = this.entries.get(k);
        if (!e) continue;
        e.pending = Math.max(0, e.pending - 1);
        if (e.pending === 0 && e.failures === 0 && e.lockedUntil <= at) this.entries.delete(k);
      }
    };
  }

  fail(keys: string[]): void {
    const now = this.clock.now();
    for (const k of keys) {
      let e = this.entries.get(k);
      if (!e) {
        e = { failures: 0, firstAt: now, lockedUntil: 0, pending: 0 };
        this.entries.set(k, e);
      } else if (now - e.firstAt > LOCK_MS) {
        e.failures = 0;
        e.firstAt = now;
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
    for (const k of keys) {
      const e = this.entries.get(k);
      if (!e) continue;
      if (e.pending > 0) {
        e.failures = 0;
        e.lockedUntil = 0;
      } else {
        this.entries.delete(k);
      }
    }
  }

  prune(): void {
    const now = this.clock.now();
    for (const [k, e] of this.entries) {
      if (e.pending === 0 && e.lockedUntil <= now && now - e.firstAt > LOCK_MS) this.entries.delete(k);
    }
  }
}
