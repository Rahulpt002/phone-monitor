import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DeviceRuntime } from '../src/services/devices.js';
import { downsample, MAX_POINTS, minuteOf } from '../src/services/telemetry.js';
import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import {
  ADMIN,
  api,
  connectFakeDashboard,
  type FakeClock,
  ingest,
  makeServer,
  msgsOfType,
  pairDevice,
} from './helpers.js';

const fast = (seq: number, ts: number, rsrp: number, score = 50) => ({
  t: 'telemetry',
  seq,
  ts: iso(ts),
  tier: 'fast',
  radio: { net_type: 'LTE', rsrp, rsrq: -11, sinr: 10, pci: 211, cell_id: 1234, score },
  net: { online: true, ping_ms: 40 + seq },
});
const slow = (seq: number, ts: number, percent: number) => ({
  t: 'telemetry',
  seq,
  ts: iso(ts),
  tier: 'slow',
  power: { percent, current_ma: -10 },
  system: { cpu_load: [0.5, 0.4, 0.3] },
});

describe('telemetry ingest, rollup and history', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let dev: DeviceRuntime;
  let token: string;

  beforeEach(async () => {
    ({ server, clock } = await makeServer());
    ({ dev } = await pairDevice(server));
    token = (await api(server, 'POST', '/api/auth/login', { body: ADMIN })).body.token;
  });
  afterEach(async () => server.close());

  const rawCount = async () =>
    Number((await server.ctx.db.get<{ n: number }>('SELECT COUNT(*) AS n FROM telemetry_raw WHERE device_id = ?', [dev.id]))?.n);

  it('stores one row per message and ignores duplicates', async () => {
    const t = clock.now() - 5_000;
    const r1 = await ingest(server, dev, fast(1, t, -90));
    expect(r1.ackSeq).toBe(1);
    const r2 = await ingest(server, dev, fast(1, t, -90), fast(2, t, -91));
    expect(r2.ackSeq).toBe(2);
    // seq 2 has the same (device, tier, ts) as seq 1, so it is a duplicate too.
    expect(await rawCount()).toBe(1);
    const malformed = await ingest(server, dev, { t: 'telemetry', seq: 3, ts: 'not a time', tier: 'fast' });
    expect(malformed).toMatchObject({ ackSeq: 3, skipped: 1 });
  });

  it('updates the current state only from newer readings and relays only live ones', async () => {
    const dash = connectFakeDashboard(server, 'viewer', { deviceId: dev.id, finder: false });
    const now = clock.now();
    await ingest(server, dev, fast(1, now - 1000, -88));
    expect(dev.state.radio?.rsrp).toBe(-88);
    // Backlog from an hour ago: stored, not relayed, does not overwrite.
    await ingest(server, dev, fast(2, now - 3_600_000, -110));
    expect(dev.state.radio?.rsrp).toBe(-88);
    expect(await rawCount()).toBe(2);
    const relayed = msgsOfType(dash.sock, 'telemetry');
    expect(relayed).toHaveLength(1);
    expect(relayed[0]).toMatchObject({ device_id: dev.id, tier: 'fast', radio: { rsrp: -88 } });
    // Snapshot reports per-group timestamps.
    const snap = server.ctx.devices.snapshot(dev);
    expect(snap.state_ts.radio).toBe(iso(now - 1000));
  });

  it('rolls raw readings up into 1-minute averages and recomputes after backlog', async () => {
    const m0 = minuteOf(clock.now()) - 10 * 60_000;
    await ingest(server, dev, fast(1, m0 + 1_000, -90, 60), fast(2, m0 + 31_000, -100, 40), slow(3, m0 + 2_000, 80));
    await ingest(server, dev, fast(4, m0 + 61_000, -80, 70));
    expect(server.ctx.telemetry.dirtyCount()).toBe(2);
    expect(await server.ctx.telemetry.rollupDirty()).toBe(2);
    const rows = await server.ctx.db.all<{ minute: number; n: number; data: string }>(
      'SELECT minute, n, data FROM telemetry_1m WHERE device_id = ? ORDER BY minute',
      [dev.id],
    );
    expect(rows.map((r) => Number(r.minute))).toEqual([m0, m0 + 60_000]);
    const first = JSON.parse(rows[0]!.data);
    expect(first['radio.rsrp']).toBe(-95);
    expect(first['radio.score']).toBe(50);
    expect(first['power.percent']).toBe(80);
    expect(first['system.load1']).toBe(0.5);
    expect(Number(rows[0]!.n)).toBe(3);

    // Late backlog for minute m0 re-dirties it.
    await ingest(server, dev, fast(5, m0 + 50_000, -110, 30));
    expect(await server.ctx.telemetry.rollupDirty()).toBe(1);
    const again = await server.ctx.db.get<{ data: string; n: number }>(
      'SELECT data, n FROM telemetry_1m WHERE device_id = ? AND minute = ?',
      [dev.id, m0],
    );
    expect(JSON.parse(again!.data)['radio.rsrp']).toBe(-100);
    expect(Number(again!.n)).toBe(4);
  });

  it('does not roll up a minute that has not ended yet', async () => {
    await ingest(server, dev, fast(1, clock.now() - 1000, -90));
    expect(await server.ctx.telemetry.rollupDirty()).toBe(0);
    clock.advance(90_000);
    expect(await server.ctx.telemetry.rollupDirty()).toBe(1);
  });

  it('catches up rollups after a restart', async () => {
    const m0 = minuteOf(clock.now()) - 5 * 60_000;
    await ingest(server, dev, fast(1, m0 + 1000, -90));
    // Simulate a crash: the in-memory dirty set is lost.
    (server.ctx.telemetry as unknown as { dirty: Map<string, Set<number>> }).dirty.clear();
    expect(await server.ctx.telemetry.catchUp()).toBe(1);
    expect(await server.ctx.telemetry.rollupDirty()).toBe(1);
  });

  it('answers history queries in raw and 1m resolution', async () => {
    const now = clock.now();
    const m0 = minuteOf(now) - 3 * 60_000;
    await ingest(server, dev, fast(1, m0 + 1000, -90), fast(2, m0 + 30_000, -92), fast(3, m0 + 61_000, -94));
    await server.ctx.telemetry.rollupDirty();

    const raw = await api(server, 'GET', `/api/telemetry?metric=radio.rsrp&from=${iso(now - 3_600_000)}&to=${iso(now)}`, { token });
    expect(raw.status).toBe(200);
    expect(raw.body.res).toBe('raw');
    expect(raw.body.points).toEqual([
      [m0 + 1000, -90],
      [m0 + 30_000, -92],
      [m0 + 61_000, -94],
    ]);

    const oneMin = await api(server, 'GET', `/api/telemetry?metric=radio.rsrp&from=${iso(now - 7 * 3_600_000)}&to=${iso(now)}`, { token });
    expect(oneMin.body.res).toBe('1m');
    expect(oneMin.body.points).toEqual([
      [m0, -91],
      [m0 + 60_000, -94],
    ]);

    const forced = await api(server, 'GET', `/api/telemetry?metric=power.percent&res=1m&from=${iso(now - 3_600_000)}&to=${iso(now)}`, { token });
    expect(forced.body).toMatchObject({ res: '1m', metric: 'power.percent' });

    expect((await api(server, 'GET', '/api/telemetry?metric=nope', { token })).status).toBe(400);
    expect((await api(server, 'GET', '/api/telemetry?metric=radio.rsrp&res=5m', { token })).status).toBe(400);
  });

  it('downsamples to at most 5000 points', () => {
    const pts: [number, number | null][] = [];
    for (let i = 0; i < 20_000; i++) pts.push([i * 1000, i % 7 === 0 ? null : i]);
    const out = downsample(pts, 0, 20_000_000);
    expect(out.length).toBeLessThanOrEqual(MAX_POINTS);
    expect(out[0]![0]).toBe(0);
    const small: [number, number | null][] = [[1, 2]];
    expect(downsample(small, 0, 10)).toBe(small);
  });

  it('applies retention to raw and rollup rows', async () => {
    const now = clock.now();
    await ingest(server, dev, fast(1, now - 8 * 86_400_000, -90), fast(2, now - 1000, -91));
    await server.ctx.telemetry.retention(now);
    expect(await rawCount()).toBe(1);
  });
});
