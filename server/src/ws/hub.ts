import type { Rates, ServerToAgent, ServerToDashboard, UserInfo } from '@hsm/shared';
import type { WebSocket } from 'ws';
import type { AppContext } from '../context.js';

const WS_OPEN = 1;
/** A Dashboard this far behind is dropped; it reconnects and gets a fresh snapshot. */
const DASH_MAX_BUFFERED = 4 * 1024 * 1024;

export interface AgentConn {
  deviceId: string;
  socket: WebSocket;
  connectedAt: number;
  /** Rates last sent to this Agent (in hello or a rates message). */
  rates: Rates | null;
  /** Buffered messages are stored strictly in arrival order, one at a time. */
  queue: Promise<void>;
  badMessages: number;
  lastSettingsPush: number;
  closed: boolean;
  /** A ws ping is outstanding (liveness check, see Hub.pingAll). */
  awaitingPong: boolean;
}

export interface DashSubscription {
  deviceId: string | null;
  finder: boolean;
}

export interface DashConn {
  id: number;
  socket: WebSocket;
  user: UserInfo | null;
  sessionHash: string | null;
  sub: DashSubscription | null;
  badMessages: number;
  queue: Promise<void>;
  awaitingPong: boolean;
}

export interface BroadcastOptions {
  /** Only admins receive it (SMS). */
  adminOnly?: boolean;
  /** Variant for viewers; null = do not send to viewers. Omitted = same message. */
  viewerMsg?: ServerToDashboard | null;
}

export function sendJson(socket: WebSocket, msg: unknown): boolean {
  if (socket.readyState !== WS_OPEN) return false;
  socket.send(JSON.stringify(msg));
  return true;
}

/**
 * Live connections: at most one Agent socket per device and any number of Dashboard sockets.
 * Also decides the Agent's sending rates from which Dashboards are watching (protocol 5.4).
 */
export class Hub {
  readonly agents = new Map<string, AgentConn>();
  readonly dashboards = new Set<DashConn>();
  private nextDashId = 1;

  constructor(private ctx: AppContext) {}

  // --- agents ---------------------------------------------------------------------------------

  registerAgent(deviceId: string, socket: WebSocket): AgentConn {
    const old = this.agents.get(deviceId);
    if (old) {
      old.closed = true;
      sendJson(old.socket, { t: 'error', code: 'replaced', message: 'replaced by a newer connection' });
      old.socket.close(4002, 'replaced');
    }
    const conn: AgentConn = {
      deviceId,
      socket,
      connectedAt: this.ctx.clock.now(),
      rates: null,
      queue: Promise.resolve(),
      badMessages: 0,
      lastSettingsPush: 0,
      closed: false,
      awaitingPong: false,
    };
    socket.on('pong', () => {
      conn.awaitingPong = false;
    });
    this.agents.set(deviceId, conn);
    return conn;
  }

  /** Returns true when `conn` was the current connection of its device. */
  unregisterAgent(conn: AgentConn): boolean {
    conn.closed = true;
    if (this.agents.get(conn.deviceId) !== conn) return false;
    this.agents.delete(conn.deviceId);
    return true;
  }

  agentConnected(deviceId: string): boolean {
    const c = this.agents.get(deviceId);
    return !!c && !c.closed && c.socket.readyState === WS_OPEN;
  }

  sendToAgent(deviceId: string, msg: ServerToAgent): boolean {
    const c = this.agents.get(deviceId);
    if (!c || c.closed) return false;
    return sendJson(c.socket, msg);
  }

  closeAgent(deviceId: string, code: number, reason: string, error?: ServerToAgent): void {
    const c = this.agents.get(deviceId);
    if (!c) return;
    if (error) sendJson(c.socket, error);
    c.socket.close(code, reason);
  }

  /** Drops a socket that stopped delivering messages without closing (half-open TCP). */
  terminateAgent(deviceId: string): void {
    const c = this.agents.get(deviceId);
    if (!c) return;
    this.unregisterAgent(c);
    c.socket.terminate();
  }

  // --- dashboards -----------------------------------------------------------------------------

  addDashboard(socket: WebSocket): DashConn {
    const conn: DashConn = {
      id: this.nextDashId++,
      socket,
      user: null,
      sessionHash: null,
      sub: null,
      badMessages: 0,
      queue: Promise.resolve(),
      awaitingPong: false,
    };
    socket.on('pong', () => {
      conn.awaitingPong = false;
    });
    this.dashboards.add(conn);
    return conn;
  }

