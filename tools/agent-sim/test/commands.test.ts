import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CmdMsg } from '@hsm/shared';
import type { Agent } from '../src/agent.js';
import { validateArgs } from '../src/commands.js';
import { helloMsg, makeAgent, outboxOf, quickSettings, type Json } from './helpers.js';

let n = 0;
function cmd(name: string, args: Record<string, unknown> = {}, extra: Partial<CmdMsg> = {}): CmdMsg {
  return { t: 'cmd', id: `c_${++n}`, name: name as CmdMsg['name'], args, sent_at: new Date().toISOString(), user: 'admin', ...extra };
}

const ackOf = (agent: Agent, id: string): Json => outboxOf(agent, 'cmd_ack').find((m) => m.id === id);
const resultOf = (agent: Agent, id: string): Json => outboxOf(agent, 'cmd_result').find((m) => m.id === id);
const events = (agent: Agent, kind: string): Json[] => outboxOf(agent, 'event').filter((m) => m.event.kind === kind).map((m) => m.event);

async function run(agent: Agent, c: CmdMsg, ms = 10_000): Promise<{ ack: Json; result: Json }> {
  agent.handleServerMessage(c);
  await vi.advanceTimersByTimeAsync(ms);
  return { ack: ackOf(agent, c.id), result: resultOf(agent, c.id) };
}

