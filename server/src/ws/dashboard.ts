import type { FastifyRequest } from 'fastify';
import type { RawData, WebSocket } from 'ws';
import type { AppContext } from '../context.js';
import { isPlainObject, type Plain } from '../util.js';
import { sendJson } from './hub.js';

export const DASH_AUTH_TIMEOUT_MS = 10_000;
const MAX_BAD_MESSAGES = 10;

/** WS /ws/dashboard (docs/protocol.md section 5). */
export function handleDashboardSocket(ctx: AppContext, socket: WebSocket, _req: FastifyRequest): void {
  const conn = ctx.hub.addDashboard(socket);
  const timer = setTimeout(() => {
    if (conn.user) return;
    sendJson(socket, { t: 'auth_failed', message: 'authentication timeout' });
    socket.close(4001, 'auth timeout');
  }, DASH_AUTH_TIMEOUT_MS);
  timer.unref?.();

  const fail = (message: string) => {
    sendJson(socket, { t: 'auth_failed', message });
    socket.close(4001, 'unauthorized');
  };

  const handle = async (msg: Plain) => {
    switch (msg.t) {
      case 'auth': {
        if (conn.user) return;
        const info = typeof msg.token === 'string' ? await ctx.auth.authenticate(msg.token) : null;
        if (!info) return fail('invalid or expired session');
        clearTimeout(timer);
        conn.user = ctx.auth.userInfo(info.user);
        conn.sessionHash = info.sessionHash;
        sendJson(socket, { t: 'auth_ok', user: conn.user });
        return;
      }
      case 'ping':
        sendJson(socket, { t: 'pong' });
        return;
      case 'subscribe': {
        if (!conn.user) return fail('authenticate first');
        const deviceId = typeof msg.device_id === 'string' && msg.device_id ? msg.device_id : null;
        conn.sub = { deviceId, finder: msg.finder === true };
        const devices = deviceId ? [ctx.devices.get(deviceId)].filter((d) => !!d) : ctx.devices.all();
        for (const d of devices) sendJson(socket, { t: 'snapshot', device: ctx.devices.snapshot(d) });
        ctx.hub.refreshRates();
        return;
      }
      default:
      // Unknown types are ignored (forward compatibility).
    }
  };

  socket.on('message', (data: RawData, isBinary: boolean) => {
    let msg: unknown = null;
    if (!isBinary) {
      try {
        msg = JSON.parse(data.toString());
      } catch {
        msg = null;
      }
    }
    if (!isPlainObject(msg) || typeof msg.t !== 'string') {
      conn.badMessages++;
      if (conn.badMessages > MAX_BAD_MESSAGES) socket.close(4003, 'protocol error');
      return;
    }
    const m = msg;
    conn.queue = conn.queue.then(() => handle(m)).catch((err) => ctx.log.error({ err }, 'dashboard message failed'));
  });

  socket.on('close', () => {
    clearTimeout(timer);
    ctx.hub.removeDashboard(conn);
  });
}
