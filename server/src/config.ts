import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

// Both src/config.ts (run by tsx / vitest) and dist/index.js (the tsup bundle) sit exactly one
// directory below the server package, so the same relative path works in dev and production.
const HERE = dirname(fileURLToPath(import.meta.url));
export const SERVER_DIR = resolve(HERE, '..');
export const REPO_DIR = resolve(SERVER_DIR, '..');

export type LogLevel = 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace' | 'silent';

export interface Config {
  version: string;
  nodeEnv: string;
  port: number;
  host: string;
  /** Absolute base URL without trailing slash, or null to derive it from each request. */
  publicUrl: string | null;
  databaseUrl: string;
  databaseAuthToken: string | null;
  adminUsername: string | null;
  adminPassword: string | null;
  sessionTtlDays: number;
  telegramBotToken: string | null;
  telegramChatId: string | null;
  vapidPublicKey: string | null;
  vapidPrivateKey: string | null;
  vapidSubject: string | null;
  corsOrigins: string[];
  webDist: string;
  logLevel: LogLevel;
  /**
   * Which proxies may set X-Forwarded-For/-Proto/-Host: true (any), false (none), a hop count
   * (the client IP is the entry that many hops from the right), or a comma list of proxy
   * addresses/CIDRs. See trustProxyOption.
   */
  trustProxy: boolean | number | string;
}

export const MIN_PASSWORD_LENGTH = 10;

function readVersion(): string {
  try {
    const pkg = JSON.parse(readFileSync(resolve(SERVER_DIR, 'package.json'), 'utf8')) as { version?: string };
    return pkg.version ?? '0.0.0';
  } catch {
    return '0.0.0';
  }
}

export class ConfigError extends Error {}

/** Empty strings in .env files mean "unset". */
const blank = (v: unknown) => (typeof v === 'string' ? (v.trim() === '' ? undefined : v.trim()) : v);
const opt = z.preprocess(blank, z.string().optional());

const httpUrl = (name: string) =>
  opt.refine(
    (v) => {
      if (v === undefined) return true;
      try {
        const u = new URL(v);
        return u.protocol === 'http:' || u.protocol === 'https:';
      } catch {
        return false;
      }
    },
    { message: `${name} must be an absolute http(s) URL` },
  );

const EnvSchema = z.object({
  NODE_ENV: opt,
  PORT: z.preprocess(blank, z.coerce.number().int().min(0).max(65535).optional()),
  HOST: opt,
  PUBLIC_URL: httpUrl('PUBLIC_URL'),
  /** Set by Render on every web service (https://<name>.onrender.com); fallback for PUBLIC_URL. */
  RENDER_EXTERNAL_URL: httpUrl('RENDER_EXTERNAL_URL'),
  DATABASE_URL: opt,
  DATABASE_AUTH_TOKEN: opt,
  ADMIN_USERNAME: opt,
  ADMIN_PASSWORD: z.string().optional(),
  SESSION_TTL_DAYS: z.preprocess(blank, z.coerce.number().positive().max(3650).optional()),
  TELEGRAM_BOT_TOKEN: opt,
  TELEGRAM_CHAT_ID: opt,
  VAPID_PUBLIC_KEY: opt,
  VAPID_PRIVATE_KEY: opt,
  VAPID_SUBJECT: opt.refine((v) => v === undefined || /^(mailto:|https:\/\/)/i.test(v), {
    message: 'VAPID_SUBJECT must start with mailto: or https://',
  }),
  CORS_ORIGINS: opt,
  WEB_DIST: opt,
  LOG_LEVEL: z.preprocess(blank, z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).optional()),
  TRUST_PROXY: opt,
});

