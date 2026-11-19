import type { FastifyInstance } from 'fastify';
import type { DeviceInfo, DeviceSnapshot, DeviceSummary, PairCodeResponse, PairResponse } from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { notFound, parseOrThrow } from '../errors.js';
import { DeviceInfoSchema } from '../services/devices.js';
import { baseUrl, query, qString, RATE_PAIR } from './helpers.js';

const PairCodeSchema = z.object({ name: z.string().max(80).optional() });
const PairSchema = z.object({ code: z.string().min(1).max(32), device: DeviceInfoSchema });
const RenameSchema = z.object({ name: z.string().min(1).max(80) });
const UnpairSchema = z.object({ password: z.string().max(256).optional() });

/** The device named in the path; never falls back to the first device. */
function deviceFromPath(ctx: AppContext, params: unknown) {
  const id = String((params as { id?: string }).id ?? '');
  const dev = id ? ctx.devices.get(id) : undefined;
  if (!dev) throw notFound(`Device ${id} not found`);
  return dev;
}

export function registerDeviceRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  app.post('/pair/codes', { preHandler: g.adminWrite }, async (req, reply) => {
    const b = parseOrThrow(PairCodeSchema, req.body ?? {}, 'request');
    const res: PairCodeResponse = await ctx.devices.createPairCode(
      b.name?.trim() || null,
      authOf(req).user.username,
      baseUrl(ctx, req),
    );
    return reply.code(201).send(res);
  });

  // Public: called by the Agent with the one-time code.
  app.post('/pair', { config: { rateLimit: RATE_PAIR } }, async (req): Promise<PairResponse> => {
    const b = parseOrThrow(PairSchema, req.body, 'pairing request');
    return ctx.devices.pair(b.code, b.device as DeviceInfo, baseUrl(ctx, req));
  });

  app.get('/devices', { preHandler: g.user }, async (): Promise<DeviceSummary[]> =>
    ctx.devices.all().map((d) => ctx.devices.summary(d)),
  );

  app.get('/device', { preHandler: g.user }, async (req): Promise<DeviceSnapshot> => {
    const dev = ctx.devices.resolve(qString(query(req), 'device_id', 64));
    return ctx.devices.snapshot(dev);
  });

  app.patch('/devices/:id', { preHandler: g.adminWrite }, async (req): Promise<DeviceSummary> => {
    const b = parseOrThrow(RenameSchema, req.body, 'request');
    return ctx.devices.rename(deviceFromPath(ctx, req.params), b.name);
  });

  app.delete('/devices/:id', { preHandler: g.adminWrite }, async (req, reply) => {
    const b = parseOrThrow(UnpairSchema, req.body ?? {}, 'request');
    const a = authOf(req);
    const dev = deviceFromPath(ctx, req.params);
    await ctx.auth.reauth(a.user, b.password, req.ip);
    await ctx.devices.unpair(dev);
    ctx.log.info({ by: a.user.username, device_id: dev.id }, 'device unpaired');
    return reply.code(204).send();
  });
}
