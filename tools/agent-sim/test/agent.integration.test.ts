import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import type { Agent, AgentExitReason } from '../src/agent.js';
import { StateStore } from '../src/state.js';
import { expectSameShape, FakeServer, fixture, helloMsg, makeAgent, quickSettings, type Json } from './helpers.js';

const heartbeatFixture: Json = (fixture('agent-to-server.json') as Json[]).find((m) => m.t === 'heartbeat');

let server: FakeServer | null = null;
let agent: Agent | null = null;

afterEach(async () => {
  await agent?.stop();
  await server?.close();
  agent = null;
  server = null;
});

const exitOf = (a: Agent) => new Promise<AgentExitReason>((resolve) => a.once('exit', resolve));
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('Agent against an in-process server', () => {
  it('connects with the bearer token, sends static/slow/fast after hello, drains and heartbeats', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const conn = await server.connection(0);
    expect(conn.auth).toBe('Bearer good-token');

    const stat = await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'static');
    const slow = await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'slow');
    const fast = await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast');
    expect(stat.seq).toBeLessThan(slow.seq);
    expect(slow.seq).toBeLessThan(fast.seq);
    expect(stat.device.device_id).toBe('d_test');
    expect(conn.items.some((m) => m.t === 'event' && m.event.kind === 'settings_applied')).toBe(true);

    const hb = await conn.waitFor((m) => m.t === 'heartbeat' && m.outbox === 0, 5000, 'heartbeat with an empty outbox');
    expectSameShape(hb, heartbeatFixture);
    expect(hb.settings_version).toBe(1);

    // More fast telemetry keeps coming at rates.fast_s, each acked and deleted.
    const later = await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast' && m.seq > fast.seq + 2);
    expect(later.radio.net_type).toBeDefined();
    const seqs = conn.items.filter((m) => typeof m.seq === 'number').map((m) => m.seq);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(new Set(seqs).size).toBe(seqs.length);
  }, 15_000);

  it('runs a command end to end: cmd -> cmd_ack -> cmd_result', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast');
    conn.send({ t: 'cmd', id: 'c_apps', name: 'app_list', args: {}, sent_at: new Date().toISOString(), user: 'admin' });
    const ack = await conn.waitFor((m) => m.t === 'cmd_ack' && m.id === 'c_apps');
    expect(ack).toMatchObject({ accepted: true, reason: null });
    const result = await conn.waitFor((m) => m.t === 'cmd_result' && m.id === 'c_apps');
    expect(result.ok).toBe(true);
    expect(JSON.parse(result.output).length).toBeGreaterThan(5);
    expect(ack.seq).toBeLessThan(result.seq);
  }, 15_000);

  it('applies a settings push and reports the new version in the heartbeat', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'heartbeat');
    conn.send({ t: 'settings', version: 9, settings: quickSettings({ watchdog: { charge_limit_pct: 55 } }) });
    const applied = await conn.waitFor((m) => m.t === 'event' && m.event.kind === 'settings_applied' && m.event.data.version === 9);
    expect(applied.event.severity).toBe('info');
    await conn.waitFor((m) => m.t === 'heartbeat' && m.settings_version === 9);
    expect(agent.phone.chargeLimitPct).toBe(55);
  }, 15_000);

  it('streams finder_live while rates.mirror is on', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'heartbeat');
    expect(conn.items.some((m) => m.t === 'finder_live')).toBe(false);
    conn.send({ t: 'rates', fast_s: 0.4, mirror: true });
    const live = await conn.waitFor((m) => m.t === 'finder_live');
    expect(live.live.mode).toBe('walk');
    conn.send({ t: 'rates', fast_s: 0.4, mirror: false });
    await sleep(300);
    const count = conn.items.filter((m) => m.t === 'finder_live').length;
    await sleep(600);
    expect(conn.items.filter((m) => m.t === 'finder_live').length).toBe(count);
  }, 15_000);

  it('buffers during --offline-for and back-fills in a batch after reconnecting', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url, offlineForS: 1.5, offlineAfterS: 0.5 });
    agent.start();
    const first = await server.connection(0);
    await first.waitClosed(5000);
    const firstSeqs: number[] = first.items.filter((m) => typeof m.seq === 'number').map((m) => m.seq);
    const lastSent = Math.max(...firstSeqs);
    const second = await server.connection(1, 5000);
    const batch = await new Promise<Json>((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('no batch')), 5000);
      const check = setInterval(() => {
        const b = second.frames.find((f) => f.t === 'batch');
        if (b) {
          clearTimeout(t);
          clearInterval(check);
          resolve(b);
        }
      }, 20);
    });
    const seqs: number[] = batch.items.map((m: Json) => m.seq);
    expect(seqs.length).toBeGreaterThan(3);
    // Rows whose ack never arrived before the link dropped are sent again; nothing is skipped.
    expect(seqs[0]).toBeLessThanOrEqual(lastSent + 1);
    seqs.forEach((s, i) => i > 0 && expect(s).toBe((seqs[i - 1] as number) + 1));
    const all = new Set([...firstSeqs, ...seqs]);
    for (let s = Math.min(...all); s <= Math.max(...all); s++) expect(all.has(s), `seq ${s}`).toBe(true);
    const tiers = batch.items.filter((m: Json) => m.t === 'telemetry').map((m: Json) => m.tier);
    expect(tiers).toContain('fast');
    expect(tiers).toContain('slow');
  }, 15_000);

  it('mobile data off drops the link; the change reverts at the deadline and the report arrives after reconnecting', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const first = await server.connection(0);
    await first.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast');
    first.send({
      t: 'cmd',
      id: 'c_data',
      name: 'mobile_data',
      args: { enabled: false },
      sent_at: new Date().toISOString(),
      user: 'admin',
      revert_after_s: 1.5,
      revert_confirm: 'agent_link',
    });
    const result = await first.waitFor((m) => m.t === 'cmd_result' && m.id === 'c_data');
    expect(result.revert_deadline).toBeTruthy();
    await first.waitClosed(3000);
    expect(agent.phone.flags.dataEnabled).toBe(false);

    const second = await server.connection(1, 6000);
    const revert = await second.waitFor((m) => m.t === 'cmd_revert' && m.id === 'c_data');
    expect(revert.state).toBe('reverted');
    const ev = await second.waitFor((m) => m.t === 'event' && m.event.kind === 'revert');
    expect(ev.event.data).toEqual({ command_id: 'c_data', name: 'mobile_data' });
    expect(agent.phone.flags.dataEnabled).toBe(true);
  }, 15_000);

  it('with --keep-link the link stays up and the revert manager probes instead', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url, keepLink: true });
    agent.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast');
    conn.send({ t: 'cmd', id: 'c_air', name: 'airplane_mode', args: { enabled: true }, sent_at: new Date().toISOString(), revert_after_s: 120, revert_confirm: 'agent_link' });
    await conn.waitFor((m) => m.t === 'cmd_result' && m.id === 'c_air');
    await sleep(500);
    expect(conn.closed).toBe(false);
    expect(agent.reverts.get('c_air')?.confirm).toBe('agent_link');
    const fast = await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast' && m.radio.airplane === true);
    expect(fast.radio.rsrp).toBeNull();
  }, 15_000);

  it('stops for good on 4001 and asks for re-pairing', async () => {
    server = await FakeServer.start({ token: 'other-token' });
    agent = makeAgent({ wsUrl: server.url });
    const exited = exitOf(agent);
    agent.start();
    expect(await exited).toBe('unauthorized');
    await sleep(600);
    expect(server.conns).toHaveLength(1);
    expect(agent.isStopped).toBe(true);
  }, 15_000);

  it('treats an HTTP 401 on the upgrade like 4001', async () => {
    let attempts = 0;
    const http = createServer();
    http.on('upgrade', (_req, socket) => {
      attempts++;
      socket.end('HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    });
    await new Promise<void>((r) => http.listen(0, '127.0.0.1', () => r()));
    try {
      const { port } = http.address() as AddressInfo;
      agent = makeAgent({ wsUrl: `ws://127.0.0.1:${port}/ws/agent` });
      const exited = exitOf(agent);
      agent.start();
      expect(await exited).toBe('unauthorized');
      await sleep(500);
      expect(attempts).toBe(1);
    } finally {
      http.close();
    }
  }, 15_000);

  it('keeps "mobile data off" across an Agent restart and still reverts it at the deadline', async () => {
    server = await FakeServer.start();
    const store = new StateStore(null);
    const first = makeAgent({ wsUrl: server.url }, store);
    first.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'telemetry' && m.tier === 'fast');
    conn.send({ t: 'cmd', id: 'c_off', name: 'mobile_data', args: { enabled: false }, sent_at: new Date().toISOString(), revert_after_s: 3, revert_confirm: 'agent_link' });
    await conn.waitFor((m) => m.t === 'cmd_result' && m.id === 'c_off');
    await conn.waitClosed(3000);
    await first.stop(); // the Agent process dies; the phone keeps mobile data off

    agent = makeAgent({ wsUrl: server.url }, store);
    expect(agent.phone.flags.dataEnabled).toBe(false);
    agent.start();
    await sleep(800);
    expect(server.conns).toHaveLength(1); // still no link
    const second = await server.connection(1, 6000);
    const revert = await second.waitFor((m) => m.t === 'cmd_revert' && m.id === 'c_off');
    expect(revert.state).toBe('reverted');
    expect(agent.phone.flags.dataEnabled).toBe(true);
  }, 15_000);

  it('stops on 4002 (replaced by another connection)', async () => {
    server = await FakeServer.start();
    agent = makeAgent({ wsUrl: server.url });
    const exited = exitOf(agent);
    agent.start();
    const conn = await server.connection(0);
    await conn.waitFor((m) => m.t === 'heartbeat');
    conn.send({ t: 'error', code: 'replaced', message: 'newer connection' });
    conn.ws.close(4002, 'replaced');
    expect(await exited).toBe('replaced');
    await sleep(600);
    expect(server.conns).toHaveLength(1);
  }, 15_000);

  it('reconnects with backoff when the server drops the socket, and re-sends unacked rows', async () => {
    server = await FakeServer.start({ autoAck: false });
    agent = makeAgent({ wsUrl: server.url });
    agent.start();
    const first = await server.connection(0);
    const firstStatic = await first.waitFor((m) => m.t === 'telemetry' && m.tier === 'static');
    first.ws.terminate();
    const second = await server.connection(1, 5000);
    second.autoAck = true;
    // Nothing was acked on the first socket, so its rows come again (same seq).
    const again = await second.waitFor((m) => m.seq === firstStatic.seq);
    expect(again.tier).toBe('static');
    await second.waitFor((m) => m.t === 'heartbeat' && m.outbox === 0, 5000);
  }, 15_000);

  it('a simulated reboot drops the link and comes back with boot and agent_start events', async () => {
    server = await FakeServer.start({ hello: (n) => helloMsg({ settings_version: 1 + n }) });
    agent = makeAgent({ wsUrl: server.url, speed: 20 });
    agent.start();
    const first = await server.connection(0);
    await first.waitFor((m) => m.t === 'heartbeat');
    first.send({ t: 'cmd', id: 'c_boot', name: 'reboot', args: {}, sent_at: new Date().toISOString(), user: 'admin' });
    await first.waitFor((m) => m.t === 'cmd_result' && m.id === 'c_boot');
    await first.waitClosed(3000);
    const second = await server.connection(1, 6000);
    const boot = await second.waitFor((m) => m.t === 'event' && m.event.kind === 'boot');
    expect(boot.event.data.boot_reason).toBe('reboot,userrequested');
    const start = await second.waitFor((m) => m.t === 'event' && m.event.kind === 'agent_start' && m.event.data.reason === 'boot');
    expect(start.seq).toBeGreaterThan(boot.seq);
    const slow = await second.waitFor((m) => m.t === 'telemetry' && m.tier === 'slow' && m.seq > start.seq);
    expect(slow.system.uptime_s).toBeLessThan(10);
    expect(slow.system.reboots_in_window).toBe(1);
  }, 15_000);
});
