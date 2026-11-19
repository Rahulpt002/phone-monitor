import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { DeviceInfo } from '@hsm/shared';
import type { WebSocket } from 'ws';
import { totpCode } from '../src/auth/totp.js';
import type { Config } from '../src/config.js';
import type { DeviceRuntime } from '../src/services/devices.js';
import { storeBuffered } from '../src/services/ingest.js';
import { buildServer, type BuildOptions, type HsmServer } from '../src/server.js';
import type { Clock } from '../src/util.js';
import type { DashConn } from '../src/ws/hub.js';

export const ADMIN = { username: 'admin', password: 'correct-horse-battery' };

export class FakeClock implements Clock {
  constructor(public t: number) {}
  now(): number {
    return this.t;
  }
  advance(ms: number): void {
    this.t += ms;
  }
}

export const T0 = Date.parse('2026-11-19T10:16:00.000Z');

export function testConfig(overrides: Partial<Config> = {}): Config {
  return {
    version: 'test',
    nodeEnv: 'test',
    port: 0,
    host: '127.0.0.1',
    publicUrl: null,
    databaseUrl: ':memory:',
    databaseAuthToken: null,
    adminUsername: ADMIN.username,
    adminPassword: ADMIN.password,
    sessionTtlDays: 30,
    telegramBotToken: null,
    telegramChatId: null,
    vapidPublicKey: null,
    vapidPrivateKey: null,
    vapidSubject: null,
    corsOrigins: [],
    webDist: '/nonexistent/web/dist',
    logLevel: 'silent',
    trustProxy: false,
    ...overrides,
  };
}

export async function makeServer(
  opts: { clock?: FakeClock; config?: Partial<Config> } & Omit<BuildOptions, 'clock'> = {},
): Promise<{ server: HsmServer; clock: FakeClock }> {
  const clock = opts.clock ?? new FakeClock(T0);
  const server = await buildServer(testConfig(opts.config), {
    clock,
    logger: false,
    fetch: opts.fetch,
    pushSender: opts.pushSender,
  });
  return { server, clock };
}

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'shared', 'fixtures');
export const fixture = <T = unknown>(name: string): T => JSON.parse(readFileSync(join(fixturesDir, name), 'utf8')) as T;

export const DEVICE_INFO: DeviceInfo = {
  manufacturer: 'samsung',
  model: 'SM-G991B',
  device: 'o1s',
  android_version: '13',
  sdk: 33,
  build_id: 'TP1A.220624.014',
  agent_version: '0.1.0',
  agent_version_code: 1,
  profile_id: 'samsung_galaxy_s',
  rooted: true,
  root_impl: 'magisk 27.0',
  imei_tail: '4821',
};

export async function pairDevice(server: HsmServer): Promise<{ dev: DeviceRuntime; token: string }> {
  const ctx = server.ctx;
  const code = await ctx.devices.createPairCode('Test phone', 'admin', 'http://localhost:8080');
  const res = await ctx.devices.pair(code.code, DEVICE_INFO, 'http://localhost:8080');
  const dev = ctx.devices.get(res.device_id);
  if (!dev) throw new Error('pairing failed');
  return { dev, token: res.device_token };
}

/** A stand-in for a ws socket that records what the Server sends. */
export interface FakeSocket {
  sent: Record<string, unknown>[];
  closed: { code?: number; reason?: string } | null;
  readyState: number;
  bufferedAmount: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  terminate(): void;
  ping(): void;
  on(): void;
}

export function fakeSocket(): FakeSocket {
  const s: FakeSocket = {
    sent: [],
    closed: null,
    readyState: 1,
    bufferedAmount: 0,
    send(data: string) {
      s.sent.push(JSON.parse(data) as Record<string, unknown>);
    },
    close(code?: number, reason?: string) {
      s.closed = { code, reason };
      s.readyState = 3;
    },
    terminate() {
      s.closed = { code: 1006 };
      s.readyState = 3;
    },
    ping() {},
    on() {},
  };
  return s;
}

export const asWs = (s: FakeSocket) => s as unknown as WebSocket;

export function connectFakeAgent(server: HsmServer, dev: DeviceRuntime): FakeSocket {
  const sock = fakeSocket();
  server.ctx.hub.registerAgent(dev.id, asWs(sock));
  dev.connectedSince = server.ctx.clock.now();
  server.ctx.devices.touch(dev);
  return sock;
}

export function connectFakeDashboard(
  server: HsmServer,
  role: 'admin' | 'viewer',
  sub: { deviceId: string | null; finder: boolean } = { deviceId: null, finder: false },
): { sock: FakeSocket; conn: DashConn } {
  const sock = fakeSocket();
  const conn = server.ctx.hub.addDashboard(asWs(sock));
  conn.user = { id: role === 'admin' ? 1 : 2, username: role, role, totp_enabled: true, created_at: '' };
  conn.sub = sub;
  return { sock, conn };
}

export async function ingest(server: HsmServer, dev: DeviceRuntime, ...msgs: unknown[]) {
  return storeBuffered(server.ctx, dev, msgs);
}

/** HTTP helper over Fastify's inject. */
export async function api(
  server: HsmServer,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  url: string,
  opts: { token?: string; body?: unknown; ip?: string } = {},
): Promise<{ status: number; body: any }> {
  const res = await server.app.inject({
    method,
    url,
    headers: {
      ...(opts.token ? { authorization: `Bearer ${opts.token}` } : {}),
      ...(opts.body !== undefined ? { 'content-type': 'application/json' } : {}),
    },
    payload: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    remoteAddress: opts.ip ?? '127.0.0.1',
  });
  let body: unknown = null;
  try {
    body = res.body ? JSON.parse(res.body) : null;
  } catch {
    body = res.body;
  }
  return { status: res.statusCode, body };
}

/** Signs in as the bootstrap admin and enrolls TOTP, returning a session with write access. */
export async function adminWithTotp(server: HsmServer, clock: FakeClock): Promise<{ token: string; secret: string }> {
  const login = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
  if (login.status !== 200) throw new Error(`login failed: ${JSON.stringify(login.body)}`);
  const token = login.body.token as string;
  const setup = await api(server, 'POST', '/api/auth/totp/setup', { token });
  const secret = setup.body.secret as string;
  const en = await api(server, 'POST', '/api/auth/totp/enable', { token, body: { code: totpCode(secret, clock.now()) } });
  if (en.status !== 200) throw new Error(`enable failed: ${JSON.stringify(en.body)}`);
  return { token, secret };
}

export const msgsOfType = (s: FakeSocket, t: string) => s.sent.filter((m) => m.t === t);
