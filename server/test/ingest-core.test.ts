import type { FastifyRequest } from 'fastify';
import { afterEach, describe, expect, it, vi } from 'vitest';
import WebSocket from 'ws';
import type { Stmt } from '../src/db/client.js';
import { FUTURE_TS_TOLERANCE_MS } from '../src/services/devices.js';
import { isItemError } from '../src/services/ingest.js';
import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import { handleAgentSocket } from '../src/ws/agent.js';
import { alertKinds, alertServer, eventMsg, settle } from './core-helpers.js';
import {
  ADMIN,
  adminWithTotp,
  api,
  asWs,
  connectFakeAgent,
  connectFakeDashboard,
  type FakeClock,
  fakeSocket,
  ingest,
  makeServer,
  msgsOfType,
  pairDevice,
} from './helpers.js';

const count = async (server: HsmServer, sql: string, args: (string | number)[] = []) =>
  Number((await server.ctx.db.get<{ n: number }>(sql, args))?.n ?? 0);

describe('ingest: write failures and lost commit responses', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => {
    vi.restoreAllMocks();
    await server?.close();
  });

  it('classifies only data errors as item errors', () => {
    expect(isItemError(new Error('SQLITE_CONSTRAINT: NOT NULL constraint failed: events.kind'))).toBe(true);
    expect(isItemError(Object.assign(new Error('datatype mismatch'), { code: 'SQLITE_MISMATCH' }))).toBe(true);
    expect(isItemError(Object.assign(new Error('database or disk is full'), { code: 'SQLITE_FULL' }))).toBe(false);
    expect(isItemError(Object.assign(new Error('attempt to write a readonly database'), { code: 'SQLITE_READONLY' }))).toBe(false);
    expect(isItemError(Object.assign(new Error('database is locked'), { code: 'SQLITE_BUSY' }))).toBe(false);
    expect(isItemError(Object.assign(new Error('Operation was blocked'), { code: 'BLOCKED' }))).toBe(false);
    expect(isItemError(new TypeError('fetch failed', { cause: Object.assign(new Error('read ECONNRESET'), { code: 'ECONNRESET' }) }))).toBe(false);
    expect(isItemError(new Error('something else'))).toBe(false);
  });

  it('acknowledges nothing when writes fail but reads work (F03)', async () => {
    ({ server, clock } = await makeServer());
    const { dev } = await pairDevice(server);
    const db = server.ctx.db;
    const spy = vi.spyOn(db, 'batch').mockRejectedValue(
      Object.assign(new Error('SQLITE_READONLY: attempt to write a readonly database'), { code: 'SQLITE_READONLY' }),
    );
    expect(await db.ping()).toBe(true);
    await expect(ingest(server, dev, eventMsg(7, 'e_boot', 'boot', clock.now()))).rejects.toThrow('SQLITE_READONLY');

    // Every item rejected, even with a data error: an outage too, nothing acknowledged.
    spy.mockRejectedValue(new Error('SQLITE_CONSTRAINT: simulated for every write'));
    await expect(
      ingest(server, dev, eventMsg(8, 'e_a', 'boot', clock.now()), eventMsg(9, 'e_b', 'boot', clock.now())),
    ).rejects.toThrow(/rejected every buffered message/);
    spy.mockRestore();
    expect(await count(server, 'SELECT COUNT(*) AS n FROM events')).toBe(0);
    // Once writes work again the resent group is stored and acknowledged.
    expect(await ingest(server, dev, eventMsg(7, 'e_boot', 'boot', clock.now()))).toMatchObject({ ackSeq: 7, stored: 1 });
  });

  it('alerts and rolls up after a commit whose response was lost (F46)', async () => {
    const s = await alertServer();
    ({ server, clock } = s);
    const { dev } = await pairDevice(server);
    const db = server.ctx.db;
    const real = db.batch.bind(db);
    let lost = false;
    vi.spyOn(db, 'batch').mockImplementation(async (stmts: Stmt[]) => {
      const res = await real(stmts);
      if (!lost && stmts.some((x) => x.args?.includes('e_hot'))) {
        lost = true;
        throw new TypeError('fetch failed'); // committed, but the response never arrived
      }
      return res;
    });
    const ts = clock.now() - 1000;
    const group = [
      { t: 'telemetry', seq: 1, ts: iso(ts), tier: 'slow', power: { percent: 80 } },
      eventMsg(2, 'e_hot', 'temp_critical', ts, { severity: 'critical', message: 'Battery 46 °C' }),
    ];
    await expect(ingest(server, dev, ...group)).rejects.toThrow('fetch failed');
    // The Agent resends; every insert is now ignored as a duplicate.
    expect(await ingest(server, dev, ...group)).toMatchObject({ ackSeq: 2 });
    await settle(50);
    expect(await alertKinds(server)).toEqual(['hot']);
    expect(dev.state.power?.percent).toBe(80);
    await server.ctx.telemetry.rollupDirty(true);
    expect(await count(server, 'SELECT COUNT(*) AS n FROM telemetry_1m WHERE device_id = ?', [dev.id])).toBe(1);

    // An ordinary resend of an event whose alert went out raises nothing new.
    await ingest(server, dev, ...group);
    await settle(50);
    expect(await alertKinds(server)).toEqual(['hot']);
  });

  it('does not let a future-dated reading freeze the state or count as live (F37)', async () => {
    ({ server, clock } = await makeServer());
    const { dev } = await pairDevice(server);
    const dash = connectFakeDashboard(server, 'viewer', { deviceId: dev.id, finder: false });
    const fast = (seq: number, tsMs: number, rsrp: number) => ({
      t: 'telemetry', seq, ts: iso(tsMs), tier: 'fast', radio: { net_type: 'LTE', rsrp },
    });
    const ahead = clock.now() + 3_600_000; // phone clock an hour fast
    await ingest(server, dev, fast(1, ahead, -80));
    expect(dev.state.radio?.rsrp).toBe(-80);
    expect(dev.stateTs.radio).toBe(clock.now());
    expect(msgsOfType(dash.sock, 'telemetry')).toHaveLength(0);
    // The stored row keeps the phone's timestamp (dedup depends on it).
    expect(await count(server, 'SELECT COUNT(*) AS n FROM telemetry_raw WHERE ts = ?', [ahead])).toBe(1);

    clock.advance(60_000);
    await ingest(server, dev, fast(2, clock.now(), -95));
    expect(dev.state.radio?.rsrp).toBe(-95);
    expect(msgsOfType(dash.sock, 'telemetry')).toHaveLength(1);

    // A state timestamp already stored far in the future (before this fix) does not block either.
    dev.stateTs.radio = clock.now() + 2 * 3_600_000;
    clock.advance(5_000);
    await ingest(server, dev, fast(3, clock.now(), -99));
    expect(dev.state.radio?.rsrp).toBe(-99);
    // Within the tolerance a slightly fast clock is still live.
    await ingest(server, dev, fast(4, clock.now() + FUTURE_TS_TOLERANCE_MS - 1000, -90));
    expect(msgsOfType(dash.sock, 'telemetry')).toHaveLength(3);
  });

  it("sends a Dashboard a snapshot on a phone's first connection", async () => {
    ({ server, clock } = await makeServer());
    const { dev, token } = await pairDevice(server);
    const dash = connectFakeDashboard(server, 'admin', { deviceId: null, finder: false });
    const req = (t: string) => ({ headers: { authorization: `Bearer ${t}` }, ip: '127.0.0.1' }) as unknown as FastifyRequest;
    handleAgentSocket(server.ctx, asWs(fakeSocket()), req(token));
    expect(msgsOfType(dash.sock, 'snapshot')).toEqual([
      { t: 'snapshot', device: expect.objectContaining({ device_id: dev.id, online: true }) },
    ]);
    // Later connections send a status.
    handleAgentSocket(server.ctx, asWs(fakeSocket()), req(token));
    expect(msgsOfType(dash.sock, 'snapshot')).toHaveLength(1);
    expect(msgsOfType(dash.sock, 'status').at(-1)).toMatchObject({ device_id: dev.id, online: true });
  });

  it('negotiates permessage-deflate on the Agent socket only (F33)', async () => {
    ({ server, clock } = await makeServer());
    const base = (await server.listen()).replace('localhost', '127.0.0.1').replace(/^http/, 'ws');
    const { token } = await pairDevice(server);
    const open = (path: string, headers: Record<string, string> = {}) =>
      new Promise<WebSocket>((resolve, reject) => {
        const ws = new WebSocket(`${base}${path}`, { headers, perMessageDeflate: true });
        ws.once('open', () => resolve(ws));
        ws.once('error', reject);
      });
    const agent = await open('/ws/agent', { authorization: `Bearer ${token}` });
    expect(agent.extensions).toContain('permessage-deflate');
    expect(agent.extensions).not.toContain('client_max_window_bits'); // OkHttp refuses that parameter
    const dash = await open('/ws/dashboard');
    expect(dash.extensions).toBe('');
    agent.close();
    dash.close();
  });
});

