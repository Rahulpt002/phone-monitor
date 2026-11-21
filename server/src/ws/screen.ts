import type { FastifyRequest } from 'fastify';
import type { WebSocket } from 'ws';
import type { AppContext } from '../context.js';
import { SCREEN_CLOSE } from '../services/screen.js';
import { parseBearer, sha256Hex } from '../util.js';

/** WS /ws/screen/agent?session=<id> (docs/protocol.md section 8). Device token on the upgrade. */
export function handleScreenAgentSocket(ctx: AppContext, socket: WebSocket, req: FastifyRequest): void {
  const token = parseBearer(req.headers.authorization);
  const dev = token ? ctx.devices.byTokenHash(sha256Hex(token)) : undefined;
  if (!dev) {
    ctx.log.warn({ ip: req.ip }, 'screen relay connection with an invalid device token');
    socket.close(SCREEN_CLOSE.unauthorized, 'unauthorized');
    return;
  }
  const session = (req.query as { session?: unknown } | undefined)?.session;
  if (typeof session !== 'string' || session.length === 0 || session.length > 64) {
    socket.close(SCREEN_CLOSE.protocol, 'session query parameter required');
    return;
  }
  ctx.screen.attachAgent(dev.id, session, socket);
}

/** WS /ws/screen/view: first message `{t:"screen_auth", token, session_id}`. */
export function handleScreenViewSocket(ctx: AppContext, socket: WebSocket): void {
  ctx.screen.attachViewer(socket);
}
