import type { FastifyInstance } from 'fastify';
import type { AlertRecord } from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { ApiHttpError, parseOrThrow } from '../errors.js';
import { qInt, qString, qTime, query, userAgent } from './helpers.js';

const SubscribeSchema = z.object({ subscription: z.unknown() });
const UnsubscribeSchema = z.object({ endpoint: z.string().min(1).max(2000) });

export function registerAlertRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  app.get('/alerts', { preHandler: g.user }, async (req): Promise<AlertRecord[]> => {
    const q = query(req);
    const deviceId = qString(q, 'device_id', 64) ?? null;
    const viewer = authOf(req).user.role !== 'admin';
    return ctx.alerts.list(deviceId, qInt(q, 'limit', 100, 1, 1000), qTime(q, 'before') ?? null, viewer);
  });

  app.post('/alerts/test', { preHandler: g.adminWrite, config: { rateLimit: { max: 5, timeWindow: 60_000 } } }, async (req) => {
    return ctx.alerts.test(authOf(req).user.username);
  });

  app.get('/push/vapid', { preHandler: g.user }, async () => {
    const key = ctx.push.vapidPublicKey;
    if (!key) throw new ApiHttpError(404, 'not_found', 'Web Push is not configured');
    return { public_key: key };
  });

  app.post('/push/subscribe', { preHandler: g.user }, async (req, reply) => {
    const b = parseOrThrow(SubscribeSchema, req.body, 'request');
    const a = authOf(req);
    await ctx.push.subscribe(a.user.id, b.subscription, userAgent(req), a.sessionHash);
    return reply.code(204).send();
  });

  // Native Android app (Firebase Cloud Messaging), tied to the signed-in session.
  app.post('/push/fcm/register', { preHandler: g.user }, async (req, reply) => {
    const a = authOf(req);
    await ctx.fcm.register(a.user.id, a.sessionHash, req.body);
    return reply.code(204).send();
  });

  app.post('/push/fcm/unregister', { preHandler: g.user }, async (req, reply) => {
    await ctx.fcm.unregister(authOf(req).user.id, req.body);
    return reply.code(204).send();
  });

  app.post('/push/unsubscribe', { preHandler: g.user }, async (req, reply) => {
    const b = parseOrThrow(UnsubscribeSchema, req.body, 'request');
    await ctx.push.unsubscribe(b.endpoint);
    return reply.code(204).send();
  });
}
