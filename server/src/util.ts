import { createHash, randomBytes } from 'node:crypto';

/** Injected time source so every time-based rule can be tested without real waiting. */
export interface Clock {
  now(): number;
}
export const systemClock: Clock = { now: () => Date.now() };

export const iso = (ms: number): string => new Date(ms).toISOString();
export const isoOrNull = (ms: number | null | undefined): string | null => (ms == null ? null : iso(ms));

/** Parses an ISO timestamp (or epoch ms) into epoch ms; null when missing or invalid. */
export function parseTs(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v !== 'string' || v.length === 0 || v.length > 64) return null;
  const ms = /^\d{10,16}$/.test(v) ? Number(v) : Date.parse(v);
  return Number.isFinite(ms) ? ms : null;
}

export const sha256Hex = (s: string): string => createHash('sha256').update(s, 'utf8').digest('hex');

/** Opaque random token, URL-safe. */
export const randomToken = (bytes = 32): string => randomBytes(bytes).toString('base64url');

export const randomId = (prefix: string, bytes = 8): string => `${prefix}${randomBytes(bytes).toString('hex')}`;

export type Plain = Record<string, unknown>;
export const isPlainObject = (v: unknown): v is Plain =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

export function safeJsonParse<T>(s: unknown, fallback: T): T {
  if (typeof s !== 'string' || s.length === 0) return fallback;
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
}

/** Truncates a string to at most `maxBytes` UTF-8 bytes without splitting a character. */
export function truncateUtf8(s: string, maxBytes: number): string {
  const buf = Buffer.from(s, 'utf8');
  if (buf.length <= maxBytes) return s;
  let end = maxBytes;
  // Step back over UTF-8 continuation bytes (10xxxxxx) so the cut lands on a character start.
  while (end > 0 && ((buf[end] ?? 0) & 0xc0) === 0x80) end--;
  return buf.subarray(0, end).toString('utf8');
}

export const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 90) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 90) return `${m} min`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  if (h < 48) return rm ? `${h} h ${rm} min` : `${h} h`;
  const d = Math.floor(h / 24);
  const rh = h % 24;
  return rh ? `${d} d ${rh} h` : `${d} d`;
}

/** Bearer token from an Authorization header value. */
export function parseBearer(header: string | string[] | undefined): string | null {
  const v = Array.isArray(header) ? header[0] : header;
  if (!v) return null;
  const m = /^Bearer\s+(\S+)\s*$/i.exec(v);
  return m?.[1] ?? null;
}
