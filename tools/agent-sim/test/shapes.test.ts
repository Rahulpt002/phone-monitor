import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_SETTINGS, TIER_GROUPS, signalScore } from '@hsm/shared';
import { SimClock } from '../src/clock.js';
import { makeEvent } from '../src/events.js';
import { Phone } from '../src/phone/phone.js';
import { Random } from '../src/rng.js';
import { expectSameShape, fixture, helloMsg, makeAgent, outboxOf, type Json } from './helpers.js';

const agentToServer: Json[] = fixture('agent-to-server.json');
const fx = (t: string, pred: (m: Json) => boolean = () => true): Json => {
  const m = agentToServer.find((x) => x.t === t && pred(x));
  if (!m) throw new Error(`fixture ${t} missing`);
  return m;
};

function phone(seed = 1): Phone {
  const clock = new SimClock();
  const now = clock.now();
  return new Phone({
    rng: new Random(seed),
    clock,
    settings: DEFAULT_SETTINGS,
    bootCount: 42,
    bootAt: now - 86_400_000,
    bootReason: 'reboot,userrequested',
    imeiTail: '4821',
  });
}

describe('telemetry generators match the shared fixtures', () => {
  it('fast tier: radio (with NR leg and neighbours) and net', () => {
    const p = phone();
    for (let i = 0; i < 5; i++) p.tick(1);
    const groups = p.fastGroups();
    expect(Object.keys(groups).sort()).toEqual([...TIER_GROUPS.fast].sort());
    const f = fx('telemetry', (m) => m.tier === 'fast');
    expect(groups.radio.net_type).toBe('NR_NSA');
    expect(groups.radio.nr).not.toBeNull();
    expectSameShape(groups.radio, f.radio, '$.radio');
    expectSameShape(groups.net, f.net, '$.net');
  });

  it('slow tier: power, heat, system, tether, traffic, position', () => {
    const p = phone();
    for (let i = 0; i < 5; i++) p.tick(1);
    const groups = p.slowGroups(0);
    expect(Object.keys(groups).sort()).toEqual([...TIER_GROUPS.slow].sort());
    const f = fx('telemetry', (m) => m.tier === 'slow');
    for (const g of TIER_GROUPS.slow) expectSameShape(groups[g as keyof typeof groups], f[g], `$.${g}`);
    expect(groups.tether.clients?.length).toBe(1);
    expect(groups.traffic.per_if?.rndis0).toBeDefined();
  });

  it('static tier: device and a capability map with network_mode unsupported', () => {
    const p = phone();
    const groups = p.staticGroups('d_x');
    const f = fx('telemetry', (m) => m.tier === 'static');
    expectSameShape(groups.device, f.device, '$.device');
    expectSameShape(groups.capabilities, f.capabilities, '$.capabilities');
    expect(groups.capabilities.network_mode).toEqual(f.capabilities.network_mode);
    expect(groups.capabilities.mobile_data?.supported).toBe(true);
    expect(groups.device.device_id).toBe('d_x');
  });

  it('keeps every radio key (as null) when the radio is off', () => {
    const p = phone();
    p.flags.airplane = true;
    p.refreshReading();
    const { radio, net } = p.fastGroups();
    const f = fx('telemetry', (m) => m.tier === 'fast');
    expect(Object.keys(radio).sort()).toEqual(Object.keys(f.radio).sort());
    expect(Object.keys(net).sort()).toEqual(Object.keys(f.net).sort());
    expect(radio.rsrp).toBeNull();
    expect(radio.score).toBeNull();
    expect(net.online).toBe(false);
  });

  it('radio score is the smoothed signalScore of recent readings', () => {
    const p = phone(3);
    p.tick(1);
    const r = p.lastReading!;
    expect(p.rawScore).toBe(signalScore(r.rsrp, r.sinr, r.rsrq));
    expect(p.fastGroups().radio.score).toBe(p.score);
  });

  it('events have the fixture shape', () => {
    const ev = makeEvent(
      { kind: 'watchdog_action', severity: 'warning', message: 'x', data: { rule: 'internet', action: 'data_cycle', step: 1, outcome: 'recovered' } },
      new Date().toISOString(),
    );
    const f = fx('event');
    expectSameShape({ t: 'event', seq: 1, event: ev }, f);
    expect(ev.id).toMatch(/^e_[0-9A-Za-z]{10}$/);
  });
});

