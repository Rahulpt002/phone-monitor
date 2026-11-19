import type { FastifyInstance } from 'fastify';
import type { PublicConfig } from '@hsm/shared';
import type { AppContext } from '../context.js';

/** GET /healthz (root, for Render's health check and an uptime monitor). */
export function registerHealthRoute(app: FastifyInstance, ctx: AppContext): void {
  app.get('/healthz', async (_req, reply) => {
    const dbOk = await ctx.db.ping();
    const body = {
      ok: dbOk,
      version: ctx.config.version,
      uptime_s: Math.round((ctx.clock.now() - ctx.startedAt) / 1000),
      db: dbOk ? 'ok' : 'error',
    };
    return reply.code(dbOk ? 200 : 503).send(body);
  });
}

/** GET /api/config (public). */
export function registerConfigRoute(app: FastifyInstance, ctx: AppContext): void {
  app.get('/config', async (): Promise<PublicConfig> => ({
    version: ctx.config.version,
    telegram_configured: ctx.telegram.configured,
    push_configured: ctx.push.configured,
  }));
}
