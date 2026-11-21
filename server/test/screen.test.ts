import { SCREEN, SCREEN_FRAME_CONT, SCREEN_FRAME_START } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { totpCode } from '../src/auth/totp.js';
import type { HsmServer } from '../src/server.js';
import type { DeviceRuntime } from '../src/services/devices.js';
import { ADMIN, adminWithTotp, api, FakeClock, loginWithTotp, makeServer, pairDevice, T0, userWithTotp } from './helpers.js';
import { Client, type Msg } from './ws-client.js';

/** Records text (parsed) and binary frames separately. */
class RawClient {
  readonly text: Msg[] = [];
  readonly binary: Buffer[] = [];
  closed: { code: number; reason: string } | null = null;

  private constructor(readonly ws: WebSocket) {
    ws.on('message', (data, isBinary) => {
      if (isBinary) this.binary.push(Buffer.from(data as Buffer));
      else this.text.push(JSON.parse(data.toString()) as Msg);
    });
    ws.on('close', (code, reason) => {
      this.closed = { code, reason: reason.toString() };
    });
  }

  static open(url: string, headers: Record<string, string> = {}): Promise<RawClient> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url, { headers });
      const c = new RawClient(ws);
      ws.once('open', () => resolve(c));
      ws.once('error', reject);
    });
  }

  send(msg: unknown) {
    this.ws.send(JSON.stringify(msg));
  }

  async until<T>(check: () => T | undefined | null | false, what: string, timeoutMs = 3000): Promise<T> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const v = check();
      if (v) return v;
      if (Date.now() > deadline) {
        throw new Error(`timed out waiting for ${what}; text=${JSON.stringify(this.text.map((m) => m.t))} binary=${this.binary.length} closed=${JSON.stringify(this.closed)}`);
      }
      await new Promise((r) => setTimeout(r, 20));
    }
  }

  waitText(pred: (m: Msg) => boolean, what: string) {
    return this.until(() => this.text.find(pred), what);
  }

  waitClosed() {
    return this.until(() => this.closed, 'close');
  }
}

/** A START frame: kind byte, scrcpy's 12-byte header, then the first payload chunk. */
function startFrame(flags: { config?: boolean; key?: boolean }, payloadSize: number, chunk: Buffer): Buffer {
  const head = Buffer.alloc(13);
  head[0] = SCREEN_FRAME_START;
  let pf = 1234n;
  if (flags.config) pf |= 1n << 62n;
  if (flags.key) pf |= 1n << 61n;
  head.writeBigUInt64BE(pf, 1);
  head.writeUInt32BE(payloadSize, 9);
  return Buffer.concat([head, chunk]);
}

