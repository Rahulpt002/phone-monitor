import { randomBytes } from 'node:crypto';
import type { MetricStats } from '@hsm/shared';

const BASE62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';

/**
 * Unique id with a type prefix ("e_", "s_", "m_", "p_"). Uses crypto randomness rather than the
 * seeded simulation RNG: the Server de-duplicates events, spots and SMS by id, so two runs with
 * the same --seed must still produce different ids.
 */
export function newId(prefix: string, length = 10): string {
  let out = prefix;
  for (const b of randomBytes(length)) out += BASE62.charAt(b % 62);
  return out;
}

export const round1 = (x: number): number => Math.round(x * 10) / 10;
export const round2 = (x: number): number => Math.round(x * 100) / 100;

export function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export const delay = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

/** Local hour of day as a fraction (14.5 = 14:30). */
export function hourOfDay(ms: number): number {
  const d = new Date(ms);
  return d.getHours() + d.getMinutes() / 60 + d.getSeconds() / 3600;
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/** Local calendar date "YYYY-MM-DD". */
export function localDateKey(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** Gaussian bump on the 24-hour circle: 1 at `center`, falling off with `width` hours. */
export function dayBump(hour: number, center: number, width: number): number {
  let d = Math.abs(hour - center);
  d = Math.min(d, 24 - d);
  return Math.exp(-(d * d) / (2 * width * width));
}

export function median(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  const hi = sorted[mid] as number;
  return sorted.length % 2 === 1 ? hi : ((sorted[mid - 1] as number) + hi) / 2;
}

/** Average, minimum, maximum and population standard deviation (the Finder's "stability"). */
export function stats(values: readonly number[]): MetricStats {
  if (values.length === 0) return { avg: 0, min: 0, max: 0, std: 0, samples: 0 };
  const avg = values.reduce((a, b) => a + b, 0) / values.length;
  const variance = values.reduce((a, b) => a + (b - avg) ** 2, 0) / values.length;
  return {
    avg: round1(avg),
    min: round1(Math.min(...values)),
    max: round1(Math.max(...values)),
    std: round1(Math.sqrt(variance)),
    samples: values.length,
  };
}

/** "45 s", "3 min 20 s", "2 h 5 min". */
export function formatDuration(seconds: number): string {
  if (seconds > 0 && seconds < 9.95 && !Number.isInteger(seconds)) return `${seconds.toFixed(1)} s`;
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return `${s} s`;
  if (s < 3600) {
    const rest = s % 60;
    return rest ? `${Math.floor(s / 60)} min ${rest} s` : `${s / 60} min`;
  }
  const minutes = Math.floor((s % 3600) / 60);
  return minutes ? `${Math.floor(s / 3600)} h ${minutes} min` : `${Math.floor(s / 3600)} h`;
}

/** Cuts a string to at most `maxBytes` of UTF-8, marking the cut. */
export function truncateUtf8(text: string, maxBytes: number): string {
  if (Buffer.byteLength(text, 'utf8') <= maxBytes) return text;
  const marker = '\n[output truncated]';
  const budget = Math.max(0, maxBytes - Buffer.byteLength(marker, 'utf8'));
  let cut = Buffer.from(text, 'utf8').subarray(0, budget).toString('utf8');
  // A multi-byte character split at the boundary decodes to U+FFFD; drop it.
  if (cut.endsWith('�')) cut = cut.slice(0, -1);
  return cut + marker;
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** Base URL without trailing slashes. */
export function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

/** ws(s)://host/ws/agent for an http(s) base URL. */
export function agentWsUrl(baseUrl: string): string {
  const u = new URL(normalizeBaseUrl(baseUrl));
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.pathname = `${u.pathname.replace(/\/+$/, '')}/ws/agent`;
  u.search = '';
  u.hash = '';
  return u.toString();
}