/**
 * TRUST_PROXY:
 * - an integer N: trust exactly N proxy hops (the socket peer and the N-1 entries to its left), so
 *   the client IP is the N-th X-Forwarded-For entry from the right. Behind Render, 1. A client can
 *   prepend whatever it likes but cannot change the entry the proxy appended. 0 = false.
 * - true / yes / on: trust everything; the client IP is the left-most X-Forwarded-For entry, which
 *   a client can forge.
 * - false / no / off: ignore X-Forwarded-* headers.
 * - anything else: a comma list of trusted proxy addresses, CIDRs or the names loopback,
 *   linklocal, uniquelocal (Docker + Caddy).
 */
function parseTrustProxy(v: string | undefined): boolean | number | string {
  if (v === undefined) return true;
  const s = v.toLowerCase();
  if (/^\d+$/.test(s)) {
    const n = Number(s);
    if (n > 100) throw new ConfigError(`TRUST_PROXY: hop count ${n} is not plausible`);
    return n === 0 ? false : n;
  }
  if (['true', 'yes', 'on'].includes(s)) return true;
  if (['false', 'no', 'off'].includes(s)) return false;
  return v;
}

/**
 * Fastify's `trustProxy` option for the configured value. Fastify 5 treats a number as "trust
 * nothing" (lib/request.js getTrustProxyFn), so a hop count becomes a function: @fastify/proxy-addr
 * calls it with hop 0 for the socket peer, 1 for the right-most X-Forwarded-For entry, and so on,
 * and the client IP is the first address that is not trusted.
 */
export function trustProxyOption(tp: boolean | number | string): boolean | string | ((addr: string, hop: number) => boolean) {
  if (typeof tp !== 'number') return tp;
  const hops = tp;
  return (_addr: string, hop: number) => hop < hops;
}

function parseOrigins(v: string | undefined): string[] {
  if (!v) return [];
  const out: string[] = [];
  for (const part of v.split(',')) {
    const s = part.trim();
    if (!s) continue;
    let origin: string;
    try {
      origin = new URL(s).origin;
    } catch {
      throw new ConfigError(`CORS_ORIGINS: "${s}" is not a valid origin (expected e.g. https://hsm.vercel.app)`);
    }
    out.push(origin);
  }
  return out;
}

/** Reads and validates the environment. Throws ConfigError with every problem listed. */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const lines = parsed.error.issues.map((i) => `  ${i.path.join('.') || '(env)'}: ${i.message}`);
    throw new ConfigError(`Invalid environment:\n${lines.join('\n')}`);
  }
  const e = parsed.data;
  const rawPublic = e.PUBLIC_URL ?? e.RENDER_EXTERNAL_URL;
  const publicUrl = rawPublic ? rawPublic.replace(/\/+$/, '') : null;
  const corsOrigins = parseOrigins(e.CORS_ORIGINS);
  const adminPassword = e.ADMIN_PASSWORD && e.ADMIN_PASSWORD.length > 0 ? e.ADMIN_PASSWORD : null;

  return {
    version: readVersion(),
    nodeEnv: e.NODE_ENV ?? 'development',
    port: e.PORT ?? 8080,
    host: e.HOST ?? '0.0.0.0',
    publicUrl,
    databaseUrl: e.DATABASE_URL ?? `file:${resolve(SERVER_DIR, 'data', 'hsm.db')}`,
    databaseAuthToken: e.DATABASE_AUTH_TOKEN ?? null,
    adminUsername: e.ADMIN_USERNAME ?? null,
    adminPassword,
    sessionTtlDays: e.SESSION_TTL_DAYS ?? 30,
    telegramBotToken: e.TELEGRAM_BOT_TOKEN ?? null,
    telegramChatId: e.TELEGRAM_CHAT_ID ?? null,
    vapidPublicKey: e.VAPID_PUBLIC_KEY ?? null,
    vapidPrivateKey: e.VAPID_PRIVATE_KEY ?? null,
    vapidSubject: e.VAPID_SUBJECT ?? null,
    corsOrigins,
    webDist: e.WEB_DIST ? resolve(e.WEB_DIST) : resolve(REPO_DIR, 'web', 'dist'),
    logLevel: e.LOG_LEVEL ?? 'info',
    trustProxy: parseTrustProxy(e.TRUST_PROXY),
  };
}
