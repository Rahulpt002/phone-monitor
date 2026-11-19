import type { FastifyRequest } from 'fastify';
import type { AppContext } from '../context.js';
import { badRequest } from '../errors.js';
import { parseTs } from '../util.js';

export type Query = Record<string, string | string[] | undefined>;

export const query = (req: FastifyRequest): Query => (req.query ?? {}) as Query;

const first = (v: string | string[] | undefined): string | undefined => (Array.isArray(v) ? v[0] : v);

export function qString(q: Query, name: string, max = 200): string | undefined {
  const v = first(q[name]);
  if (v === undefined || v === '') return undefined;
  if (v.length > max) throw badRequest(`${name} is too long`);
  return v;
}

export function qInt(q: Query, name: string, def: number, min: number, max: number): number {
  const v = first(q[name]);
  if (v === undefined || v === '') return def;
  const n = Number(v);
  if (!Number.isInteger(n)) throw badRequest(`${name} must be an integer`);
  return Math.min(max, Math.max(min, n));
}

/** ISO timestamp or epoch ms; undefined when absent. */
export function qTime(q: Query, name: string): number | undefined {
  const v = first(q[name]);
  if (v === undefined || v === '') return undefined;
  const ms = parseTs(v);
  if (ms == null) throw badRequest(`${name} must be an ISO timestamp`);
  return ms;
}

/** The Server's public base URL: PUBLIC_URL, else what the client used (behind the proxy). */
export function baseUrl(ctx: AppContext, req: FastifyRequest): string {
  if (ctx.config.publicUrl) return ctx.config.publicUrl;
  return `${req.protocol}://${req.host}`.replace(/\/+$/, '');
}

export const userAgent = (req: FastifyRequest): string | null => {
  const ua = req.headers['user-agent'];
  return typeof ua === 'string' ? ua : null;
};

export const RATE_AUTH = { max: 10, timeWindow: 60_000 };
export const RATE_PAIR = { max: 10, timeWindow: 60_000 };