describe('agent messages match the shared fixtures', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('cmd_ack (accepted and rejected), cmd_result and cmd_revert', async () => {
    const agent = makeAgent();
    agent.handleServerMessage(helloMsg({ settings_version: 2 }));
    const now = new Date().toISOString();
    agent.handleServerMessage({ t: 'cmd', id: 'c_1', name: 'mobile_data', args: { enabled: false }, sent_at: now, revert_after_s: 120, revert_confirm: 'agent_link' });
    agent.handleServerMessage({ t: 'cmd', id: 'c_2', name: 'reboot', args: {}, sent_at: new Date(Date.now() - 600_000).toISOString() });
    await vi.advanceTimersByTimeAsync(121_000);

    const acks = outboxOf(agent, 'cmd_ack');
    expectSameShape(acks.find((a) => a.accepted), fx('cmd_ack', (m) => m.accepted));
    expectSameShape(acks.find((a) => !a.accepted), fx('cmd_ack', (m) => !m.accepted));
    const result = outboxOf(agent, 'cmd_result')[0];
    expectSameShape(result, fx('cmd_result', (m) => m.revert_deadline));
    expect(result.revert_deadline).toBeTruthy();
    expectSameShape(outboxOf(agent, 'cmd_revert')[0], fx('cmd_revert'));
    await agent.stop();
  });

  it('finder_spot (with a speed session), finder_spot_deleted, finder_live and sms', async () => {
    const agent = makeAgent();
    agent.handleServerMessage(helloMsg());
    const sent = () => new Date().toISOString();
    agent.handleServerMessage({ t: 'cmd', id: 'c_spot', name: 'finder_spot_test', args: { name: 'Roof NE', orientation: 'upright', duration_s: 10 }, sent_at: sent() });
    await vi.advanceTimersByTimeAsync(12_000);
    const spot = outboxOf(agent, 'finder_spot')[0];
    expect(spot).toBeDefined();
    agent.handleServerMessage({ t: 'cmd', id: 'c_speed', name: 'speed_test', args: { spot_id: spot.spot.id }, sent_at: sent() });
    await vi.advanceTimersByTimeAsync(40_000);
    const spots = outboxOf(agent, 'finder_spot');
    const withSpeed = spots[spots.length - 1];
    expect(withSpeed.spot.speed).toHaveLength(1);
    expect(withSpeed.spot.speed[0].runs).toHaveLength(3);
    expectSameShape(withSpeed, fx('finder_spot'));
    expect(withSpeed.spot.metrics.score.samples).toBe(10);

    agent.handleServerMessage({ t: 'cmd', id: 'c_del', name: 'finder_delete_spot', args: { spot_id: spot.spot.id }, sent_at: sent() });
    await vi.advanceTimersByTimeAsync(1_000);
    expectSameShape(outboxOf(agent, 'finder_spot_deleted')[0], fx('finder_spot_deleted'));

    expectSameShape({ t: 'finder_live', live: agent.finder.live() }, fx('finder_live'));

    agent.handleServerMessage({ t: 'cmd', id: 'c_sms', name: 'send_sms', args: { to: '+15550100', body: 'hello' }, sent_at: sent() });
    await vi.advanceTimersByTimeAsync(3_000);
    const sms = outboxOf(agent, 'sms')[0];
    expectSameShape(sms, fx('sms'));
    expect(sms.sms.direction).toBe('out');
    await agent.stop();
  });
});