  removeDashboard(conn: DashConn): void {
    if (this.dashboards.delete(conn) && conn.sub) this.refreshRates();
  }

  private watches(c: DashConn, deviceId: string): boolean {
    return !!c.user && !!c.sub && (c.sub.deviceId === null || c.sub.deviceId === deviceId);
  }

  watchers(deviceId: string): DashConn[] {
    return [...this.dashboards].filter((c) => this.watches(c, deviceId));
  }

  computeRates(deviceId: string): Rates {
    const dev = this.ctx.devices.get(deviceId);
    const t = dev?.settings.telemetry;
    const watchers = this.watchers(deviceId);
    const fastActive = t?.fast_active_s ?? 5;
    const fastIdle = t?.fast_idle_s ?? 60;
    return {
      fast_s: watchers.length > 0 ? fastActive : fastIdle,
      mirror: watchers.some((w) => w.sub?.finder === true),
    };
  }

  /** Recomputes rates and sends `rates` to every Agent (or one) whose answer changed. */
  refreshRates(deviceId?: string): void {
    const conns = deviceId ? [this.agents.get(deviceId)].filter((c): c is AgentConn => !!c) : [...this.agents.values()];
    for (const c of conns) {
      if (c.closed) continue;
      const r = this.computeRates(c.deviceId);
      if (c.rates && c.rates.fast_s === r.fast_s && c.rates.mirror === r.mirror) continue;
      c.rates = r;
      sendJson(c.socket, { t: 'rates', ...r });
    }
  }

  private sendDash(c: DashConn, data: string): void {
    if (c.socket.readyState !== WS_OPEN) return;
    if (c.socket.bufferedAmount > DASH_MAX_BUFFERED) {
      this.ctx.log.warn({ dash: c.id }, 'dashboard socket too far behind; dropping it');
      c.socket.terminate();
      return;
    }
    c.socket.send(data);
  }

  private deliver(targets: Iterable<DashConn>, msg: ServerToDashboard, opts: BroadcastOptions): void {
    let full: string | null = null;
    let viewer: string | null = null;
    for (const c of targets) {
      if (!c.user) continue;
      const isAdmin = c.user.role === 'admin';
      if (!isAdmin && opts.adminOnly) continue;
      if (isAdmin || opts.viewerMsg === undefined) {
        full ??= JSON.stringify(msg);
        this.sendDash(c, full);
      } else if (opts.viewerMsg !== null) {
        viewer ??= JSON.stringify(opts.viewerMsg);
        this.sendDash(c, viewer);
      }
    }
  }

  /** To every authenticated Dashboard subscribed to this device (or to all devices). */
  broadcastDevice(deviceId: string, msg: ServerToDashboard, opts: BroadcastOptions = {}): void {
    this.deliver(
      [...this.dashboards].filter((c) => this.watches(c, deviceId)),
      msg,
      opts,
    );
  }

  /** To every authenticated Dashboard, subscribed or not (alerts). */
  broadcastAll(msg: ServerToDashboard, opts: BroadcastOptions = {}): void {
    this.deliver(this.dashboards, msg, opts);
  }

  closeDashboards(pred: (c: DashConn) => boolean, reason: string): void {
    for (const c of [...this.dashboards]) {
      if (!pred(c)) continue;
      this.dashboards.delete(c);
      c.socket.close(4001, reason.slice(0, 100));
    }
    this.refreshRates();
  }

  /**
   * Liveness check every 30 s: a socket that did not answer the previous ping is a dead (half-open)
   * connection and is terminated; its close handler then cleans up. Browsers and OkHttp answer
   * ping frames automatically, even when a background tab throttles its timers.
   */
  pingAll(): void {
    const conns: { socket: WebSocket; awaitingPong: boolean }[] = [...this.agents.values(), ...this.dashboards];
    for (const c of conns) {
      if (c.socket.readyState !== WS_OPEN) continue;
      if (c.awaitingPong) {
        c.socket.terminate();
        continue;
      }
      c.awaitingPong = true;
      c.socket.ping();
    }
  }

  /** Graceful shutdown: 1001 "going away" tells clients to reconnect later. */
  closeAll(code = 1001, reason = 'server shutting down'): void {
    for (const c of this.agents.values()) {
      c.closed = true;
      c.socket.close(code, reason);
    }
    this.agents.clear();
    for (const c of this.dashboards) c.socket.close(code, reason);
    this.dashboards.clear();
  }
}
