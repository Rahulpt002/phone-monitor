import { EventEmitter } from 'node:events';
import WebSocket from 'ws';
import { PROTOCOL } from '@hsm/shared';
import type { Logger } from './log.js';
import type { Random } from './rng.js';
import { formatDuration } from './util.js';

export interface ConnectionOptions {
  url: string;
  token: string;
  log: Logger;
  rng: Random;
  /** First reconnect delay; doubles up to `backoffMaxMs` (docs/protocol.md 3.1: 2 s .. 60 s). */
  backoffBaseMs?: number;
  backoffMaxMs?: number;
  /** A connection that stayed up this long resets the backoff. */
  stableMs?: number;
  /** WebSocket ping to detect dead sockets (OkHttp pingInterval on the real Agent). */
  pingIntervalMs?: number;
  handshakeTimeoutMs?: number;
  userAgent?: string;
}

export interface CloseInfo {
  code: number;
  reason: string;
  /** The socket had opened before it closed. */
  wasOpen: boolean;
  /** Closed by us (link down, stop). */
  local: boolean;
}

/** Close codes that end the connection for good (docs/protocol.md 7). */
export type FatalCode = 4001 | 4002;

/**
 * The Agent's socket to WS /ws/agent: Bearer token on the upgrade, exponential backoff with
 * jitter, no reconnect while the link is down ("enabled" false), an immediate reconnect when the
 * link returns (the Android network-available callback), and a stop on 4001 / 4002.
 *
 * Events: 'open', 'message' (parsed object), 'close' (CloseInfo), 'fatal' (FatalCode).
 */
export class Connection extends EventEmitter {
  private ws: WebSocket | null = null;
  private enabled = false;
  private stopped = false;
  private attempt = 0;
  private retryTimer: NodeJS.Timeout | null = null;
  private pingTimer: NodeJS.Timeout | null = null;
  private awaitingPong = false;
  private openedAt = 0;
  private readonly opts: Required<Omit<ConnectionOptions, 'log' | 'rng'>> & Pick<ConnectionOptions, 'log' | 'rng'>;