describe('command checks (docs/protocol.md 3.5)', () => {
  let agent: Agent;
  beforeEach(() => {
    vi.useFakeTimers();
    agent = makeAgent();
    agent.handleServerMessage(helloMsg());
  });
  afterEach(async () => {
    await agent.stop();
    vi.useRealTimers();
  });

  it('rejects a command older than expiry_s by the offset-corrected clock', async () => {
    // The Server clock runs 5 minutes ahead of the phone.
    agent.handleServerMessage(helloMsg({ server_time: new Date(Date.now() + 300_000).toISOString(), settings_version: 2 }));
    expect(agent.currentSettings().offsetMs).toBeGreaterThanOrEqual(299_000);
    const phoneNow = cmd('app_list', {}, { sent_at: new Date().toISOString() });
    const serverNow = cmd('app_list', {}, { sent_at: new Date(Date.now() + 290_000).toISOString() });
    agent.handleServerMessage(phoneNow);
    agent.handleServerMessage(serverNow);
    await vi.advanceTimersByTimeAsync(2000);
    expect(ackOf(agent, phoneNow.id)).toMatchObject({ accepted: false, reason: 'expired' });
    expect(ackOf(agent, serverNow.id)).toMatchObject({ accepted: true, reason: null });
    expect(resultOf(agent, phoneNow.id)).toBeUndefined();
  });

  it('rejects an unparseable sent_at as expired', async () => {
    const { ack } = await run(agent, cmd('app_list', {}, { sent_at: 'yesterday' }));
    expect(ack.reason).toBe('expired');
  });

  it('rejects commands the capability map does not support, and unknown names', async () => {
    expect((await run(agent, cmd('network_mode', { mode: 'lte_only' }))).ack.reason).toBe('unsupported');
    expect((await run(agent, cmd('teleport'))).ack.reason).toBe('unsupported');
  });

  it('blocks power_off unless allow_power_off', async () => {
    expect((await run(agent, cmd('power_off'))).ack.reason).toBe('blocked');
  });

  it('validates arguments', async () => {
    expect((await run(agent, cmd('brightness', { level: 999 }))).ack.reason).toBe('invalid_args');
    expect((await run(agent, cmd('mobile_data', {}))).ack.reason).toBe('invalid_args');
    expect((await run(agent, cmd('ussd', { code: 'balance' }))).ack.reason).toBe('invalid_args');
    expect(validateArgs('hotspot', { enabled: true, password: 'short' })).toMatch(/password/);
    expect(validateArgs('send_sms', { to: '+91 98450 12345', body: 'hi' })).toBeNull();
    expect(validateArgs('watchdog_simulate', { scenario: 'internet_down', duration_s: 300 })).toBeNull();
  });

  it('answers busy while a conflicting command runs', async () => {
    const spot = cmd('finder_spot_test', { name: 'A', duration_s: 30 });
    agent.handleServerMessage(spot);
    await vi.advanceTimersByTimeAsync(1000);
    const speed = cmd('speed_test');
    agent.handleServerMessage(speed);
    await vi.advanceTimersByTimeAsync(31_000);
    expect(ackOf(agent, speed.id)).toMatchObject({ accepted: false, reason: 'busy' });
    expect(resultOf(agent, spot.id)).toMatchObject({ ok: true });
  });

  it('executes a command id only once', async () => {
    const c = cmd('app_list');
    agent.handleServerMessage(c);
    agent.handleServerMessage({ ...c });
    await vi.advanceTimersByTimeAsync(2000);
    expect(outboxOf(agent, 'cmd_ack').filter((m) => m.id === c.id)).toHaveLength(1);
    expect(outboxOf(agent, 'cmd_result').filter((m) => m.id === c.id)).toHaveLength(1);
  });

  /** The Server stored and acknowledged everything buffered so far. */
  const serverAckedAll = (a: Agent) => {
    a.outbox.takeFrames();
    a.outbox.ack(a.outbox.lastSeq);
    expect(a.outbox.size).toBe(0);
  };

  it('a re-sent cmd whose report was already delivered is not run again; its latest report is sent again', async () => {
    // The Server re-sends a 'sent' command without an ack after a reconnect (half-open socket).
    const c = cmd('send_sms', { to: '+15550001111', body: 'hi' });
    const { result } = await run(agent, c, 3000);
    expect(result).toMatchObject({ ok: true });
    expect(outboxOf(agent, 'sms')).toHaveLength(1);
    serverAckedAll(agent);

    agent.handleServerMessage({ ...c });
    await vi.advanceTimersByTimeAsync(3000);
    expect(outboxOf(agent, 'sms')).toHaveLength(0); // not executed a second time
    expect(outboxOf(agent, 'cmd_ack')).toHaveLength(0);
    const again = outboxOf(agent, 'cmd_result');
    expect(again).toHaveLength(1);
    const { seq: _a, ...first } = result;
    const { seq: _b, ...copy } = again[0];
    expect(copy).toEqual(first); // the same report (same ts), under a new seq

    // Another copy while that report is still queued adds nothing: the outbox delivers it.
    agent.handleServerMessage({ ...c });
    await vi.advanceTimersByTimeAsync(1000);
    expect(outboxOf(agent, 'cmd_result')).toHaveLength(1);
  });

  it('a re-sent cmd that is still running re-sends its cmd_ack and still runs once', async () => {
    const c = cmd('finder_spot_test', { name: 'dup', duration_s: 20 });
    agent.handleServerMessage(c);
    await vi.advanceTimersByTimeAsync(1000);
    expect(ackOf(agent, c.id)).toMatchObject({ accepted: true });
    serverAckedAll(agent);
    agent.handleServerMessage({ ...c });
    expect(outboxOf(agent, 'cmd_ack')).toEqual([expect.objectContaining({ id: c.id, accepted: true })]);
    await vi.advanceTimersByTimeAsync(25_000);
    expect(outboxOf(agent, 'cmd_result').filter((m) => m.id === c.id)).toHaveLength(1);
    expect(agent.finder.list().filter((s) => s.name === 'dup')).toHaveLength(1);
  });

  it('a re-sent cmd that was rejected re-sends the rejection', async () => {
    const c = cmd('brightness', { level: 999 });
    expect((await run(agent, c, 500)).ack).toMatchObject({ accepted: false, reason: 'invalid_args' });
    serverAckedAll(agent);
    agent.handleServerMessage({ ...c });
    expect(outboxOf(agent, 'cmd_ack')).toEqual([expect.objectContaining({ id: c.id, accepted: false, reason: 'invalid_args' })]);
    expect(outboxOf(agent, 'cmd_result')).toHaveLength(0);
  });

  it('enforces the reboot limit across reboots and reports reboot_blocked', async () => {
    const before = agent.phone.system.bootCount;
    for (let i = 0; i < 3; i++) {
      const r = await run(agent, cmd('reboot'), 30_000);
      expect(r.ack.accepted).toBe(true);
      expect(r.result).toMatchObject({ ok: true });
      expect(agent.phone.powered).toBe(true);
    }
    expect(agent.phone.system.bootCount).toBe(before + 3);
    expect(events(agent, 'boot')).toHaveLength(3);
    expect(events(agent, 'agent_start').filter((e) => e.data.reason === 'boot')).toHaveLength(3);
    const fourth = await run(agent, cmd('reboot'), 1000);
    expect(fourth.ack).toMatchObject({ accepted: false, reason: 'reboot_limit' });
    expect(events(agent, 'reboot_blocked')[0].data).toMatchObject({ source: 'dashboard', reboots_in_window: 3 });
    expect(agent.rebootsInWindow()).toBe(3);
    // The window slides: after reboot_window_h the limit frees up again.
    await vi.advanceTimersByTimeAsync(6 * 3_600_000);
    expect(agent.rebootsInWindow()).toBe(0);
  });

  it('simulated reboot takes the phone down for about 20 s', async () => {
    agent.handleServerMessage(cmd('reboot'));
    await vi.advanceTimersByTimeAsync(1000);
    expect(agent.phone.powered).toBe(false);
    await vi.advanceTimersByTimeAsync(20_000);
    expect(agent.phone.powered).toBe(true);
    expect(events(agent, 'boot')[0].data).toMatchObject({ boot_reason: 'reboot,userrequested' });
  });
});

