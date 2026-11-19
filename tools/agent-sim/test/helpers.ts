import { readFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';
import { expect } from 'vitest';
import { WebSocketServer, type WebSocket } from 'ws';
import { DEFAULT_SETTINGS, mergeSettings, type HelloMsg, type Settings } from '@hsm/shared';
import { Agent, type AgentOptions } from '../src/agent.js';
import { Logger } from '../src/log.js';
import { StateStore } from '../src/state.js';

/* eslint-disable @typescript-eslint/no-explicit-any */
export type Json = any;

export function fixture(name: string): Json {
  const url = new URL(`../../../shared/fixtures/${name}`, import.meta.url);
  return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'));
}

/** Objects whose keys are data (interface names, thermal zones), compared by value shape only. */
const MAP_KEYS = new Set(['per_if', 'zones']);

/**
 * Asserts that `actual` has exactly the keys of `expected` (recursively), with matching types
 * where both are non-null. Arrays compare their first elements.
 */
export function expectSameShape(actual: unknown, expected: unknown, path = '$'): void {
  if (expected === null || expected === undefined || actual === null || actual === undefined) return;
  const key = path.split('.').pop() ?? '';
  if (Array.isArray(expected)) {
    expect(Array.isArray(actual), `${path} should be an array`).toBe(true);
    const a = actual as unknown[];
    if (expected.length > 0 && a.length > 0) expectSameShape(a[0], expected[0], `${path}[0]`);
    return;
  }
  if (typeof expected === 'object') {
    expect(typeof actual, `${path} should be an object`).toBe('object');
    const a = actual as Record<string, unknown>;
    const e = expected as Record<string, unknown>;
    if (key === 'capabilities') {
      for (const [name, entry] of Object.entries(a)) {
        expect(typeof (entry as Json).supported, `${path}.${name}.supported`).toBe('boolean');
        for (const k of Object.keys(entry as object)) expect(['supported', 'method', 'note'], `${path}.${name}.${k}`).toContain(k);
      }
      return;
    }
    if (MAP_KEYS.has(key)) {
      const sample = Object.values(e)[0];
      expect(Object.keys(a).length, `${path} should not be empty`).toBeGreaterThan(0);
      for (const [k, v] of Object.entries(a)) expectSameShape(v, sample, `${path}.${k}`);
      return;
    }
    expect(Object.keys(a).sort(), `keys of ${path}`).toEqual(Object.keys(e).sort());
    for (const k of Object.keys(e)) expectSameShape(a[k], e[k], `${path}.${k}`);
    return;
  }
  expect(typeof actual, `type of ${path}`).toBe(typeof expected);
}

/** Settings with short intervals so integration tests run in seconds. */
export function quickSettings(patch: Record<string, unknown> = {}): Settings {
  return mergeSettings(
    mergeSettings(DEFAULT_SETTINGS, { telemetry: { heartbeat_s: 0.3, fast_active_s: 0.4, fast_idle_s: 0.4, slow_s: 0.5, mirror_s: 0.2 } }),
    patch,
  );
}

export function helloMsg(over: Partial<HelloMsg> = {}): HelloMsg {
  return {
    t: 'hello',
    server_time: new Date().toISOString(),
    device_id: 'd_test',
    settings: quickSettings(),
    settings_version: 1,
    rates: { fast_s: 0.4, mirror: false },
    ...over,
  };
}

export function makeAgent(opts: Partial<AgentOptions> = {}, store = new StateStore(null)): Agent {
  return new Agent(
    {
      wsUrl: 'ws://127.0.0.1:9/ws/agent',
      token: 'good-token',
      seed: 7,
      backoffBaseMs: 100,
      backoffMaxMs: 400,
      tickMs: 100,
      ...opts,
    },
    store,
    Logger.silent(),
  );
}

/** Buffered messages of one type currently in the Agent's outbox. */
export function outboxOf(agent: Agent, t: string): Json[] {
  return agent.outbox.peek().filter((m) => m.t === t);
}

export class FakeConn {
  /** Raw frames as received. */
  readonly frames: Json[] = [];
  /** Frames with `batch` items flattened. */
  readonly items: Json[] = [];
  closed = false;
  closeCode: number | null = null;
  private listeners = new Set<() => void>();

  constructor(
    readonly ws: WebSocket,
    readonly auth: string | null,
    public autoAck: boolean,
  ) {
    ws.on('message', (data) => {
      const msg = JSON.parse(String(data));
      this.frames.push(msg);
      const flat: Json[] = msg.t === 'batch' ? msg.items : [msg];
      this.items.push(...flat);
      if (this.autoAck) {
        const seqs = flat.map((m) => m.seq).filter((n): n is number => typeof n === 'number');
        if (seqs.length) this.send({ t: 'ack', seq: Math.max(...seqs) });
      }
      if (msg.t === 'probe') this.send({ t: 'probe_ack', id: msg.id, server_time: new Date().toISOString() });
      this.notify();
    });
    ws.on('close', (code) => {
      this.closed = true;
      this.closeCode = code;
      this.notify();
    });
  }

  send(obj: unknown): void {
    if (this.ws.readyState === this.ws.OPEN) this.ws.send(JSON.stringify(obj));
  }

  waitFor(pred: (m: Json) => boolean, timeoutMs = 5000, what = 'message'): Promise<Json> {
    return this.until(() => this.items.find(pred), timeoutMs, what);
  }

  waitClosed(timeoutMs = 5000): Promise<true> {
    return this.until(() => (this.closed ? true : undefined), timeoutMs, 'close');
  }

  private until<T>(check: () => T | undefined, timeoutMs: number, what: string): Promise<T> {
    return new Promise((resolve, reject) => {
      const first = check();
      if (first !== undefined) {
        resolve(first);
        return;
      }
      const timer = setTimeout(() => {
        this.listeners.delete(listener);
        reject(new Error(`timed out waiting for ${what}`));
      }, timeoutMs);
      const listener = () => {
        const v = check();
        if (v === undefined) return;
        clearTimeout(timer);
        this.listeners.delete(listener);
        resolve(v);
      };
      this.listeners.add(listener);
    });
  }

  private notify(): void {
    for (const l of [...this.listeners]) l();
  }
}

export interface FakeServerOptions {
  token?: string;
  autoAck?: boolean;
  /** hello for the n-th accepted connection (0-based). */
  hello?: (n: number) => HelloMsg;
}

/** In-process stand-in for the Server's /ws/agent endpoint. */
export class FakeServer {
  readonly conns: FakeConn[] = [];
  private accepted = 0;
  private waiters: (() => void)[] = [];

  private constructor(readonly wss: WebSocketServer, opts: FakeServerOptions) {
    wss.on('connection', (ws, req) => {
      const conn = new FakeConn(ws, req.headers.authorization ?? null, opts.autoAck ?? true);
      this.conns.push(conn);
      if (conn.auth !== `Bearer ${opts.token ?? 'good-token'}`) {
        conn.send({ t: 'error', code: 'unauthorized', message: 'invalid device token' });
        ws.close(4001, 'unauthorized');
      } else {
        conn.send(opts.hello ? opts.hello(this.accepted) : helloMsg());
        this.accepted++;
      }
      for (const w of this.waiters.splice(0)) w();
    });
  }

  static async start(opts: FakeServerOptions = {}): Promise<FakeServer> {
    const wss = new WebSocketServer({ host: '127.0.0.1', port: 0, path: '/ws/agent' });
    await new Promise<void>((resolve) => wss.once('listening', () => resolve()));
    return new FakeServer(wss, opts);
  }

  get url(): string {
    const { port } = this.wss.address() as AddressInfo;
    return `ws://127.0.0.1:${port}/ws/agent`;
  }

  /** Resolves with the n-th connection (0-based) once it exists. */
  connection(n: number, timeoutMs = 5000): Promise<FakeConn> {
    return new Promise((resolve, reject) => {
      const check = () => {
        const c = this.conns[n];
        if (c) {
          clearTimeout(timer);
          resolve(c);
          return true;
        }
        return false;
      };
      const timer = setTimeout(() => reject(new Error(`timed out waiting for connection #${n}`)), timeoutMs);
      if (check()) return;
      const waiter = () => {
        if (!check()) this.waiters.push(waiter);
      };
      this.waiters.push(waiter);
    });
  }

  async close(): Promise<void> {
    for (const c of this.conns) c.ws.terminate();
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
  }
}
