import type { FinderSpot } from '@hsm/shared';
import { afterEach, describe, expect, it } from 'vitest';
import { BACK_ONLINE_DELAY_MS, cycleStart, sameCell, STARTUP_GRACE_MS } from '../src/services/alerts.js';
import type { DeviceRuntime } from '../src/services/devices.js';
import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import {
  adminWithTotp,
  api,
  connectFakeAgent,
  connectFakeDashboard,
  FakeClock,
  fixture,
  ingest,
  makeServer,
  msgsOfType,
  pairDevice,
  T0,
} from './helpers.js';

interface Sent {
  url: string;
  body: { chat_id: string; text: string; parse_mode: string };
}

function fakeTelegram() {
  const sent: Sent[] = [];
  const fetchFn = (async (url: string | URL | Request, init?: RequestInit) => {
    sent.push({ url: String(url), body: JSON.parse(String(init?.body)) });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  }) as typeof fetch;
  return { sent, fetchFn };
}

const kinds = async (server: HsmServer) =>
  (await server.ctx.db.all<{ kind: string }>('SELECT kind FROM alerts_sent ORDER BY id')).map((r) => r.kind);

const spot = (avg: number, cell_id = 1234): FinderSpot => {
  const s = fixture<Record<string, any>[]>('agent-to-server.json').find((m) => m.t === 'finder_spot')!.spot as FinderSpot;
  return { ...s, id: 's_base', baseline: true, metrics: { ...s.metrics, score: { ...s.metrics.score, avg } }, serving: { ...s.serving, cell_id } };
};