describe('command effects and outputs', () => {
  let agent: Agent;
  beforeEach(() => {
    vi.useFakeTimers();
    agent = makeAgent();
    agent.handleServerMessage(helloMsg({ settings: quickSettings({ commands: { allow_power_off: false } }) }));
  });
  afterEach(async () => {
    await agent.stop();
    vi.useRealTimers();
  });

  it('app_list returns a JSON array of {pkg, enabled, system}', async () => {
    const { result } = await run(agent, cmd('app_list'));
    const apps = JSON.parse(result.output);
    expect(Array.isArray(apps)).toBe(true);
    expect(apps[0]).toEqual({ pkg: 'com.hotspotmonitor.agent', enabled: true, system: false });
  });

  it('app_disable / app_enable change the package state', async () => {
    const pkg = 'com.android.chrome';
    expect((await run(agent, cmd('app_disable', { package: pkg }))).result.output).toBe(`Package ${pkg} new state: disabled-user`);
    expect(agent.phone.apps.find((a) => a.pkg === pkg)?.enabled).toBe(false);
    expect((await run(agent, cmd('app_enable', { package: pkg }))).result.ok).toBe(true);
    expect((await run(agent, cmd('app_force_stop', { package: 'com.nope.app' }))).result).toMatchObject({ ok: false, error: 'Unknown package: com.nope.app' });
  });

  it('process_list is top -n 1 text', async () => {
    const { result } = await run(agent, cmd('process_list'));
    expect(result.output).toContain('PID USER');
    expect(result.output).toContain('com.hotspotmonitor.agent');
  });

  it('shell answers like a root shell and reports exit codes', async () => {
    expect((await run(agent, cmd('shell', { cmd: 'id' }))).result.output).toMatch(/^uid=0\(root\)/);
    expect((await run(agent, cmd('shell', { cmd: 'echo hello && getprop ro.product.model' }))).result.output).toBe('hello\nSM-G991B');
    const bad = (await run(agent, cmd('shell', { cmd: 'frobnicate' }))).result;
    expect(bad).toMatchObject({ ok: false, error: 'exit code 127' });
    expect(bad.output).toContain('inaccessible or not found');
  });

  it('hotspot applies a new password without writing it to the log (CommandSpec.secret_args)', async () => {
    const lines: string[] = [];
    const spy = vi.spyOn(agent.log, 'info').mockImplementation((_tag: string, msg: string) => void lines.push(msg));
    const secret = 'sim-secret-pass-123';
    const { ack, result } = await run(agent, cmd('hotspot', { enabled: true, ssid: 'E2E-AP', password: secret }), 4000);
    spy.mockRestore();
    expect(ack.accepted).toBe(true);
    expect(result.output).toBe('hotspot on: SSID "E2E-AP", WPA2, new password set');
    expect(agent.phone.flags.hotspot).toBe(true);
    expect(lines.some((l) => l.includes('"password":"<redacted>"'))).toBe(true);
    expect(lines.join('\n')).not.toContain(secret);
    expect(JSON.stringify(agent.outbox.peek())).not.toContain(secret);
  });

  it('ussd returns the carrier reply', async () => {
    const { result } = await run(agent, cmd('ussd', { code: '*121#' }));
    expect(result.ok).toBe(true);
    expect(result.output).toMatch(/Main Bal/);
  });

  it('send_sms records an outgoing SMS; a short code answers with an incoming one', async () => {
    const { result } = await run(agent, cmd('send_sms', { to: '121', body: 'BAL' }), 3000);
    expect(result).toMatchObject({ ok: true });
    await vi.advanceTimersByTimeAsync(10_000);
    const sms = outboxOf(agent, 'sms').map((m) => m.sms);
    expect(sms.map((s) => s.direction)).toEqual(['out', 'in']);
    expect(sms[0]).toMatchObject({ peer: '121', body: 'BAL' });
    expect(events(agent, 'sms_received')).toHaveLength(1);
  });

  it('charging and charge_limit drive the power model', async () => {
    await run(agent, cmd('charging', { enabled: false }));
    expect(agent.phone.power.stopReason).toBe('manual');
    expect(events(agent, 'charging_stopped').at(-1)?.data).toEqual({ reason: 'manual' });
    await run(agent, cmd('charging', { enabled: true }));
    expect(agent.phone.power.hasStop('manual')).toBe(false);
    expect((await run(agent, cmd('charge_limit', { percent: 55 }))).result.output).toBe('charge limit 55% (resume at 45%)');
    expect(agent.phone.chargeLimitPct).toBe(55);
  });

  it('usb_tethering off arms a dashboard revert that cmd_keep keeps', async () => {
    const c = cmd('usb_tethering', { enabled: false }, { revert_after_s: 120, revert_confirm: 'dashboard' });
    const { result } = await run(agent, c, 2000);
    expect(result.revert_deadline).toBeTruthy();
    expect(agent.phone.tetherOn).toBe(false);
    expect(events(agent, 'tether_down')).toHaveLength(1);
    agent.handleServerMessage({ t: 'cmd_keep', id: c.id });
    expect(outboxOf(agent, 'cmd_revert')[0]).toMatchObject({ id: c.id, state: 'kept' });
    await vi.advanceTimersByTimeAsync(200_000);
    expect(agent.phone.tetherOn).toBe(false);
  });

  it('mobile data off reverts at the deadline without a server round-trip', async () => {
    const c = cmd('mobile_data', { enabled: false }, { revert_after_s: 120, revert_confirm: 'agent_link' });
    const { result } = await run(agent, c, 2000);
    expect(result).toMatchObject({ ok: true, output: 'mobile data disabled' });
    expect(Date.parse(result.revert_deadline) - Date.parse(result.ts)).toBeGreaterThan(115_000);
    expect(agent.phone.flags.dataEnabled).toBe(false);
    await vi.advanceTimersByTimeAsync(120_000);
    expect(agent.phone.flags.dataEnabled).toBe(true);
    expect(outboxOf(agent, 'cmd_revert')[0]).toMatchObject({ id: c.id, state: 'reverted' });
    expect(events(agent, 'revert')[0].data).toEqual({ command_id: c.id, name: 'mobile_data' });
  });

  it('arms auto-revert even when the Server omitted revert_after_s', async () => {
    const c = cmd('airplane_mode', { enabled: true });
    const { result } = await run(agent, c, 2000);
    expect(result.revert_deadline).toBeTruthy();
    expect(agent.reverts.get(c.id)?.confirm).toBe('agent_link');
  });

  it('a later "mobile data on" supersedes an armed "mobile data off"', async () => {
    const off = cmd('mobile_data', { enabled: false }, { revert_after_s: 120, revert_confirm: 'agent_link' });
    await run(agent, off, 2000);
    await run(agent, cmd('mobile_data', { enabled: true }), 2000);
    expect(agent.reverts.size).toBe(0);
    expect(outboxOf(agent, 'cmd_revert')[0]).toMatchObject({ id: off.id, state: 'kept', detail: expect.stringContaining('Superseded by a newer') });
    expect(events(agent, 'revert')).toHaveLength(0);
  });

  it('a newer change that arms its own revert inherits the earlier undo (Kotlin parity)', async () => {
    const t1 = cmd('usb_tethering', { enabled: false }, { revert_after_s: 120, revert_confirm: 'agent_link' });
    await run(agent, t1, 2000);
    const t2 = cmd('usb_tethering', { enabled: true, function: 'ncm' }, { revert_after_s: 120, revert_confirm: 'agent_link' });
    await run(agent, t2, 2000);
    expect(outboxOf(agent, 'cmd_revert')[0]).toMatchObject({ id: t1.id, state: 'kept' });
    expect(agent.reverts.size).toBe(1);
    expect(agent.reverts.get(t2.id)?.undo).toEqual(expect.objectContaining({ name: 'usb_tethering', args: expect.objectContaining({ enabled: true }) }));
  });

  it('capability_probe and refresh send the static tier', async () => {
    const before = outboxOf(agent, 'telemetry').filter((m) => m.tier === 'static').length;
    const probe = await run(agent, cmd('capability_probe'), 5000);
    expect(probe.result.output).toMatch(/29 of 30 commands supported; not supported: network_mode/);
    await run(agent, cmd('refresh'), 1000);
    const tiers = outboxOf(agent, 'telemetry').map((m) => m.tier);
    expect(tiers.filter((t) => t === 'static').length).toBe(before + 2);
    expect(tiers).toContain('slow');
  });

  it('a profile override can make network_mode supported', async () => {
    agent.handleServerMessage({
      t: 'settings',
      version: 5,
      settings: quickSettings({ profile_override: { network_mode: { lte_only: '11', auto: '33' } } }),
    });
    expect(agent.phone.capabilities.network_mode?.supported).toBe(true);
    expect(events(agent, 'settings_applied').at(-1)?.data).toEqual({ version: 5 });
    const { ack, result } = await run(agent, cmd('network_mode', { mode: 'lte_only' }, { revert_after_s: 120, revert_confirm: 'agent_link' }), 3000);
    expect(ack.accepted).toBe(true);
    expect(result.output).toContain('lte_only');
    expect(agent.phone.netType()).toBe('LTE');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(agent.phone.flags.networkMode).toBe('auto');
  });

  it('watchdog_simulate makes the internet check fail for the duration', async () => {
    const { result } = await run(agent, cmd('watchdog_simulate', { scenario: 'internet_down', duration_s: 60 }), 1000);
    expect(result.ok).toBe(true);
    expect(agent.phone.internetOk).toBe(false);
    expect(agent.phone.flags.internetBroken).toBe(false); // the link itself stays up
  });

  it('finder_set_baseline flags exactly one spot', async () => {
    await run(agent, cmd('finder_spot_test', { name: 'A', duration_s: 5 }), 7000);
    await run(agent, cmd('finder_spot_test', { name: 'B', duration_s: 5, orientation: 'flat' }), 7000);
    const [a, b] = agent.finder.list().sort((x, y) => x.name.localeCompare(y.name));
    await run(agent, cmd('finder_set_baseline', { spot_id: a!.id }), 1000);
    await run(agent, cmd('finder_set_baseline', { spot_id: b!.id }), 1000);
    expect(agent.finder.list().filter((s) => s.baseline).map((s) => s.name)).toEqual(['B']);
    expect((await run(agent, cmd('finder_set_baseline', { spot_id: 's_missing' }), 1000)).result).toMatchObject({ ok: false });
  });
});