describe('PUT /api/settings: re-auth for profile_override, audit event, write before assign', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => {
    vi.restoreAllMocks();
    await server?.close();
  });

  const override = { commands: { mobile_data: ['svc data enable'] }, charge_control: [{ path: '/sys/x', on: '1', off: '0' }] };

  it('needs the password to change profile_override and records every change as an event (F01)', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const { dev } = await pairDevice(server);
    const agent = connectFakeAgent(server, dev);
    const dash = connectFakeDashboard(server, 'viewer', { deviceId: dev.id, finder: false });
    const put = (body: Record<string, unknown>) => api(server, 'PUT', '/api/settings', { token, body });

    const noPw = await put({ settings: { profile_override: override } });
    expect(noPw.status).toBe(401);
    expect(noPw.body.error).toBe('reauth_required');
    const badPw = await put({ settings: { profile_override: override }, password: 'not the password' });
    expect(badPw.status).toBe(401);
    expect(dev.settingsVersion).toBe(1);
    expect(dev.settings.profile_override).toBeNull();
    expect(msgsOfType(agent, 'settings')).toHaveLength(0);

    const ok = await put({ settings: { profile_override: override }, password: ADMIN.password });
    expect(ok.status).toBe(200);
    expect(ok.body).toMatchObject({ version: 2, settings: { profile_override: override } });
    expect(msgsOfType(agent, 'settings')).toHaveLength(1);

    // Other settings need no password; re-sending the same override (a full-form save) neither.
    const plain = await put({ settings: { plan: { quota_gb: 20 }, profile_override: structuredClone(override) } });
    expect(plain.status).toBe(200);
    expect(plain.body.version).toBe(3);

    const events = await api(server, 'GET', `/api/events?device_id=${dev.id}&kind=settings_changed`, { token });
    expect(events.body).toEqual([
      expect.objectContaining({
        kind: 'settings_changed',
        severity: 'info',
        data: { user: 'admin', version: 3, changed: ['plan.quota_gb'], profile_override_changed: false },
      }),
      expect.objectContaining({
        kind: 'settings_changed',
        severity: 'warning',
        data: { user: 'admin', version: 2, changed: ['profile_override'], profile_override_changed: true },
      }),
    ]);
    const live = msgsOfType(dash.sock, 'event').map((m) => (m.event as { kind: string }).kind);
    expect(live).toEqual(['settings_changed', 'settings_changed']);
  });

  it('changes nothing in memory or on the phone when the write fails, and serializes updates (F47)', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const { dev } = await pairDevice(server);
    const agent = connectFakeAgent(server, dev);
    const db = server.ctx.db;
    const real = db.batch.bind(db);
    const realRun = db.run.bind(db);
    const isSettingsWrite = (sql: string | undefined) => !!sql?.startsWith('UPDATE devices SET settings');
    const spy = vi.spyOn(db, 'batch').mockImplementation(async (stmts: Stmt[]) => {
      if (isSettingsWrite(stmts[0]?.sql)) throw new TypeError('fetch failed');
      return real(stmts);
    });
    const runSpy = vi.spyOn(db, 'run').mockImplementation(async (sql, args) => {
      if (isSettingsWrite(sql)) throw new TypeError('fetch failed');
      return realRun(sql, args);
    });
    const failed = await api(server, 'PUT', '/api/settings', { token, body: { settings: { plan: { quota_gb: 7 } } } });
    expect(failed.status).toBe(500);
    expect(dev.settingsVersion).toBe(1);
    expect(dev.settings.plan.quota_gb).toBe(0);
    expect(msgsOfType(agent, 'settings')).toHaveLength(0);
    spy.mockRestore();
    runSpy.mockRestore();

    const [a, b] = await Promise.all([
      api(server, 'PUT', '/api/settings', { token, body: { settings: { plan: { quota_gb: 7 } } } }),
      api(server, 'PUT', '/api/settings', { token, body: { settings: { watchdog: { charge_limit_pct: 70 } } } }),
    ]);
    expect([a.body.version, b.body.version].sort()).toEqual([2, 3]);
    expect(dev.settings.plan.quota_gb).toBe(7);
    expect(dev.settings.watchdog.charge_limit_pct).toBe(70);
    const stored = await db.get<{ settings: string; settings_version: number }>('SELECT settings, settings_version FROM devices WHERE id = ?', [dev.id]);
    expect(Number(stored?.settings_version)).toBe(3);
    expect(JSON.parse(stored!.settings)).toMatchObject({ plan: { quota_gb: 7 }, watchdog: { charge_limit_pct: 70 } });
    expect(msgsOfType(agent, 'settings').map((m) => m.version)).toEqual([2, 3]);
  });
});