describe('remote screen', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let base: string;
  let wsBase: string;
  let dev: DeviceRuntime;
  let devToken: string;
  let admin: { token: string; secret: string };
  let agent: Client;
  const sockets: { ws: WebSocket }[] = [];

  const code = () => {
    // A new TOTP step every time, so codes are never replays.
    clock.advance(30_000);
    return totpCode(admin.secret, clock.now());
  };
  const start = (body: Record<string, unknown> = {}, token = admin.token) =>
    api(server, 'POST', '/api/screen/sessions', {
      token,
      body: { device_id: dev.id, quality: 'low', password: ADMIN.password, code: code(), ...body },
    });
  const track = <T extends { ws: WebSocket }>(c: T): T => {
    sockets.push(c);
    return c;
  };
  const openAgentRelay = async (sessionId: string, token = devToken) =>
    track(await RawClient.open(`${wsBase}/ws/screen/agent?session=${sessionId}`, { authorization: `Bearer ${token}` }));
  const openViewer = async (sessionId: string, token = admin.token) => {
    const v = track(await RawClient.open(`${wsBase}/ws/screen/view`));
    v.send({ t: 'screen_auth', token, session_id: sessionId });
    return v;
  };

  beforeEach(async () => {
    clock = new FakeClock(T0);
    ({ server } = await makeServer({ clock }));
    base = (await server.listen()).replace('localhost', '127.0.0.1');
    wsBase = base.replace(/^http/, 'ws');
    ({ dev, token: devToken } = await pairDevice(server));
    dev.state.capabilities = { screen_mirror: { supported: true, method: 'scrcpy 5.0.1' } };
    admin = await adminWithTotp(server, clock);
    agent = track(await Client.open(`${wsBase}/ws/agent`, { authorization: `Bearer ${devToken}` }));
    await agent.waitFor((m) => m.t === 'hello', 'hello');
  });

  afterEach(async () => {
    for (const s of sockets.splice(0)) s.ws.close();
    await server.close();
  });

  it('needs the password and a fresh code, an online phone and screen support', async () => {
    let r = await start({ password: undefined });
    expect(r).toMatchObject({ status: 401, body: { error: 'reauth_required' } });
    r = await start({ password: 'wrong' });
    expect(r).toMatchObject({ status: 401, body: { error: 'reauth_required' } });
    r = await start({ code: '000000' });
    expect(r.status).toBe(400);

    // The same code twice: the second use is a replay.
    const c = code();
    expect((await start({ code: c })).status).toBe(201);
    expect((await start({ code: c })).status).toBe(400);

    dev.state.capabilities = { screen_mirror: { supported: false, note: 'no root' } };
    r = await start();
    expect(r).toMatchObject({ status: 409, body: { error: 'conflict' } });
    expect(r.body.message).toContain('no root');

    dev.state.capabilities = { screen_mirror: { supported: true } };
    agent.ws.close();
    await agent.waitClosed();
    await new Promise((res) => setTimeout(res, 50));
    r = await start();
    expect(r).toMatchObject({ status: 409, body: { error: 'device_offline' } });
  });

  it('is for admins only', async () => {
    const viewer = await userWithTotp(server, clock, admin.token, { username: 'vera', password: 'viewer-password-1', role: 'viewer' });
    clock.advance(30_000);
    const r = await api(server, 'POST', '/api/screen/sessions', {
      token: viewer.token,
      body: { device_id: dev.id, password: 'viewer-password-1', code: totpCode(viewer.secret, clock.now()) },
    });
    expect(r.status).toBe(403);
  });

  it('relays video to the viewer and control to the Agent, then ends with an audit trail', async () => {
    const r = await start();
    expect(r.status).toBe(201);
    const id = r.body.session_id as string;
    expect(r.body).toMatchObject({ device_id: dev.id, quality: 'low', ws_path: '/ws/screen/view' });

    const startMsg = await agent.waitFor((m) => m.t === 'screen_start', 'screen_start');
    expect(startMsg).toMatchObject({ session_id: id, quality: 'low', max_size: 960, bit_rate: 800_000, max_fps: 15, user: 'admin' });

    const relay = await openAgentRelay(id);
    const viewer = await openViewer(id);
    await viewer.waitText((m) => m.t === 'screen_status' && m.state === 'starting', 'status starting');
    // A new viewer gets a key-frame request sent to the Agent.
    await relay.waitText((m) => m.t === 'keyframe', 'keyframe request');

    relay.send({ t: 'screen_meta', codec: 'h264', width: 576, height: 1280, device_name: 'SM-M526B' });
    await viewer.waitText((m) => m.t === 'screen_meta' && m.width === 576, 'meta');
    agent.send({ t: 'screen_state', session_id: id, state: 'streaming' });
    await viewer.waitText((m) => m.t === 'screen_status' && m.state === 'streaming', 'status streaming');

    // A delta frame before any key frame is dropped; config, a split key frame and deltas pass.
    const delta0 = startFrame({}, 3, Buffer.from([9, 9, 9]));
    const config = startFrame({ config: true }, 4, Buffer.from([0, 0, 0, 1]));
    const keyA = startFrame({ key: true }, 6, Buffer.from([1, 2, 3]));
    const keyB = Buffer.from([SCREEN_FRAME_CONT, 4, 5, 6]);
    const delta1 = startFrame({}, 2, Buffer.from([7, 8]));
    for (const f of [delta0, config, keyA, keyB, delta1]) relay.ws.send(f, { binary: true });
    await viewer.until(() => viewer.binary.length >= 4, 'four frames');
    expect(viewer.binary.map((b) => b.toString('hex'))).toEqual([config, keyA, keyB, delta1].map((b) => b.toString('hex')));

    // Control: valid messages are forwarded, invalid ones are not.
    viewer.send({ t: 'touch', action: 'down', pointer: 0, x: 2, y: 0.5 }); // x out of range
    viewer.send({ t: 'touch', action: 'down', pointer: 0, x: 0.5, y: 0.25 });
    viewer.send({ t: 'key', key: 'home' });
    viewer.send({ t: 'text', text: 'x'.repeat(SCREEN.max_text_bytes + 1) }); // too long
    viewer.send({ t: 'key', key: 'back' });
    await relay.waitText((m) => m.t === 'key' && m.key === 'back', 'back key');
    const forwarded = relay.text.filter((m) => m.t !== 'keyframe');
    expect(forwarded).toEqual([
      { t: 'touch', action: 'down', pointer: 0, x: 0.5, y: 0.25 },
      { t: 'key', key: 'home' },
      { t: 'key', key: 'back' },
    ]);

    viewer.send({ t: 'screen_stop' });
    const ended = await viewer.waitText((m) => m.t === 'screen_status' && m.state === 'ended', 'ended');
    expect(ended.reason).toBe('stopped');
    await viewer.waitClosed();
    await relay.waitClosed();
    await agent.waitFor((m) => m.t === 'screen_stop' && m.session_id === id, 'screen_stop to agent');

    const events = await api(server, 'GET', `/api/events?device_id=${dev.id}`, { token: admin.token });
    const screenEvents = (events.body.items ?? events.body).filter((e: Msg) => e.kind === 'screen_session');
    expect(screenEvents.map((e: Msg) => e.data.state).sort()).toEqual(['ended', 'started']);
    const endEv = screenEvents.find((e: Msg) => e.data.state === 'ended');
    expect(endEv.data).toMatchObject({ user: 'admin', session_id: id, reason: 'stopped' });
    expect(endEv.data.bytes).toBeGreaterThan(0);
  });

  it('binds the viewer to the sign-in session that passed the check', async () => {
    const r = await start();
    const id = r.body.session_id as string;
    // Same admin, different sign-in session.
    const other = await loginWithTotp(server, clock, ADMIN, admin.secret);
    const v = await openViewer(id, other);
    const failed = await v.waitText((m) => m.t === 'auth_failed', 'auth_failed');
    expect(failed.message).toContain('another sign-in');
    expect((await v.waitClosed()).code).toBe(4001);

    const bad = await openViewer('scr_nope');
    expect((await bad.waitClosed()).code).toBe(4005);
  });

  it('rejects a relay socket with a wrong token or session', async () => {
    const r = await start();
    const id = r.body.session_id as string;
    const wrongToken = await openAgentRelay(id, 'nope');
    expect((await wrongToken.waitClosed()).code).toBe(4001);
    const wrongSession = await openAgentRelay('scr_other');
    expect((await wrongSession.waitClosed()).code).toBe(4005);
  });

  it('ends a session the Agent never joins, and one whose viewer left', async () => {
    let r = await start();
    let id = r.body.session_id as string;
    clock.advance((SCREEN.agent_connect_timeout_s + 1) * 1000);
    await server.ctx.screen.tick();
    expect(server.ctx.screen.get(id)).toBeUndefined();
    await agent.waitFor((m) => m.t === 'screen_stop' && m.session_id === id && m.reason === 'agent_timeout', 'agent_timeout');

    r = await start();
    id = r.body.session_id as string;
    const relay = await openAgentRelay(id);
    await relay.until(() => server.ctx.screen.get(id)?.agent, 'agent attached');
    clock.advance((SCREEN.viewer_grace_s + 1) * 1000);
    await server.ctx.screen.tick();
    await agent.waitFor((m) => m.t === 'screen_stop' && m.session_id === id && m.reason === 'viewer_gone', 'viewer_gone');
    await relay.waitClosed();
  });

  it('replaces the previous session for the same phone and stops unknown ones on the Agent', async () => {
    const first = (await start()).body.session_id as string;
    const second = (await start()).body.session_id as string;
    expect(second).not.toBe(first);
    await agent.waitFor((m) => m.t === 'screen_stop' && m.session_id === first && m.reason === 'replaced', 'replaced');

    agent.send({ t: 'screen_state', session_id: 'scr_ghost', state: 'streaming' });
    await agent.waitFor((m) => m.t === 'screen_stop' && m.session_id === 'scr_ghost', 'ghost stop');
  });

  it('ends when the Agent reports a failure', async () => {
    const id = (await start()).body.session_id as string;
    const viewer = await openViewer(id);
    await viewer.waitText((m) => m.t === 'screen_status', 'status');
    agent.send({ t: 'screen_state', session_id: id, state: 'failed', error: 'no root' });
    const ended = await viewer.waitText((m) => m.t === 'screen_status' && m.state === 'ended', 'ended');
    expect(ended).toMatchObject({ reason: 'agent_failed' });
    expect(ended.message).toContain('no root');
  });
});