describe('timestamps use the offset-corrected clock (Kotlin Clock.isoNow, ARCHITECTURE.md "Timestamps")', () => {
  let agent: Agent;
  /** Server time: the real clock in these tests. */
  const nearServer = (iso: string, what: string, slackMs = 5000) => {
    expect(typeof iso, what).toBe('string');
    expect(Math.abs(Date.parse(iso) - Date.now()), `${what} = ${iso}`).toBeLessThan(slackMs);
  };
  afterEach(async () => {
    await agent.stop();
    vi.useRealTimers();
  });

  for (const skewS of [300, -300]) {
    it(`a phone clock ${skewS > 0 ? '5 min fast' : '5 min slow'} still stamps ts, at and revert_deadline in server time`, async () => {
      vi.useFakeTimers();
      agent = makeAgent({ clockSkewS: skewS });
      agent.handleServerMessage(helloMsg({ settings: quickSettings({ commands: { allow_power_off: false } }) }));
      expect(Math.round(agent.currentSettings().offsetMs / 1000)).toBe(-skewS);

      for (const t of outboxOf(agent, 'telemetry')) nearServer(t.ts, `telemetry ${t.tier} ts`);
      const slow = outboxOf(agent, 'telemetry').find((m) => m.tier === 'slow');
      expect(Math.abs(Date.parse(slow.system.last_boot_at) + slow.system.uptime_s * 1000 - Date.parse(slow.ts))).toBeLessThan(2000);
      nearServer(slow.position.fix_at, 'position.fix_at', 90_000);
      for (const e of outboxOf(agent, 'event')) nearServer(e.event.ts, `event ${e.event.kind} ts`);

      const off = cmd('mobile_data', { enabled: false }, { revert_after_s: 120, revert_confirm: 'agent_link' });
      const { ack, result } = await run(agent, off, 2000);
      nearServer(ack.ts, 'cmd_ack.ts');
      nearServer(result.ts, 'cmd_result.ts');
      nearServer(result.revert_deadline, 'revert_deadline', 120_000 + 5000);
      expect(Date.parse(result.revert_deadline) - Date.now()).toBeGreaterThan(115_000);
      await vi.advanceTimersByTimeAsync(120_000);
      const rev = outboxOf(agent, 'cmd_revert').find((m) => m.id === off.id);
      expect(rev.state).toBe('reverted');
      nearServer(rev.ts, 'cmd_revert.ts');

      await run(agent, cmd('send_sms', { to: '121', body: 'BAL' }), 3000);
      await vi.advanceTimersByTimeAsync(10_000);
      for (const m of outboxOf(agent, 'sms')) nearServer(m.sms.at, `sms ${m.sms.direction} at`, 15_000);
      const rx = outboxOf(agent, 'event').find((m) => m.event.kind === 'sms_received');
      nearServer(rx.event.data.at, 'sms_received data.at', 15_000);

      await run(agent, cmd('finder_spot_test', { name: 'clock', duration_s: 5 }), 7000);
      const spot = outboxOf(agent, 'finder_spot')[0].spot;
      nearServer(spot.created_at, 'finder_spot.created_at', 15_000);
      await run(agent, cmd('speed_test', { spot_id: spot.id }), 120_000);
      const withSpeed = outboxOf(agent, 'finder_spot').map((m) => m.spot).find((s) => s.id === spot.id && s.speed.length > 0);
      nearServer(withSpeed.speed[0].at, 'speed session at', 130_000);
      for (const r of withSpeed.speed[0].runs) nearServer(r.at, 'speed run at', 130_000);
      nearServer(agent.finder.live().ts, 'finder_live ts');
    });
  }

  it('the internet_down_since stamp is converted to server time too', async () => {
    vi.useFakeTimers();
    agent = makeAgent({ clockSkewS: 600 });
    agent.handleServerMessage(helloMsg());
    agent.phone.flags.probeFailSim = true; // internet check failing
    agent.phone.net.downSince = agent.clock.now() - 60_000; // a minute ago on the phone's clock
    const since = agent.phone.fastGroups().net.internet_down_since as string;
    expect(Math.abs(Date.parse(since) - (Date.now() - 60_000))).toBeLessThan(2000);
  });
});
