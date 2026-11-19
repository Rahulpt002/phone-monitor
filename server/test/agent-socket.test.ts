import type { Stmt } from '../src/db/client.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { HsmServer } from '../src/server.js';
import type { DeviceRuntime } from '../src/services/devices.js';
import { iso } from '../src/util.js';
import { normalizeFinderLive } from '../src/ws/agent.js';
import { adminWithTotp, api, FakeClock, fixture, ingest, makeServer, pairDevice, T0 } from './helpers.js';
import { Client, type Msg } from './ws-client.js';

/** Removes every null-valued key, recursively (what the Kotlin Agent does with explicitNulls = false). */
function stripNulls(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(stripNulls);
  if (v !== null && typeof v === 'object') {
    return Object.fromEntries(
      Object.entries(v as Record<string, unknown>)
        .filter(([, x]) => x !== null)
        .map(([k, x]) => [k, stripNulls(x)]),
    );
  }
  return v;
}

const countNulls = (v: unknown): number =>
  v === null ? 1 : typeof v === 'object' ? Object.values(v as object).reduce((n: number, x) => n + countNulls(x), 0) : 0;

describe('Agent socket: acknowledgements, invalid items and omitted nulls', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let dev: DeviceRuntime;
  let agent: Client;
  let base: string;

  const now = () => iso(clock.now());
  const ackOf = (seq: number) => agent.waitFor((m) => m.t === 'ack' && m.seq === seq, `ack ${seq}`);
  const count = async (sql: string, args: (string | number)[] = []) =>
    Number((await server.ctx.db.get<{ n: number }>(sql, args))?.n ?? 0);

  beforeEach(async () => {
    clock = new FakeClock(T0);
    ({ server } = await makeServer({ clock }));
    base = (await server.listen()).replace('localhost', '127.0.0.1');
    const paired = await pairDevice(server);
    dev = paired.dev;
    agent = await Client.open(`${base.replace(/^http/, 'ws')}/ws/agent`, { authorization: `Bearer ${paired.token}` });
    await agent.waitFor((m) => m.t === 'hello', 'hello');
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    agent.ws.close();
    await server.close();
  });

  const event = (seq: number, id: string) => ({
    t: 'event',
    seq,
    event: { id, ts: now(), kind: 'connectivity', severity: 'info', message: 'Internet back', data: { online: true } },
  });
  const sms = (seq: number, id: string) => ({
    t: 'sms',
    seq,
    sms: { id, direction: 'in', peer: 'JX-JIOINF', body: 'hello', at: now() },
  });
  const fast = (seq: number, ts: string) => ({ t: 'telemetry', seq, ts, tier: 'fast', radio: { net_type: 'LTE', rsrp: -95 }, net: { online: true } });

  it('acks a batch whose first item reports an unknown command, and stores the valid items after it', async () => {
    agent.send({
      t: 'batch',
      items: [
        // Left in the outbox from before a re-pairing: no such command on this Server.
        { t: 'cmd_result', seq: 41, id: 'c_from_old_pairing', ok: true, output: '', error: null, duration_ms: 5, ts: now() },
        { t: 'cmd_ack', seq: 42, id: 'c_from_old_pairing', accepted: true, ts: now() },
        event(43, 'e_after_unknown'),
        sms(44, 'm_after_unknown'),
        fast(45, now()),
      ],
    });
    await ackOf(45);
    expect(agent.messages.filter((m) => m.t === 'ack').map((m) => m.seq)).toEqual([45]);
    expect(await count('SELECT COUNT(*) AS n FROM events WHERE device_id = ? AND id = ?', [dev.id, 'e_after_unknown'])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM sms WHERE device_id = ? AND id = ?', [dev.id, 'm_after_unknown'])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM telemetry_raw WHERE device_id = ?', [dev.id])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM commands')).toBe(0);
  });

  it('drops invalid items one by one: the rest of the batch is stored and the highest seq acked', async () => {
    agent.send({
      t: 'batch',
      items: [
        event(50, 'e_first'),
        { t: 'finder_spot', seq: 51, spot: { id: 's_broken', name: 'no metrics' } },
        'not an object',
        null,
        { t: 'cmd_revert', seq: 52, id: 'c_nope', state: 'sideways', ts: now() },
        { t: 'event', seq: 53, event: { ts: now(), kind: 'boot', severity: 'info', message: 'no id' } },
        { t: 'telemetry', seq: 54, ts: 'not a time', tier: 'fast', radio: { net_type: 'LTE' } },
        { t: 'some_future_type', seq: 55, data: 1 },
        sms(56, 'm_last'),
      ],
    });
    await ackOf(56);
    expect(await count('SELECT COUNT(*) AS n FROM events WHERE device_id = ?', [dev.id])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM sms WHERE device_id = ? AND id = ?', [dev.id, 'm_last'])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM finder_spots')).toBe(0);
    expect(await count('SELECT COUNT(*) AS n FROM telemetry_raw')).toBe(0);
    // Not a protocol error: the frame itself was fine.
    expect(agent.messages.some((m) => m.t === 'error')).toBe(false);
  });

  it('acks a single invalid buffered message so it cannot block the outbox head', async () => {
    agent.send({ t: 'cmd_revert', seq: 60, id: 'c_unknown', state: 'kept', ts: now() });
    await ackOf(60);
    agent.send({ t: 'finder_spot', seq: 61, spot: { id: 's_x' } });
    await ackOf(61);
  });

  it('stores the rest of a batch when the database rejects one item', async () => {
    const db = server.ctx.db;
    const realBatch = db.batch.bind(db);
    vi.spyOn(db, 'batch').mockImplementation(async (stmts: Stmt[]) => {
      if (stmts.some((s) => s.args?.includes('e_poison'))) throw new Error('SQLITE_CONSTRAINT: simulated');
      return realBatch(stmts);
    });
    agent.send({ t: 'batch', items: [event(70, 'e_ok_1'), event(71, 'e_poison'), sms(72, 'm_ok'), event(73, 'e_ok_2')] });
    await ackOf(73);
    const ids = (await db.all<{ id: string }>('SELECT id FROM events WHERE device_id = ? ORDER BY id', [dev.id])).map((r) => r.id);
    expect(ids).toEqual(['e_ok_1', 'e_ok_2']);
    expect(await count('SELECT COUNT(*) AS n FROM sms WHERE id = ?', ['m_ok'])).toBe(1);
  });

  it('acknowledges nothing while the database is unreachable, then stores the resent group', async () => {
    const db = server.ctx.db;
    const batchSpy = vi.spyOn(db, 'batch').mockRejectedValue(new Error('fetch failed'));
    const pingSpy = vi.spyOn(db, 'ping').mockResolvedValue(false);
    const group = [event(80, 'e_outage'), sms(81, 'm_outage')];
    await expect(ingest(server, dev, ...group)).rejects.toThrow('fetch failed');
    agent.send({ t: 'batch', items: group });
    await new Promise((r) => setTimeout(r, 150));
    expect(agent.messages.some((m) => m.t === 'ack')).toBe(false);

    batchSpy.mockRestore();
    pingSpy.mockRestore();
    agent.send({ t: 'batch', items: group });
    await ackOf(81);
    expect(await count('SELECT COUNT(*) AS n FROM events WHERE id = ?', ['e_outage'])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM sms WHERE id = ?', ['m_outage'])).toBe(1);
  });

  it('accepts and stores every fixture message with its null-valued fields omitted', async () => {
    const { token } = await adminWithTotp(server, clock);
    const dash = await Client.open(`${base.replace(/^http/, 'ws')}/ws/dashboard`);
    dash.send({ t: 'auth', token });
    await dash.waitFor((m) => m.t === 'auth_ok', 'auth_ok');
    dash.send({ t: 'subscribe', device_id: dev.id, finder: true });
    await dash.waitFor((m) => m.t === 'snapshot', 'snapshot');

    // Real commands for the fixture's command ids, so the reports have something to update.
    const ids: Record<string, string> = {};
    for (const fixtureId of ['c_8f2a', 'c_9b11', 'c_a001']) {
      const r = await api(server, 'POST', '/api/commands', { token, body: { name: 'refresh' } });
      expect(r.status).toBe(201);
      ids[fixtureId] = r.body.id;
    }

    const original = fixture<Msg[]>('agent-to-server.json');
    expect(countNulls(original)).toBeGreaterThan(5); // the fixture really has nulls to strip
    const msgs = JSON.parse(JSON.stringify(stripNulls(original)).replace(/"c_(8f2a|9b11|a001)"/g, (q) => `"${ids[q.slice(1, -1)]}"`)) as Msg[];
    expect(countNulls(msgs)).toBe(0);

    for (const m of msgs) {
      agent.send(m);
      if (m.t === 'batch') await ackOf(Math.max(...(m.items as Msg[]).map((i) => i.seq as number)));
      else if (typeof m.seq === 'number') await ackOf(m.seq);
      else if (m.t === 'probe') await agent.waitFor((a) => a.t === 'probe_ack' && a.id === m.id, 'probe_ack');
    }
    expect(agent.messages.filter((m) => m.t === 'error')).toEqual([]);

    // Telemetry (fast, slow, static; the batch repeats fast and slow at the same ts).
    expect(await count('SELECT COUNT(*) AS n FROM telemetry_raw WHERE device_id = ?', [dev.id])).toBe(3);
    const snap = await api(server, 'GET', `/api/device?device_id=${dev.id}`, { token });
    expect(snap.body.state.net).toMatchObject({ online: true, public_ipv4: '203.0.113.7' });
    expect(snap.body.state.net).not.toHaveProperty('public_ipv6');
    expect(snap.body.state.tether.clients).toEqual([{ ip: '192.168.42.100', mac: 'aa:bb:cc:dd:ee:ff', iface: 'rndis0' }]);
    expect(snap.body.state.capabilities.mobile_data).toEqual({ supported: true, method: 'svc data' });

    // Event, SMS, spot, spot deletion.
    expect(await count('SELECT COUNT(*) AS n FROM events WHERE device_id = ? AND id = ?', [dev.id, 'e_7Hk2pQ'])).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM sms WHERE device_id = ? AND id = ?', [dev.id, 'm_x1'])).toBe(1);
    const spots = await api(server, 'GET', `/api/finder/spots?device_id=${dev.id}`, { token });
    expect(spots.body.spots).toEqual([expect.objectContaining({ id: 's_roof_ne', cell_switches: 0, baseline: false })]);
    await dash.waitFor((m) => m.t === 'finder_spot_deleted' && m.spot_id === 's_old', 'finder_spot_deleted');

    // Command reports without reason / error / revert_deadline.
    const cmds = await api(server, 'GET', `/api/commands?device_id=${dev.id}&limit=10`, { token });
    const byId = Object.fromEntries((cmds.body as Msg[]).map((c) => [c.id, c]));
    expect(byId[ids.c_8f2a!]).toMatchObject({ status: 'done', ok: true, output: '', error: null, revert_deadline: null, duration_ms: 14200 });
    expect(byId[ids.c_9b11!]).toMatchObject({ status: 'rejected', ok: false, error: 'expired' });
    expect(byId[ids.c_a001!]).toMatchObject({ status: 'reverted', error: 'no server round-trip within 120 s' });

    // FinderLive without progress (and the relay keeps the contract's score fields).
    const live = await dash.waitFor((m) => m.t === 'finder_live', 'finder_live');
    expect(live.live).toMatchObject({ score: 63.5, score_raw: 64.9, mode: 'walk' });
    dash.ws.close();
  });

  it('treats omitted speed values and FinderLive scores as null', async () => {
    const { token } = await adminWithTotp(server, clock);
    const dash = await Client.open(`${base.replace(/^http/, 'ws')}/ws/dashboard`);
    dash.send({ t: 'auth', token });
    await dash.waitFor((m) => m.t === 'auth_ok', 'auth_ok');
    dash.send({ t: 'subscribe', device_id: dev.id, finder: true });
    await dash.waitFor((m) => m.t === 'snapshot', 'snapshot');

    const spot = structuredClone(fixture<Msg[]>('agent-to-server.json').find((m) => m.t === 'finder_spot')!.spot) as Msg;
    spot.id = 's_failed_speed';
    // A failed speed session: the Agent omits every null value.
    spot.speed = [{ at: now(), peak_hour: false, runs: [{ bytes: 0, at: now() }] }];
    delete spot.metrics.sinr;
    delete spot.lat;
    agent.send({ t: 'finder_spot', seq: 90, spot });
    await ackOf(90);
    const spots = await api(server, 'GET', `/api/finder/spots?device_id=${dev.id}`, { token });
    const stored = (spots.body.spots as Msg[]).find((s) => s.id === 's_failed_speed')!;
    expect(stored.speed[0]).toMatchObject({ down_mbps: null, up_mbps: null, ping_ms: null, jitter_ms: null });
    expect(stored.speed[0].runs[0]).toEqual({ bytes: 0, at: now(), down_mbps: null, up_mbps: null, ping_ms: null, jitter_ms: null });

    agent.send({ t: 'finder_live', live: { ts: now(), mode: 'walk', net_type: 'LTE' } });
    const live = await dash.waitFor((m) => m.t === 'finder_live', 'finder_live');
    expect(live.live).toEqual({ ts: now(), mode: 'walk', net_type: 'LTE', score: null, score_raw: null });
    dash.ws.close();
  });
});

describe('normalizeFinderLive', () => {
  it('fills in the required nullable scores and keeps everything else', () => {
    expect(normalizeFinderLive({ ts: 't', mode: 'idle' })).toEqual({ ts: 't', mode: 'idle', score: null, score_raw: null });
    expect(normalizeFinderLive({ ts: 't', mode: 'walk', score: 50, score_raw: 51, rsrp: -90 })).toEqual({
      ts: 't', mode: 'walk', score: 50, score_raw: 51, rsrp: -90,
    });
  });
});
