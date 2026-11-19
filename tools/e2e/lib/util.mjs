import { createHmac } from 'node:crypto';
import net from 'node:net';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class AssertionError extends Error {}

/** Throws an AssertionError with `message` when `cond` is falsy. */
export function check(cond, message) {
  if (!cond) throw new AssertionError(message);
}

export function eq(actual, expected, what) {
  if (actual !== expected) {
    throw new AssertionError(`${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

/**
 * Polls `fn` until it returns a truthy value (returned) or the timeout passes. Errors thrown by
 * `fn` count as "not yet" and the last one is included in the timeout message.
 */
export async function waitFor(fn, { timeout = 10_000, interval = 200, label = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let lastErr = null;
  for (;;) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (err) {
      lastErr = err;
    }
    if (Date.now() > deadline) {
      throw new AssertionError(`timed out after ${Math.round(timeout / 1000)} s waiting for ${label}${lastErr ? ` (last error: ${lastErr.message})` : ''}`);
    }
    await sleep(interval);
  }
}

/** A TCP port that was free a moment ago on 127.0.0.1. */
export function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

export const short = (v, n = 300) => {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? `${s.slice(0, n)}...` : s;
};

// --- TOTP (RFC 6238: HMAC-SHA1, 6 digits, 30 s step), node:crypto only -------------------------

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function base32Decode(input) {
  const s = input.toUpperCase().replace(/[\s=-]/g, '');
  let bits = 0;
  let value = 0;
  const out = [];
  for (const ch of s) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error(`invalid base32 character "${ch}"`);
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

export function hotp(key, counter, digits = 6) {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const mac = createHmac('sha1', key).update(msg).digest();
  const offset = mac[mac.length - 1] & 0x0f;
  const bin = ((mac[offset] & 0x7f) << 24) | (mac[offset + 1] << 16) | (mac[offset + 2] << 8) | mac[offset + 3];
  return String(bin % 10 ** digits).padStart(digits, '0');
}

export const TOTP_STEP_MS = 30_000;
export const totpStep = (ms = Date.now()) => Math.floor(ms / TOTP_STEP_MS);

/**
 * An authenticator for one secret. The Server accepts the current step +/- 1 and rejects a step
 * at or below the last one it accepted (replay protection), so every code handed out here uses a
 * step that has not been used yet, waiting for the clock when needed.
 */
export class Authenticator {
  constructor(secret) {
    this.secret = secret;
    this.key = base32Decode(secret);
    this.lastStep = null;
  }

  async nextCode() {
    for (;;) {
      const now = totpStep();
      const step = this.lastStep === null ? now : Math.max(now, this.lastStep + 1);
      if (step <= now + 1) {
        this.lastStep = step;
        return hotp(this.key, step);
      }
      await sleep(Math.max(200, (now + 1) * TOTP_STEP_MS - Date.now() + 200));
    }
  }
}
