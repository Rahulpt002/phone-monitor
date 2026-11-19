import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

/** TOTP per RFC 6238 (HMAC-SHA1, 6 digits, 30 s step), implemented with node:crypto only. */

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export const TOTP_STEP_S = 30;
export const TOTP_DIGITS = 6;
export const TOTP_WINDOW = 1;

export function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(input: string): Buffer {
  const s = input.toUpperCase().replace(/[\s=-]/g, '');
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of s) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error('invalid base32 character');
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** 160-bit secret (RFC 4226 recommends 160 bits for SHA1), base32 encoded. */
export const generateTotpSecret = (): string => base32Encode(randomBytes(20));

export function hotp(key: Buffer, counter: number, digits = TOTP_DIGITS): string {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const mac = createHmac('sha1', key).update(msg).digest();
  const offset = (mac[mac.length - 1] ?? 0) & 0x0f;
  const bin =
    (((mac[offset] ?? 0) & 0x7f) << 24) |
    ((mac[offset + 1] ?? 0) << 16) |
    ((mac[offset + 2] ?? 0) << 8) |
    (mac[offset + 3] ?? 0);
  return String(bin % 10 ** digits).padStart(digits, '0');
}

export const totpStep = (timeMs: number): number => Math.floor(timeMs / 1000 / TOTP_STEP_S);

export function totpCode(secretB32: string, timeMs: number, digits = TOTP_DIGITS): string {
  return hotp(base32Decode(secretB32), totpStep(timeMs), digits);
}

/**
 * Checks a code against the current step +/- TOTP_WINDOW. Returns the matching step, or null.
 * A step at or below `lastUsedStep` is rejected so the same code cannot be replayed.
 */
export function verifyTotp(
  secretB32: string,
  code: string,
  timeMs: number,
  lastUsedStep: number | null,
): number | null {
  const clean = code.replace(/\s/g, '');
  if (!/^\d{6}$/.test(clean)) return null;
  let key: Buffer;
  try {
    key = base32Decode(secretB32);
  } catch {
    return null;
  }
  const now = totpStep(timeMs);
  let match: number | null = null;
  // Check every candidate (no early exit) to keep timing independent of which step matched.
  for (let d = -TOTP_WINDOW; d <= TOTP_WINDOW; d++) {
    const step = now + d;
    const expected = Buffer.from(hotp(key, step));
    if (timingSafeEqual(expected, Buffer.from(clean)) && match === null) match = step;
  }
  if (match === null) return null;
  if (lastUsedStep != null && match <= lastUsedStep) return null;
  return match;
}

export function otpauthUrl(secretB32: string, account: string, issuer = 'Hotspot Monitor'): string {
  const label = `${encodeURIComponent(issuer)}:${encodeURIComponent(account)}`;
  const q = new URLSearchParams({
    secret: secretB32,
    issuer,
    algorithm: 'SHA1',
    digits: String(TOTP_DIGITS),
    period: String(TOTP_STEP_S),
  });
  return `otpauth://totp/${label}?${q.toString()}`;
}