describe('alerts', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  /** Server started long enough ago that the post-restart grace period is over. */
  async function setup(opts: { telegram?: boolean } = {}) {
    const tg = fakeTelegram();
    const pushed: string[] = [];
    clock = new FakeClock(T0);
    ({ server } = await makeServer({
      clock,
      fetch: tg.fetchFn,
      pushSender: async (sub, payload) => {
        if (sub.endpoint.includes('gone')) throw Object.assign(new Error('gone'), { statusCode: 410 });
        pushed.push(payload);
        return { statusCode: 201 };
      },
      config: opts.telegram === false ? {} : { telegramBotToken: '123:abc', telegramChatId: '42', publicUrl: 'https://hsm.example.com' },
    }));
    clock.advance(STARTUP_GRACE_MS);
    const { dev } = await pairDevice(server);
    return { tg, pushed, dev };
  }

  it('declares a device offline after 180 s without messages, once, and back online with a reason', async () => {
    const { tg, dev } = await setup();
    const dash = connectFakeDashboard(server, 'admin');
    connectFakeAgent(server, dev);
    clock.advance(170_000);
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual([]);
    clock.advance(15_000);
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual(['offline']);
    expect(dev.offlineAlertOpen).toBe(true);
    // The stale socket was dropped.
    expect(server.ctx.hub.agentConnected(dev.id)).toBe(false);
    expect(tg.sent[0]?.body).toMatchObject({ chat_id: '42', parse_mode: 'HTML' });
    expect(tg.sent[0]?.body.text).toContain('<b>Phone offline</b> (critical)');
    expect(tg.sent[0]?.url).toBe('https://api.telegram.org/bot123:abc/sendMessage');
    expect(msgsOfType(dash.sock, 'alert')[0]).toMatchObject({ alert: { kind: 'offline', channels: ['telegram'] } });

    // Persisted: a restart (spin-down) does not alert again.
    const row = await server.ctx.db.get<{ offline_alert_open: number }>('SELECT offline_alert_open FROM devices WHERE id = ?', [dev.id]);
    expect(Number(row?.offline_alert_open)).toBe(1);
    clock.advance(60_000);
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual(['offline']);

    // Phone comes back; the backlog carries a boot event.
    clock.advance(600_000);
    connectFakeAgent(server, dev);
    server.ctx.alerts.onAgentActivity(dev);
    await ingest(server, dev, {
      t: 'event', seq: 1,
      event: { id: 'e_boot', ts: iso(clock.now() - 120_000), kind: 'boot', severity: 'info', message: 'booted', data: { boot_reason: 'power_on' } },
    });
    await server.ctx.alerts.processPendingBackOnline();
    expect(await kinds(server)).toEqual(['offline']);
    clock.advance(BACK_ONLINE_DELAY_MS);
    await server.ctx.alerts.processPendingBackOnline();
    const alerts = await server.ctx.alerts.list(dev.id, 10, null, false);
    expect(alerts[0]).toMatchObject({ kind: 'back_online', severity: 'info' });
    expect(alerts[0]?.message).toContain('phone rebooted (power_on)');
    // Last heard at +90 s, back at +945 s: 14 min offline.
    expect(alerts[0]?.message).toMatch(/^Back online after 14 min\./);
  });

  it('respects the cooldown and skips back online when the offline alert was suppressed', async () => {
    const { dev } = await setup();
    connectFakeAgent(server, dev);
    clock.advance(200_000);
    await server.ctx.alerts.checkOffline();
    server.ctx.alerts.onAgentActivity(dev);
    server.ctx.devices.touch(dev);
    await server.ctx.alerts.processPendingBackOnline(true);
    expect(await kinds(server)).toEqual(['offline', 'back_online']);
    // Offline again 5 minutes later: within the 30 min cooldown.
    clock.advance(200_000);
    await server.ctx.alerts.checkOffline();
    server.ctx.alerts.onAgentActivity(dev);
    await server.ctx.alerts.processPendingBackOnline(true);
    expect(await kinds(server)).toEqual(['offline', 'back_online']);
    // After the cooldown it alerts again.
    server.ctx.devices.touch(dev);
    clock.advance(31 * 60_000);
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual(['offline', 'back_online', 'offline']);
  });

  it('waits for the startup grace period after a restart', async () => {
    clock = new FakeClock(T0);
    ({ server } = await makeServer({ clock }));
    const { dev } = await pairDevice(server);
    dev.lastSeen = T0 - 10 * 60_000; // last heard long before the restart
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual([]);
    clock.advance(STARTUP_GRACE_MS + 1000);
    await server.ctx.alerts.checkOffline();
    expect(await kinds(server)).toEqual(['offline']);
  });

  it('alerts on low battery once per crossing', async () => {
    const { dev } = await setup();
    const slow = (seq: number, percent: number) => ({ t: 'telemetry', seq, ts: iso(clock.now()), tier: 'slow', power: { percent, status: 'discharging' } });
    await ingest(server, dev, slow(1, 30));
    clock.advance(60_000);
    await ingest(server, dev, slow(2, 19));
    clock.advance(60_000);
    await ingest(server, dev, slow(3, 18));
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['low_battery']);
  });

  it('alerts on data quota steps once per cycle', async () => {
    const { dev } = await setup();
    await server.ctx.settings.update(dev, { plan: { quota_gb: 100 } });
    const tr = (seq: number, gb: number) => ({
      t: 'telemetry', seq, ts: iso(clock.now() + seq), tier: 'slow',
      traffic: { cycle_rx: gb * 1e9, cycle_tx: 0, cycle_start: '2026-11-01' },
    });
    await ingest(server, dev, tr(1, 50));
    await ingest(server, dev, tr(2, 85));
    await ingest(server, dev, tr(3, 86));
    await ingest(server, dev, tr(4, 101));
    await new Promise((r) => setTimeout(r, 10));
    const list = await server.ctx.alerts.list(dev.id, 10, null, false);
    expect(list.map((a) => a.title).reverse()).toEqual(['Data quota 80% used', 'Data quota 100% used']);
    expect(list[0]?.severity).toBe('critical');
  });

  it('alerts when tethering stays down while USB is connected', async () => {
    const { dev } = await setup();
    const t = (seq: number, on: boolean) => ({
      t: 'telemetry', seq, ts: iso(clock.now()), tier: 'slow', tether: { usb_connected: true, usb_tether_on: on },
    });
    await ingest(server, dev, t(1, false));
    clock.advance(60_000);
    await ingest(server, dev, t(2, false));
    expect(await kinds(server)).toEqual([]);
    clock.advance(61_000);
    server.ctx.alerts.evaluateConditions(dev, clock.now());
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['tether_down']);
  });

  it('alerts on a signal drop against the baseline and on a changed cell', async () => {
    const { dev } = await setup();
    dev.baseline = spot(70);
    const radio = (seq: number, score: number, cell_id: number) => ({
      t: 'telemetry', seq, ts: iso(clock.now()), tier: 'fast',
      radio: { net_type: 'LTE', rsrp: -100, rsrq: -12, sinr: 3, score, pci: 211, cell_id },
    });
    await ingest(server, dev, radio(1, 30, 999)); // 30 < 60% of 70 (= 42), different cell
    for (let i = 0; i < 16; i++) {
      clock.advance(60_000);
      await ingest(server, dev, radio(2 + i, 30, 999));
    }
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['signal_drop']);
    for (let i = 0; i < 15; i++) {
      clock.advance(60_000);
      await ingest(server, dev, radio(20 + i, 30, 999));
    }
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['signal_drop', 'cell_changed']);
  });

  it('raises event alerts and skips disabled kinds', async () => {
    const { dev, tg } = await setup();
    // A backlog event more than 30 min old is stored but does not alert.
    clock.advance(3_600_000);
    await ingest(server, dev, fixture<Record<string, unknown>[]>('agent-to-server.json').find((m) => m.t === 'event'));
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual([]);
    const ev = (id: string, kind: string, severity = 'warning') => ({
      t: 'event', seq: 1, event: { id, ts: iso(clock.now()), kind, severity, message: `${kind} <now>` },
    });
    await ingest(server, dev, ev('e1', 'watchdog_action'));
    await ingest(server, dev, ev('e1', 'watchdog_action')); // duplicate
    await ingest(server, dev, ev('e2', 'temp_critical', 'critical'));
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['watchdog', 'hot']);
    expect(tg.sent[0]?.body.text).toContain('watchdog_action &lt;now&gt;');
    await server.ctx.settings.update(dev, { alerts: { enabled: { moved: false } } });
    await ingest(server, dev, ev('e3', 'moved'));
    await new Promise((r) => setTimeout(r, 10));
    expect(await kinds(server)).toEqual(['watchdog', 'hot']);
  });

  it('delivers Web Push, removes gone subscriptions and redacts SMS for viewers', async () => {
    const { dev, pushed } = await setup({ telegram: false });
    const { token } = await adminWithTotp(server, clock);
    const sub = (endpoint: string) => ({ subscription: { endpoint, keys: { p256dh: 'BPk', auth: 'x' } } });
    expect((await api(server, 'POST', '/api/push/subscribe', { token, body: sub('https://push.example.com/a') })).status).toBe(204);
    expect((await api(server, 'POST', '/api/push/subscribe', { token, body: sub('https://push.example.com/gone') })).status).toBe(204);
    expect((await api(server, 'POST', '/api/push/subscribe', { token, body: sub('http://127.0.0.1/x') })).status).toBe(400);
    const viewerDash = connectFakeDashboard(server, 'viewer');
    await ingest(server, dev, {
      t: 'event', seq: 1,
      event: { id: 'e_sms', ts: iso(clock.now()), kind: 'sms_received', severity: 'info', message: 'SMS', data: { from: 'JX-JIOINF', body: 'Plan expires' } },
    });
    await new Promise((r) => setTimeout(r, 20));
    expect(pushed).toHaveLength(1);
    expect(JSON.parse(pushed[0]!)).toMatchObject({ kind: 'carrier_sms', body: 'From JX-JIOINF: Plan expires' });
    const remaining = await server.ctx.db.all('SELECT endpoint FROM push_subscriptions');
    expect(remaining).toHaveLength(1);
    const va = msgsOfType(viewerDash.sock, 'alert')[0] as { alert: { message: string } };
    expect(va.alert.message).not.toContain('Plan expires');
    const ve = msgsOfType(viewerDash.sock, 'event')[0] as { event: { message: string; data: unknown } };
    expect(ve.event).toMatchObject({ message: 'SMS received', data: {} });
    // Test alert goes to every channel and is listed.
    const test = await api(server, 'POST', '/api/alerts/test', { token });
    expect(test.status).toBe(200);
    expect(test.body).toMatchObject({ kind: 'server_test', channels: ['push'] });
  });

  it('helpers: cell comparison and billing cycle', () => {
    expect(sameCell({ cell_id: 1, pci: 1, earfcn: 1 }, { cell_id: 1, pci: 2, earfcn: 2 })).toBe(true);
    expect(sameCell({ cell_id: 1, pci: 1, earfcn: null }, { cell_id: 2, pci: 1, earfcn: null })).toBe(false);
    expect(sameCell({ cell_id: null, pci: 5, earfcn: 100 }, { cell_id: null, pci: 5, earfcn: 200 })).toBe(false);
    expect(cycleStart(Date.parse('2026-11-19T00:00:00Z'), 1)).toBe('2026-11-01');
    expect(cycleStart(Date.parse('2026-01-03T00:00:00Z'), 5)).toBe('2025-12-05');
  });
});
