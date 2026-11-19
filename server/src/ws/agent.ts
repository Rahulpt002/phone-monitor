import { PROTOCOL, type FinderLive } from '@hsm/shared';
import type { FastifyRequest } from 'fastify';
import type { RawData, WebSocket } from 'ws';
import type { AppContext } from '../context.js';
import { isBufferedType, storeBuffered } from '../services/ingest.js';
import { iso, isNum, isPlainObject, parseBearer, sha256Hex, type Plain } from '../util.js';
import { sendJson, type AgentConn } from './hub.js';

/** Malformed frames tolerated per connection before closing with 4003. */
export const MAX_BAD_MESSAGES = 10;
/** Re-push settings when a heartbeat reports an old version, at most this often. */
const SETTINGS_REPUSH_MS = 60_000;

/** WS /ws/agent (docs/protocol.md section 3). */
export function handleAgentSocket(ctx: AppContext, socket: WebSocket, req: FastifyRequest): void {
  const token = parseBearer(req.headers.authorization);
  const dev = token ? ctx.devices.byTokenHash(sha256Hex(token)) : undefined;
  if (!dev) {
    ctx.log.warn({ ip: req.ip }, 'agent connection with an invalid device token');
    sendJson(socket, { t: 'error', code: 'unauthorized', message: 'invalid device token' });
    socket.close(4001, 'unauthorized');
    return;
  }

  const conn: AgentConn = ctx.hub.registerAgent(dev.id, socket);
  const now = ctx.clock.now();
  ctx.alerts.onAgentActivity(dev);
  ctx.devices.touch(dev);
  dev.connectedSince = now;
  conn.lastSettingsPush = now;
  const rates = ctx.hub.computeRates(dev.id);
  conn.rates = rates;
  sendJson(socket, {
    t: 'hello',
    server_time: iso(now),
    device_id: dev.id,
    settings: dev.settings,
    settings_version: dev.settingsVersion,
    rates,
  });
  ctx.devices.broadcastStatus(dev);
  ctx.log.info({ device_id: dev.id, ip: req.ip }, 'agent connected');
  ctx.commands.deliverQueued(dev.id).catch((err) => ctx.log.error({ err }, 'failed to deliver queued commands'));

  const bad = (reason: string) => {
    conn.badMessages++;
    sendJson(socket, { t: 'error', code: 'bad_message', message: reason });
    if (conn.badMessages > MAX_BAD_MESSAGES) {
      ctx.log.warn({ device_id: dev.id }, 'agent sent too many bad messages; closing');
      socket.close(4003, 'protocol error');
    }
  };

  const enqueue = (items: unknown[]) => {
    conn.queue = conn.queue.then(async () => {
      if (conn.closed || !ctx.devices.get(dev.id)) return;
      try {
        const r = await storeBuffered(ctx, dev, items);
        if (r.ackSeq !== null) sendJson(socket, { t: 'ack', seq: r.ackSeq });
        if (r.skipped) ctx.log.warn({ device_id: dev.id, skipped: r.skipped }, 'skipped malformed buffered messages');
      } catch (err) {
        // Not acknowledged: the Agent keeps the rows and sends them again.
        ctx.log.error({ err, device_id: dev.id, items: items.length }, 'failed to store agent messages');
      }
    });
  };

  const onHeartbeat = (msg: Plain) => {
    if (isNum(msg.outbox)) dev.outbox = Math.max(0, Math.round(msg.outbox));
    dev.dirty = true;
    const t = ctx.clock.now();
    if (
      isNum(msg.settings_version) &&
      msg.settings_version !== dev.settingsVersion &&
      t - conn.lastSettingsPush >= SETTINGS_REPUSH_MS
    ) {
      conn.lastSettingsPush = t;
      sendJson(socket, { t: 'settings', settings: dev.settings, version: dev.settingsVersion });
    }
    ctx.devices.broadcastStatus(dev);
  };

  socket.on('message', (data: RawData, isBinary: boolean) => {
    if (conn.closed || !ctx.devices.get(dev.id)) return;
    if (isBinary) return bad('binary frames are not supported');
    let msg: unknown;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return bad('invalid JSON');
    }
    if (!isPlainObject(msg) || typeof msg.t !== 'string') return bad('expected a JSON object with a "t" field');

    // Any message proves the phone is alive (protocol 6).
    ctx.alerts.onAgentActivity(dev);
    ctx.devices.touch(dev);

    switch (msg.t) {
      case 'heartbeat':
        onHeartbeat(msg);
        return;
      case 'probe':
        if (typeof msg.id === 'string' && msg.id.length <= 100) {
          sendJson(socket, { t: 'probe_ack', id: msg.id, server_time: iso(ctx.clock.now()) });
        } else bad('probe needs an id');
        return;
      case 'finder_live':
        if (isPlainObject(msg.live)) {
          ctx.hub.broadcastDevice(dev.id, { t: 'finder_live', device_id: dev.id, live: msg.live as unknown as FinderLive });
        }
        return;
      case 'batch':
        if (!Array.isArray(msg.items)) return bad('batch.items must be an array');
        if (msg.items.length > PROTOCOL.max_batch_items) {
          return bad(`batch has more than ${PROTOCOL.max_batch_items} items`);
        }
        if (msg.items.length) enqueue(msg.items);
        return;
      default:
        if (isBufferedType(msg.t)) enqueue([msg]);
      // Unknown types are ignored (forward compatibility).
    }
  });

  socket.on('close', (code: number) => {
    if (ctx.hub.unregisterAgent(conn)) {
      dev.connectedSince = null;
      dev.dirty = true;
      ctx.devices.broadcastStatus(dev);
      ctx.log.info({ device_id: dev.id, code }, 'agent disconnected');
    }
  });
}
