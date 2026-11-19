import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Stmt } from '../src/db/client.js';
import { htmlToText, TelegramNotifier } from '../src/notify/telegram.js';
import { BACK_ONLINE_DELAY_MS, BACK_ONLINE_MAX_WAIT_MS, MAX_SMS_IN_ALERT } from '../src/services/alerts.js';
import type { HsmServer } from '../src/server.js';
import { escapeHtml } from '../src/notify/telegram.js';
import { iso } from '../src/util.js';
import { alertKinds, alertRows, alertServer, eventMsg, settle, until } from './core-helpers.js';
import { connectFakeAgent, connectFakeDashboard, type FakeClock, ingest, msgsOfType, pairDevice } from './helpers.js';
import { Client } from './ws-client.js';

const MIN = 60_000;

describe('back online after an outage', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => {
    vi.restoreAllMocks();
    await server?.close();
  });

  /** Online, then silent for `silentMs`: the offline alert fires; the phone reconnects. */
  async function outage(silentMs: number) {
    const s = await alertServer();
    ({ server, clock } = s);
    const { dev } = await pairDevice(server);
    connectFakeAgent(server, dev);
    return { ...s, dev, lastSeen: dev.lastSeen!, silentMs };
  }

  async function goOfflineAndBack(dev: Parameters<typeof connectFakeAgent>[1], silentMs: number) {
    clock.advance(Math.min(silentMs, 181_000));
    await server.ctx.alerts.checkOffline();
    expect(dev.offlineAlertOpen).toBe(true);
    clock.advance(silentMs - Math.min(silentMs, 181_000));
    // As ws/agent.ts does on a connection: activity first, then touch.
    server.ctx.alerts.onAgentActivity(dev);
    connectFakeAgent(server, dev);
  }

  it('names only backlog events as reasons and reports the real outage length (E2E bugs)', async () => {
    const { dev } = await outage(0);
    // Delivered live shortly before the outage: a revert, a user reboot and the Agent restart.
    const t = clock.now();
    await ingest(server, dev,
      eventMsg(1, 'e_rev_live', 'revert', t, { message: 'Auto-revert: usb_tethering undone' }),
      eventMsg(2, 'e_boot_live', 'boot', t + 2000, { data: { boot_reason: 'reboot,userrequested' } }),
      eventMsg(3, 'e_start_live', 'agent_start', t + 2000, { data: { reason: 'boot' } }),
    );
    clock.advance(14_000);
    server.ctx.devices.touch(dev); // heartbeats after them
    const lastSeen = clock.now();

    await goOfflineAndBack(dev, 200_000);
    const backAt = clock.now();
    // The backlog: a revert during the outage.
    await ingest(server, dev, eventMsg(4, 'e_rev_backlog', 'revert', lastSeen + 30_000, { message: 'Auto-revert: mobile_data undone' }));
    clock.advance(BACK_ONLINE_DELAY_MS + 3_000); // processed some time after the reconnect
    await server.ctx.alerts.processPendingBackOnline();

    const rows = await alertRows(server);
    // The live revert alerted on its own ("watchdog") before the outage.
    expect(rows.map((r) => r.kind)).toEqual(['watchdog', 'offline', 'back_online']);
    const back = rows[2]!;
    expect(back.message).toContain('Auto-revert: mobile_data undone');
    expect(back.message).not.toContain('usb_tethering');
    expect(back.message).not.toContain('phone rebooted');
    expect(back.message).not.toContain('Agent restarted');
    // The outage lasted from last seen to the reconnect, not to the time the alert was built.
    expect(JSON.parse(back.data!).offline_s).toBe(Math.round((backAt - lastSeen) / 1000));
  });

  it('waits for the backlog that holds the boot event, and says when the reason is not yet known (F24)', async () => {
    const s = await alertServer();
    ({ server, clock } = s);
    const base = (await server.listen()).replace('localhost', '127.0.0.1').replace(/^http/, 'ws');
    const { dev, token } = await pairDevice(server);
    const open = async () => {
      const c = await Client.open(`${base}/ws/agent`, { authorization: `Bearer ${token}` });
      await c.waitFor((m) => m.t === 'hello', 'hello');
      return c;
    };
    const heartbeat = async (c: Client, outbox: number) => {
      const before = dev.outboxAt;
      c.send({ t: 'heartbeat', ts: iso(clock.now()), uptime_s: 1, agent_uptime_s: 1, outbox, settings_version: 1 });
      await until(() => dev.outboxAt !== before && dev.outbox === outbox, 'heartbeat');
    };

    let agent = await open();
    const lastSeen = clock.now();
    clock.advance(181_000);
    await server.ctx.alerts.checkOffline();
    await agent.waitClosed();
    clock.advance(300_000);
    agent = await open(); // back online; 40 messages still in the outbox
    await heartbeat(agent, 40);
    clock.advance(BACK_ONLINE_DELAY_MS + 5_000);
    await server.ctx.alerts.processPendingBackOnline();
    expect(await alertKinds(server)).toEqual(['offline']); // not yet: the backlog is uploading

    agent.send({ t: 'batch', items: [eventMsg(7, 'e_boot', 'boot', lastSeen + 120_000, { data: { boot_reason: 'watchdog' } })] });
    await agent.waitFor((m) => m.t === 'ack' && m.seq === 7, 'ack 7');
    await heartbeat(agent, 0);
    await server.ctx.alerts.processPendingBackOnline();
    const rows = await alertRows(server);
    expect(rows.map((r) => r.kind)).toEqual(['offline', 'back_online']);
    expect(rows[1]!.message).toContain('phone rebooted (watchdog)');
    expect(JSON.parse(rows[1]!.data!)).toMatchObject({ backlog_drained: true });

    // Second outage, past the offline cooldown: the backlog never drains within the maximum wait.
    clock.advance(31 * MIN);
    await heartbeat(agent, 0);
    clock.advance(181_000);
    await server.ctx.alerts.checkOffline();
    await agent.waitClosed();
    expect(await alertKinds(server)).toEqual(['offline', 'back_online', 'offline']);
    clock.advance(60_000);
    agent = await open();
    await heartbeat(agent, 500);
    clock.advance(BACK_ONLINE_MAX_WAIT_MS - 1000);
    await server.ctx.alerts.processPendingBackOnline();
    expect((await alertKinds(server)).filter((k) => k === 'back_online')).toHaveLength(1);
    clock.advance(1000);
    await server.ctx.alerts.processPendingBackOnline();
    const last = (await alertRows(server)).at(-1)!;
    expect(last.kind).toBe('back_online');
    expect(last.message).toContain('Reason not yet known');
    expect(last.message).toContain('about 500 messages left');
    expect(last.message).not.toContain('no reboot');
    agent.ws.close();
  });

  it('treats the backlog as drained once a heartbeat reported it empty or its messages were stored', async () => {
    const { dev } = await outage(0);
    const alerts = server.ctx.alerts;
    const since = clock.now();
    const at = (ms: number) => since + ms;
    Object.assign(dev, { outboxAt: null, outboxZeroAt: null, ackedSinceOutbox: 0, bufferedAt: at(5_000), storing: 0 });
    // No heartbeat since the reconnect: quiet for BACK_ONLINE_DELAY_MS after the last buffered message.
    expect(alerts.backlogDrained(dev, since, at(5_000 + BACK_ONLINE_DELAY_MS - 1))).toBe(false);
    expect(alerts.backlogDrained(dev, since, at(5_000 + BACK_ONLINE_DELAY_MS))).toBe(true);
    dev.storing = 1;
    expect(alerts.backlogDrained(dev, since, at(60_000))).toBe(false);
    dev.storing = 0;
    // A heartbeat reported 3 waiting: drained once 3 have been stored, however quiet it is.
    Object.assign(dev, { outboxAt: at(1_000), outbox: 3, ackedSinceOutbox: 2 });
    expect(alerts.backlogDrained(dev, since, at(60_000))).toBe(false);
    dev.ackedSinceOutbox = 3;
    expect(alerts.backlogDrained(dev, since, at(1_500))).toBe(true);
    // A heartbeat that reported an empty outbox.
    Object.assign(dev, { outbox: 9, ackedSinceOutbox: 0, outboxZeroAt: at(500) });
    expect(alerts.backlogDrained(dev, since, at(600))).toBe(true);
    // Reports from before the reconnect do not count.
    Object.assign(dev, { outboxZeroAt: since - 1, outboxAt: since - 1 });
    expect(alerts.backlogDrained(dev, since, at(600))).toBe(false);
  });

  it('alerts once per kind for alert-worthy events from a long outage, without repeating the reasons (F12/F25)', async () => {
    const { dev, tg } = await outage(0);
    const lastSeen = clock.now();
    await goOfflineAndBack(dev, 50 * MIN);
    await ingest(server, dev,
      eventMsg(1, 'e_wd', 'watchdog_action', lastSeen + 5 * MIN, { severity: 'warning', message: 'Watchdog: data cycle' }),
      eventMsg(2, 'e_hot1', 'temp_warning', lastSeen + 9 * MIN, { severity: 'warning', message: 'Battery 41 °C' }),
      eventMsg(3, 'e_hot2', 'temp_critical', lastSeen + 10 * MIN, { severity: 'critical', message: 'Battery 45 °C, charging stopped' }),
      eventMsg(4, 'e_moved', 'moved', lastSeen + 15 * MIN, {
        severity: 'critical', message: 'Phone tilted 20.0° since installation', data: { lat: 12.345671, lon: 76.543219 },
      }),
    );
    await settle();
    expect(await alertKinds(server)).toEqual(['offline']); // nothing on its own: too old
    clock.advance(BACK_ONLINE_DELAY_MS);
    await server.ctx.alerts.processPendingBackOnline();
    const rows = await alertRows(server);
    expect(rows.map((r) => r.kind)).toEqual(['offline', 'back_online', 'hot', 'moved']);
    expect(rows[1]!.message).toContain('Watchdog: data cycle');
    const hot = rows[2]!;
    expect(hot).toMatchObject({ severity: 'critical', title: 'Phone is critically hot (reported late)' });
    expect(hot.message).toContain('2 times between');
    expect(hot.message).toContain('Battery 45 °C');
    expect(JSON.parse(hot.data!)).toMatchObject({ late: true, count: 2, event_ids: ['e_hot1', 'e_hot2'] });
    const moved = rows[3]!;
    expect(moved.message).toContain('12.34567, 76.54322');
    expect(moved.message).toContain('https://www.openstreetmap.org/?mlat=12.34567&mlon=76.54322');
    // The watchdog action was named in "back online" and is not repeated.
    expect(rows.some((r) => r.kind === 'watchdog')).toBe(false);
    expect(tg.sent.at(-1)!.body.text).toContain('mlat=12.34567&amp;mlon=76.54322');

    // A resend of the same events (lost ack) raises nothing new.
    await ingest(server, dev, eventMsg(3, 'e_hot2', 'temp_critical', lastSeen + 10 * MIN, { severity: 'critical' }));
    await settle();
    clock.advance(BACK_ONLINE_MAX_WAIT_MS);
    await server.ctx.alerts.processPendingBackOnline();
    expect(await alertKinds(server)).toEqual(['offline', 'back_online', 'hot', 'moved']);
  });

  it('alerts late events that arrive while the phone stayed connected (backlog after a server-side gap)', async () => {
    const { dev } = await outage(0);
    clock.advance(60 * MIN);
    server.ctx.devices.touch(dev); // heartbeats kept it online, but nothing was stored
    await ingest(server, dev, eventMsg(1, 'e_weak', 'weak_power', clock.now() - 50 * MIN, { severity: 'warning', message: 'Battery falling while plugged in' }));
    await settle();
    await server.ctx.alerts.processPendingBackOnline();
    expect(await alertKinds(server)).toEqual([]);
    clock.advance(BACK_ONLINE_DELAY_MS);
    await server.ctx.alerts.processPendingBackOnline();
    const rows = await alertRows(server);
    expect(rows.map((r) => r.kind)).toEqual(['weak_power']);
    expect(rows[0]!.message).toContain('reported late');
  });

  it('puts the location in a live "moved" alert (F32)', async () => {
    const { dev, tg } = await outage(0);
    await ingest(server, dev, eventMsg(1, 'e_mv', 'moved', clock.now(), {
      severity: 'critical', message: 'Phone moved 150.0 m since installation', data: { lat: -33.8688, lon: 151.2093 },
    }));
    await settle();
    const [row] = await alertRows(server);
    expect(row!.message).toBe(
      'Phone moved 150.0 m since installation Location: -33.86880, 151.20930 https://www.openstreetmap.org/?mlat=-33.86880&mlon=151.20930#map=17/-33.86880/151.20930',
    );
    expect(JSON.parse(row!.data!)).toMatchObject({ event_id: 'e_mv', lat: -33.8688, lon: 151.2093, map_url: expect.stringContaining('openstreetmap.org') });
    expect(tg.sent[0]!.body.parse_mode).toBe('HTML');
  });

  it('still raises the offline alert when persisting the offline state fails (F02)', async () => {
    const { dev, tg } = await outage(0);
    const db = server.ctx.db;
    const real = db.batch.bind(db);
    let failures = 0;
    vi.spyOn(db, 'batch').mockImplementation(async (stmts: Stmt[]) => {
      if (failures === 0 && stmts[0]?.sql.includes('UPDATE devices SET last_seen')) {
        failures++;
        throw new Error('fetch failed: transient');
      }
      return real(stmts);
    });
    clock.advance(181_000);
    await server.ctx.alerts.checkOffline();
    expect(failures).toBe(1);
    expect(await alertKinds(server)).toEqual(['offline']);
    expect(tg.sent).toHaveLength(1);
    expect(dev.alertState.offlineAlertSent).toBe(true);
    expect(dev.dirty).toBe(true);
    await server.ctx.devices.flush();
    const row = await db.get<{ offline_alert_open: number }>('SELECT offline_alert_open FROM devices WHERE id = ?', [dev.id]);
    expect(Number(row?.offline_alert_open)).toBe(1);
  });

  it('sends "back online" when the phone returns while the offline alert is still being sent (F42)', async () => {
    const { dev, tg } = await outage(0);
    let release!: () => void;
    tg.state.gate = new Promise<void>((r) => (release = r));
    clock.advance(181_000);
    const checking = server.ctx.alerts.checkOffline();
    await until(() => tg.sent.length === 1, 'offline alert on its way');
    // The phone reconnects during the Telegram request.
    server.ctx.alerts.onAgentActivity(dev);
    connectFakeAgent(server, dev);
    release();
    tg.state.gate = null;
    await checking;
    clock.advance(BACK_ONLINE_DELAY_MS);
    await server.ctx.alerts.processPendingBackOnline();
    expect(await alertKinds(server)).toEqual(['offline', 'back_online']);
    expect(dev.alertState.offlineAlertSent).toBe(false);
  });

  it('cuts plain text, never HTML, for Telegram (F50)', async () => {
    const { dev, tg } = await outage(0);
    const body = `${'&'.repeat(3000)}<b>`;
    await ingest(server, dev, eventMsg(1, 'e_sms', 'sms_received', clock.now(), { data: { from: 'JX-JIOINF', body } }));
    await settle();
    const sent = tg.sent[0]!.body;
    expect(sent.parse_mode).toBe('HTML');
    // Every '&' starts a complete entity, and the visible text fits Telegram's limit.
    expect(sent.text).not.toMatch(/&(?!amp;|lt;|gt;|quot;)/);
    expect(htmlToText(sent.text).length).toBeLessThanOrEqual(4096);
    const [row] = await alertRows(server);
    expect(row!.message.length).toBeLessThanOrEqual(`From JX-JIOINF: `.length + MAX_SMS_IN_ALERT + 1);

    // The notifier itself never cuts inside markup: an over-long text goes out as plain text.
    const raw: { text: string; parse_mode?: string }[] = [];
    const notifier = new TelegramNotifier('1:x', '42', server.ctx.log, (async (_u: unknown, init?: RequestInit) => {
      raw.push(JSON.parse(String(init?.body)));
      return new Response('{"ok":true}', { status: 200 });
    }) as typeof fetch);
    expect(await notifier.send(`<b>Title</b>\n${escapeHtml('&'.repeat(5000))}`)).toBe(true);
    expect(raw[0]!.parse_mode).toBeUndefined();
    expect(raw[0]!.text.startsWith('Title\n&&&')).toBe(true);
    expect(raw[0]!.text.length).toBeLessThanOrEqual(4001);
  });

  it('broadcasts a snapshot of a newly paired phone to Dashboards watching every device', async () => {
    const s = await alertServer();
    ({ server, clock } = s);
    const all = connectFakeDashboard(server, 'viewer', { deviceId: null, finder: false });
    const other = connectFakeDashboard(server, 'admin', { deviceId: 'd_other', finder: false });
    const { dev } = await pairDevice(server);
    expect(msgsOfType(all.sock, 'snapshot')).toEqual([
      { t: 'snapshot', device: expect.objectContaining({ device_id: dev.id, online: false, name: 'Test phone' }) },
    ]);
    expect(msgsOfType(other.sock, 'snapshot')).toEqual([]);
  });
});