  constructor(opts: ConnectionOptions) {
    super();
    this.opts = {
      backoffBaseMs: 2000,
      backoffMaxMs: 60_000,
      stableMs: 60_000,
      pingIntervalMs: 20_000,
      handshakeTimeoutMs: 20_000,
      userAgent: 'hsm-agent-sim',
      ...opts,
    };
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  get url(): string {
    return this.opts.url;
  }

  /** Link up: connect now. Link down: close the socket and stop retrying until it is back. */
  setEnabled(on: boolean): void {
    if (this.stopped || on === this.enabled) return;
    this.enabled = on;
    this.clearRetry();
    if (on) {
      this.attempt = 0;
      this.connect();
    } else {
      this.closeSocket(1001, 'link down');
    }
  }

  stop(code = 1000, reason = 'agent stopped'): void {
    this.stopped = true;
    this.enabled = false;
    this.clearRetry();
    this.closeSocket(code, reason);
  }

  send(msg: object): boolean {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    const text = JSON.stringify(msg);
    if (Buffer.byteLength(text, 'utf8') > PROTOCOL.max_message_bytes) {
      this.opts.log.warn(`not sending a ${(msg as { t?: string }).t ?? '?'} frame larger than ${PROTOCOL.max_message_bytes} bytes`);
      return false;
    }
    ws.send(text);
    return true;
  }

  private connect(): void {
    if (this.stopped || !this.enabled || this.ws) return;
    const { log } = this.opts;
    log.debug('conn', `connecting to ${this.opts.url}`);
    const ws = new WebSocket(this.opts.url, {
      headers: { Authorization: `Bearer ${this.opts.token}`, 'User-Agent': this.opts.userAgent },
      handshakeTimeout: this.opts.handshakeTimeoutMs,
      maxPayload: 4 * PROTOCOL.max_message_bytes,
    });
    this.ws = ws;
    let httpStatus: number | null = null;
    let lastError: string | null = null;

    // The Server may refuse the upgrade itself (401/403) instead of accepting and closing with 4001.
    ws.on('unexpected-response', (_req, res) => {
      httpStatus = res.statusCode ?? null;
      res.resume();
      ws.terminate();
    });
    ws.on('open', () => {
      if (this.ws !== ws) return;
      this.openedAt = Date.now();
      this.startPing(ws);
      this.emit('open');
    });
    ws.on('message', (data, isBinary) => {
      if (this.ws !== ws || isBinary) return;
      let msg: unknown;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        log.warn('ignored a frame that is not JSON');
        return;
      }
      this.emit('message', msg);
    });
    ws.on('pong', () => {
      this.awaitingPong = false;
    });
    ws.on('error', (err) => {
      lastError = err.message;
      log.debug('conn', `socket error: ${err.message}`);
    });
    ws.on('close', (code, reasonBuf) => {
      if (this.ws !== ws) return; // a socket we already detached (link down / stop)
      this.ws = null;
      this.stopPing();
      const wasOpen = this.openedAt > 0;
      const upMs = wasOpen ? Date.now() - this.openedAt : 0;
      this.openedAt = 0;
      const reason = reasonBuf.toString();
      if (code === 4001 || httpStatus === 401 || httpStatus === 403) {
        this.stopped = true;
        this.emit('fatal', 4001 satisfies FatalCode);
        return;
      }
      if (code === 4002) {
        this.stopped = true;
        this.emit('fatal', 4002 satisfies FatalCode);
        return;
      }
      if (!wasOpen) {
        const why = httpStatus ? `HTTP ${httpStatus}` : (lastError ?? `code ${code}`);
        log.warn(`cannot reach ${this.opts.url} (${why})`);
      }
      this.emit('close', { code, reason, wasOpen, local: false } satisfies CloseInfo);
      if (wasOpen && upMs >= this.opts.stableMs) this.attempt = 0;
      this.scheduleReconnect();
    });
  }

  private scheduleReconnect(): void {
    if (this.stopped || !this.enabled || this.retryTimer) return;
    const base = Math.min(this.opts.backoffMaxMs, this.opts.backoffBaseMs * 2 ** this.attempt);
    this.attempt = Math.min(this.attempt + 1, 20);
    const wait = Math.round(base * (0.8 + 0.4 * this.opts.rng.next()));
    this.opts.log.info('conn', `reconnecting in ${formatDuration(wait / 1000)}`);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.connect();
    }, wait);
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private closeSocket(code: number, reason: string): void {
    const ws = this.ws;
    if (!ws) return;
    // Detach first so this socket's close event does not schedule a reconnect.
    this.ws = null;
    this.stopPing();
    const wasOpen = this.openedAt > 0;
    this.openedAt = 0;
    if (ws.readyState === WebSocket.OPEN) {
      // A graceful close flushes frames already queued (e.g. a cmd_result sent just before).
      ws.close(code, reason);
      const kill = setTimeout(() => ws.terminate(), 2000);
      kill.unref?.();
      ws.once('close', () => clearTimeout(kill));
    } else {
      ws.terminate();
    }
    this.emit('close', { code, reason, wasOpen, local: true } satisfies CloseInfo);
  }

  private startPing(ws: WebSocket): void {
    this.stopPing();
    this.awaitingPong = false;
    this.pingTimer = setInterval(() => {
      if (this.awaitingPong) {
        this.opts.log.warn(`no pong within ${formatDuration(this.opts.pingIntervalMs / 1000)}: dropping the dead socket`);
        ws.terminate();
        return;
      }
      this.awaitingPong = true;
      try {
        ws.ping();
      } catch {
        // Socket already closing; the close handler takes over.
      }
    }, this.opts.pingIntervalMs);
  }

  private stopPing(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }
}
