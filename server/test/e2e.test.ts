import type { DeviceInfo } from '@hsm/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { totpCode } from '../src/auth/totp.js';
import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import { ADMIN, FakeClock, fixture, makeServer, T0 } from './helpers.js';

type Msg = Record<string, any>;

/** Minimal WebSocket test client that records every message. */
class Client {
  readonly messages: Msg[] = [];
  closed: { code: number; reason: string } | null = null;
  private listeners: (() => void)[] = [];

  private constructor(readonly ws: WebSocket) {
    ws.on('message', (data) => {
      this.messages.push(JSON.parse(data.toString()) as Msg);
      this.notify();
    });
    ws.on('close', (code, reason) => {
      this.closed = { code, reason: reason.toString() };
      this.notify();
    });
  }

  private notify() {
    for (const l of this.listeners.splice(0)) l();
  }

  static open(url: string, headers: Record<string, string> = {}): Promise<Client> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url, { headers });
      const c = new Client(ws);
      ws.once('open', () => resolve(c));
      ws.once('error', reject);
    });
  }

  send(msg: unknown) {
    this.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }

  async waitFor(pred: (m: Msg) => boolean, what: string, timeoutMs = 3000): Promise<Msg> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = this.messages.find(pred);
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}; got ${JSON.stringify(this.messages.map((m) => m.t))}`);
      await new Promise<void>((r) => {
        this.listeners.push(r);
        setTimeout(r, 50);
      });
    }
  }

  async waitClosed(timeoutMs = 3000) {
    const deadline = Date.now() + timeoutMs;
    while (!this.closed) {
      if (Date.now() > deadline) throw new Error('socket did not close');
      await new Promise((r) => setTimeout(r, 20));
    }
    return this.closed;
  }
}

describe('end to end: pairing, live sockets, fixtures and a command', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let base: string;
  let wsBase: string;
  let token: string;
  let deviceId: string;
  let deviceToken: string;
  let agent: Client;
  let dash: Client;

  const http = async (method: string, path: string, body?: unknown, auth = token) => {
    const res = await fetch(`${base}${path}`, {
      method,
      headers: {
        ...(auth ? { authorization: `Bearer ${auth}` } : {}),
        ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    return { status: res.status, body: text ? (JSON.parse(text) as any) : null, headers: res.headers };
  };

  beforeAll(async () => {
    clock = new FakeClock(T0);
    ({ server } = await makeServer({ clock }));
    base = (await server.listen()).replace('localhost', '127.0.0.1');
    wsBase = base.replace(/^http/, 'ws');
  });

  afterAll(async () => {
    agent?.ws.close();
    dash?.ws.close();
    await server.close();
  });

  it('signs in, enrolls TOTP and completes the two-step login', async () => {
    const first = await http('POST', '/api/auth/login', ADMIN, '');
    expect(first.status).toBe(200);
    expect(first.headers.get('content-security-policy')).toContain("frame-ancestors 'none'");
    const t1 = first.body.token as string;
    const setup = await http('POST', '/api/auth/totp/setup', undefined, t1);
    const enable = await http('POST', '/api/auth/totp/enable', { code: totpCode(setup.body.secret, clock.now()) }, t1);
    expect(enable.status).toBe(200);
    clock.advance(30_000);
    const step1 = await http('POST', '/api/auth/login', ADMIN, '');
    expect(step1.body).toMatchObject({ totp_required: true });
    const step2 = await http('POST', '/api/auth/totp', {
      pending_token: step1.body.pending_token,
      code: totpCode(setup.body.secret, clock.now()),
    }, '');
    expect(step2.status).toBe(200);
    token = step2.body.token;
  });

  it('pairs a phone with a one-time code', async () => {
    const codeRes = await http('POST', '/api/pair/codes', { name: 'Roof phone' });
    expect(codeRes.status).toBe(201);
    const { code, deep_link, adb_command } = codeRes.body;
    expect(deep_link).toBe(`hsm://pair?server=${encodeURIComponent(base)}&code=${code}`);
    expect(adb_command).toContain(deep_link);

    const staticMsg = fixture<Msg[]>('agent-to-server.json').find((m) => m.tier === 'static')!;
    const { device_id: _ignored, ...info } = staticMsg.device as DeviceInfo;
    const pair = await http('POST', '/api/pair', { code: code.toLowerCase(), device: info }, '');
    expect(pair.status).toBe(200);
    expect(pair.body.ws_url).toBe(`${wsBase}/ws/agent`);
    expect(pair.body.server_url).toBe(base);
    expect(pair.body.device_token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    deviceId = pair.body.device_id;
    deviceToken = pair.body.device_token;

    const again = await http('POST', '/api/pair', { code, device: info }, '');
    expect(again.status).toBe(401);

    const devices = await http('GET', '/api/devices');
    expect(devices.body).toEqual([expect.objectContaining({ device_id: deviceId, name: 'Roof phone', online: false })]);
  });

  it('rejects a bad device token with 4001', async () => {
    const bad = await Client.open(`${wsBase}/ws/agent`, { authorization: 'Bearer nope' });
    await bad.waitFor((m) => m.t === 'error' && m.code === 'unauthorized', 'unauthorized error');
    expect((await bad.waitClosed()).code).toBe(4001);
  });

  it('connects the Agent and a Dashboard and adjusts rates', async () => {
    agent = await Client.open(`${wsBase}/ws/agent`, { authorization: `Bearer ${deviceToken}` });
    const hello = await agent.waitFor((m) => m.t === 'hello', 'hello');
    expect(hello).toMatchObject({ device_id: deviceId, settings_version: 1, rates: { fast_s: 60, mirror: false } });
    expect(hello.server_time).toBe(iso(clock.now()));
    expect(hello.settings.commands.expiry_s).toBe(60);

    dash = await Client.open(`${wsBase}/ws/dashboard`);
    dash.send({ t: 'auth', token });
    expect((await dash.waitFor((m) => m.t === 'auth_ok', 'auth_ok')).user.username).toBe('admin');
    dash.send({ t: 'subscribe', device_id: deviceId, finder: true });
    const snap = await dash.waitFor((m) => m.t === 'snapshot', 'snapshot');
    expect(snap.device).toMatchObject({ device_id: deviceId, online: true, pending_reverts: [] });
    await agent.waitFor((m) => m.t === 'rates' && m.fast_s === 5 && m.mirror === true, 'rates update');
    dash.send({ t: 'ping' });
    await dash.waitFor((m) => m.t === 'pong', 'pong');
  });

  it('stores and acknowledges every fixture message', async () => {
    const msgs = fixture<Msg[]>('agent-to-server.json');
    for (const m of msgs) {
      agent.send(m);
      if (m.t === 'batch') {
        const max = Math.max(...(m.items as Msg[]).map((i) => i.seq as number));
        await agent.waitFor((a) => a.t === 'ack' && a.seq === max, `ack ${max}`);
      } else if (typeof m.seq === 'number') {
        await agent.waitFor((a) => a.t === 'ack' && a.seq === m.seq, `ack ${m.seq}`);
      } else if (m.t === 'probe') {
        const pa = await agent.waitFor((a) => a.t === 'probe_ack', 'probe_ack');
        expect(pa).toEqual({ t: 'probe_ack', id: m.id, server_time: iso(clock.now()) });
      }
    }
    // Live relay and broadcasts reached the Dashboard.
    const tel = await dash.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast', 'telemetry relay');
    expect(tel).toMatchObject({ device_id: deviceId, radio: { pci: 211 } });
    await dash.waitFor((m) => m.t === 'event' && m.event.id === 'e_7Hk2pQ', 'event');
    await dash.waitFor((m) => m.t === 'finder_live' && m.live.score === 63.5, 'finder_live');
    await dash.waitFor((m) => m.t === 'finder_spot' && m.spot.id === 's_roof_ne', 'finder_spot');
    await dash.waitFor((m) => m.t === 'finder_spot_deleted' && m.spot_id === 's_old', 'finder_spot_deleted');
    await dash.waitFor((m) => m.t === 'sms' && m.sms.id === 'm_x1', 'sms');
    await dash.waitFor((m) => m.t === 'alert' && m.alert.kind === 'watchdog', 'watchdog alert');
    await dash.waitFor((m) => m.t === 'status' && m.online === true, 'status');

    // The batch repeated seq 101/102 with the same (tier, ts): stored once.
    const raw = await server.ctx.db.get<{ n: number }>('SELECT COUNT(*) AS n FROM telemetry_raw WHERE device_id = ?', [deviceId]);
    expect(Number(raw?.n)).toBe(3);

    const snap = await http('GET', `/api/device?device_id=${deviceId}`);
    expect(snap.body).toMatchObject({
      online: true,
      outbox: 0,
      info: { model: 'SM-G991B', device_id: deviceId },
      state: { radio: { pci: 211 }, power: { percent: 58 }, tether: { usb_tether_on: true }, capabilities: { mobile_data: { supported: true } } },
    });
    const spots = await http('GET', '/api/finder/spots');
    expect(spots.body).toMatchObject({ spots: [{ id: 's_roof_ne' }], baseline_id: null });
    const events = await http('GET', '/api/events?kind=watchdog_action');
    expect(events.body[0]).toMatchObject({ id: 'e_7Hk2pQ', device_id: deviceId, kind: 'watchdog_action' });
    const sms = await http('GET', '/api/sms');
    expect(sms.body[0]).toMatchObject({ id: 'm_x1', peer: 'JX-JIOINF' });
    const hist = await http('GET', `/api/telemetry?metric=radio.rsrp&from=${iso(clock.now() - 3_600_000)}&to=${iso(clock.now())}`);
    expect(hist.body).toEqual({ metric: 'radio.rsrp', res: 'raw', points: [[Date.parse('2026-11-19T10:15:00.000Z'), -94]] });
  });

  it('runs a command through queued, sent, acked, revert_armed and kept', async () => {
    const res = await http('POST', '/api/commands', { name: 'mobile_data', args: { enabled: false } });
    expect(res.status).toBe(201);
    expect(res.body.status).toBe('sent');
    const id = res.body.id as string;
    const cmd = await agent.waitFor((m) => m.t === 'cmd' && m.id === id, 'cmd');
    expect(cmd).toMatchObject({ name: 'mobile_data', args: { enabled: false }, revert_after_s: 120, revert_confirm: 'agent_link', user: 'admin' });

    const now = () => iso(clock.now());
    agent.send({ t: 'cmd_ack', seq: 300, id, accepted: true, reason: null, ts: now() });
    await agent.waitFor((m) => m.t === 'ack' && m.seq === 300, 'ack 300');
    agent.send({
      t: 'cmd_result', seq: 301, id, ok: true, output: 'mobile data disabled', error: null, duration_ms: 310,
      revert_deadline: iso(clock.now() + 120_000), ts: now(),
    });
    await agent.waitFor((m) => m.t === 'ack' && m.seq === 301, 'ack 301');
    const armed = await http('GET', '/api/device');
    expect(armed.body.pending_reverts).toEqual([expect.objectContaining({ id, status: 'revert_armed' })]);

    agent.send({ t: 'probe', id: 'p_2' });
    await agent.waitFor((m) => m.t === 'probe_ack' && m.id === 'p_2', 'probe_ack p_2');
    agent.send({ t: 'cmd_revert', seq: 302, id, state: 'kept', detail: null, ts: now() });
    await agent.waitFor((m) => m.t === 'ack' && m.seq === 302, 'ack 302');
    // A late duplicate ack must not move it backwards.
    agent.send({ t: 'cmd_ack', seq: 303, id, accepted: true, ts: now() });
    await agent.waitFor((m) => m.t === 'ack' && m.seq === 303, 'ack 303');

    await dash.waitFor((m) => m.t === 'command' && m.command.id === id && m.command.status === 'kept', 'kept');
    const statuses = dash.messages.filter((m) => m.t === 'command' && m.command.id === id).map((m) => m.command.status);
    expect(statuses).toEqual(['queued', 'sent', 'acked', 'revert_armed', 'kept']);
    const list = await http('GET', '/api/commands?limit=5');
    expect(list.body[0]).toMatchObject({ id, status: 'kept', ok: true, output: 'mobile data disabled' });
  });

  it('answers malformed frames with bad_message', async () => {
    agent.send('{not json');
    const err = await agent.waitFor((m) => m.t === 'error' && m.code === 'bad_message', 'bad_message');
    expect(err.message).toBe('invalid JSON');
  });

  it('replaces an older Agent connection with 4002', async () => {
    const second = await Client.open(`${wsBase}/ws/agent`, { authorization: `Bearer ${deviceToken}` });
    await second.waitFor((m) => m.t === 'hello', 'hello on second connection');
    await agent.waitFor((m) => m.t === 'error' && m.code === 'replaced', 'replaced');
    expect((await agent.waitClosed()).code).toBe(4002);
    agent = second;
  });

  it('unpairs the phone and closes its socket with 4001', async () => {
    const noPw = await http('DELETE', `/api/devices/${deviceId}`, {});
    expect(noPw.status).toBe(401);
    expect(noPw.body.error).toBe('reauth_required');
    const del = await http('DELETE', `/api/devices/${deviceId}`, { password: ADMIN.password });
    expect(del.status).toBe(204);
    expect((await agent.waitClosed()).code).toBe(4001);
    expect((await http('GET', '/api/devices')).body).toEqual([]);
    const reconnect = await Client.open(`${wsBase}/ws/agent`, { authorization: `Bearer ${deviceToken}` });
    expect((await reconnect.waitClosed()).code).toBe(4001);
  });

  it('closes an unauthenticated dashboard socket on a bad token', async () => {
    const c = await Client.open(`${wsBase}/ws/dashboard`);
    c.send({ t: 'auth', token: 'wrong' });
    await c.waitFor((m) => m.t === 'auth_failed', 'auth_failed');
    expect((await c.waitClosed()).code).toBe(4001);
  });
});

describe('graceful shutdown', () => {
  it('closes live sockets with 1001', async () => {
    const clock = new FakeClock(T0);
    const { server } = await makeServer({ clock });
    const base = (await server.listen()).replace('localhost', '127.0.0.1');
    const code = await server.ctx.devices.createPairCode(null, 'admin', base);
    const paired = await server.ctx.devices.pair(code.code, {
      manufacturer: 'x', model: 'y', device: 'z', android_version: '14', sdk: 34, agent_version: '0.1.0', profile_id: 'generic', rooted: true,
    }, base);
    const agent = await Client.open(`${base.replace(/^http/, 'ws')}/ws/agent`, { authorization: `Bearer ${paired.device_token}` });
    await agent.waitFor((m) => m.t === 'hello', 'hello');
    agent.send({ t: 'heartbeat', ts: iso(clock.now()), uptime_s: 1, agent_uptime_s: 1, outbox: 7, settings_version: 1 });
    await new Promise((r) => setTimeout(r, 50));
    await server.close();
    expect((await agent.waitClosed()).code).toBe(1001);
  });
});
