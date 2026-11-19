import type { FastifyInstance } from 'fastify';
import type {
  CommandRecord,
  FinderSpotsResponse,
  SettingsResponse,
  SmsRecord,
  StoredEvent,
  TelemetryHistoryResponse,
} from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { badRequest, parseOrThrow } from '../errors.js';
import { redactEventForViewer } from '../services/records.js';
import { qInt, qString, qTime, query } from './helpers.js';

const SettingsPutSchema = z.object({
  device_id: z.string().max(64).optional(),
  settings: z.record(z.string(), z.unknown()),
});

export function registerDataRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  // --- telemetry history --------------------------------------------------------------------
  app.get('/telemetry', { preHandler: g.user }, async (req): Promise<TelemetryHistoryResponse> => {
    const q = query(req);
    const dev = ctx.devices.resolve(qString(q, 'device_id', 64));
    const metric = qString(q, 'metric', 64);
    if (!metric) throw badRequest('metric is required');
    const to = qTime(q, 'to') ?? ctx.clock.now();
    const from = qTime(q, 'from') ?? to - 24 * 3_600_000;
    const res = qString(q, 'res', 8) ?? 'auto';
    if (res !== 'auto' && res !== 'raw' && res !== '1m') throw badRequest('res must be auto, raw or 1m');
    return ctx.telemetry.history(dev, metric, from, to, res);
  });

  // --- commands -----------------------------------------------------------------------------
  app.post('/commands', { preHandler: g.adminWrite }, async (req, reply) => {
    const rec = await ctx.commands.create(authOf(req), req.body, req.ip);
    return reply.code(201).send(rec);
  });

  app.post('/commands/:id/keep', { preHandler: g.adminWrite }, async (req): Promise<CommandRecord> => {
    const id = String((req.params as { id?: string }).id ?? '');
    const rec = await ctx.commands.keep(id);
    ctx.log.info({ command_id: id, by: authOf(req).user.username }, 'auto-revert keep requested');
    return rec;
  });

  app.get('/commands', { preHandler: g.user }, async (req): Promise<CommandRecord[]> => {
    const q = query(req);
    const dev = ctx.devices.resolve(qString(q, 'device_id', 64));
    const viewer = authOf(req).user.role !== 'admin';
    return ctx.commands.list(dev, qInt(q, 'limit', 50, 1, 500), qTime(q, 'before') ?? null, viewer);
  });

  // --- events -------------------------------------------------------------------------------
  app.get('/events', { preHandler: g.user }, async (req): Promise<StoredEvent[]> => {
    const q = query(req);
    const dev = ctx.devices.resolve(qString(q, 'device_id', 64));
    const kind = qString(q, 'kind', 500);
    const events = await ctx.records.listEvents(dev, {
      from: qTime(q, 'from') ?? null,
      to: qTime(q, 'to') ?? null,
      before: qTime(q, 'before') ?? null,
      kinds: kind ? kind.split(',').map((k) => k.trim()).filter(Boolean).slice(0, 30) : undefined,
      limit: qInt(q, 'limit', 100, 1, 1000),
    });
    return authOf(req).user.role === 'admin' ? events : events.map(redactEventForViewer);
  });

  // --- SMS (admins only; viewers never read messages) -----------------------------------------
  app.get('/sms', { preHandler: g.admin }, async (req): Promise<SmsRecord[]> => {
    const q = query(req);
    const dev = ctx.devices.resolve(qString(q, 'device_id', 64));
    return ctx.records.listSms(dev, qInt(q, 'limit', 100, 1, 1000), qTime(q, 'before') ?? null);
  });

  // --- settings -----------------------------------------------------------------------------
  app.get('/settings', { preHandler: g.user }, async (req): Promise<SettingsResponse> => {
    const dev = ctx.devices.resolve(qString(query(req), 'device_id', 64));
    return ctx.settings.get(dev);
  });

  app.put('/settings', { preHandler: g.adminWrite }, async (req): Promise<SettingsResponse> => {
    const b = parseOrThrow(SettingsPutSchema, req.body, 'settings update');
    const dev = ctx.devices.resolve(b.device_id);
    const res = await ctx.settings.update(dev, b.settings);
    ctx.log.info({ device_id: dev.id, version: res.version, by: authOf(req).user.username }, 'settings updated');
    return res;
  });

  // --- finder -------------------------------------------------------------------------------
  app.get('/finder/spots', { preHandler: g.user }, async (req): Promise<FinderSpotsResponse> => {
    const dev = ctx.devices.resolve(qString(query(req), 'device_id', 64));
    return ctx.finder.list(dev);
  });
}
