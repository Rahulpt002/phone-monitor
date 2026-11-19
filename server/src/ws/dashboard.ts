import type { FastifyRequest } from 'fastify';
import type { RawData, WebSocket } from 'ws';
import type { AppContext } from '../context.js';
import { isPlainObject, type Plain } from '../util.js';
import { sendJson } from './hub.js';

export const DASH_AUTH_TIMEOUT_MS = 10_000;
const MAX_BAD_MESSAGES = 10;
const WS_OPEN = 1;

/**
 * WS /ws/dashboard (docs/protocol.md section 5).
 *
 * Close codes: 4001 + auth_failed only when the session definitely does not exist, has expired or
 * has no TOTP yet (the client signs out or goes to enrollment). When the session lookup fails or
 * does not finish within DASH_AUTH_TIMEOUT_MS (database trouble, e.g. every Dashboard reconnecting
 * after a deploy), or no auth arrives in time, 1013 "try again later": the client keeps its token
 * and reconnects with backoff.
 */
export function handleDashboardSocket(ctx: AppContext, socket: WebSocket, _req: FastifyRequest): void {
  const conn = ctx.hub.addDashboard(socket);
  const timer = setTimeout(() => {
    if (conn.user) return;
    socket.close(1013, 'auth timeout');
  }, DASH_AUTH_TIMEOUT_MS);
  timer.unref?.();

  const fail = (message: string) => {
    clearTimeout(timer);
    sendJson(socket, { t: 'auth_failed', message });
    socket.close(4001, 'unauthorized');
  };

  const handle = async (msg: Plain) => {
    switch (msg.t) {
      case 'auth': {
        if (conn.user) return;
        if (typeof msg.token !== 'string') return fail('invalid or expired session');
        let info;
        try {
          info = await ctx.auth.authenticate(msg.token);
        } catch (err) {
          ctx.log.warn({ err }, 'dashboard session lookup failed; asking the client to retry');
          clearTimeout(timer);
          socket.close(1013, 'try again later');
          return;
        }
        // Timed out (1013) or closed by the client while the lookup ran.
        if (socket.readyState !== WS_OPEN) return;
        if (!info) return fail('invalid or expired session');
        if (ctx.auth.needsTotpEnrollment(info.user)) return fail('totp_enrollment_required');
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
