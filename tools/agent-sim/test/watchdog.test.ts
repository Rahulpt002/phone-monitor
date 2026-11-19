import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_SETTINGS, mergeSettings, type Settings } from '@hsm/shared';
import { SimClock } from '../src/clock.js';
import type { EventSpec } from '../src/events.js';
import { LockSet } from '../src/locks.js';
import { Logger } from '../src/log.js';
import { Phone, type PhoneFlags } from '../src/phone/phone.js';
import { Random } from '../src/rng.js';
import { Watchdog } from '../src/watchdog.js';

interface Rig {
  phone: Phone;
  wd: Watchdog;
  events: EventSpec[];
  reboots: string[];
  settings: Settings;
}

function rig(patch: Record<string, unknown> = {}): Rig {
  const clock = new SimClock();
  const settings = mergeSettings(DEFAULT_SETTINGS, patch);
  const phone = new Phone({
    rng: new Random(5),
    clock,
    settings,
    bootCount: 1,
    bootAt: clock.now() - 3_600_000,
    bootReason: 'reboot',
    imeiTail: '0000',
  });
  const r: Rig = { phone, wd: undefined as unknown as Watchdog, events: [], reboots: [], settings };
  let gen = 0;
  r.wd = new Watchdog({
    phone,
    clock,
    log: Logger.silent(),
    locks: new LockSet(),
    settings: () => r.settings,
    emit: (s) => r.events.push(s),
    setFlags: (p: Partial<PhoneFlags>) => Object.assign(phone.flags, p),
    rebootsInWindow: () => r.reboots.length,
    reboot: (source) => {
      r.reboots.push(source);
      gen++;
      phone.flags.internetBroken = false;
    },
    chargeStop: (reason, on, message) => {
      const { before, after } = phone.power.setStop(reason, on);
      if (before !== after) r.events.push({ kind: on ? 'charging_stopped' : 'charging_resumed', severity: 'info', message, data: { reason } });
    },
    generation: () => gen,
  });
  return r;
}

/** Ticks the watchdog once per simulated second, letting its timers run. */
async function runFor(r: Rig, seconds: number): Promise<void> {
  for (let i = 0; i < seconds; i++) {
    r.wd.tick(1);
    await vi.advanceTimersByTimeAsync(1000);
  }
}

const kinds = (r: Rig) => r.events.map((e) => e.kind);

describe('Watchdog (plan 6.5)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('walks the internet ladder: data cycle at 90 s, airplane cycle at 180 s, reboot at 600 s', async () => {
    const r = rig();
    r.phone.flags.internetBroken = true;
    await runFor(r, 89);
    expect(r.events).toEqual([]);
    await runFor(r, 15);
    const first = r.events.find((e) => e.kind === 'watchdog_action');
    expect(first?.data).toEqual({ rule: 'internet', action: 'data_cycle', step: 1, outcome: 'still_down' });
    expect(r.phone.flags.dataEnabled).toBe(true);
    await runFor(r, 100);
    const actions = r.events.filter((e) => e.kind === 'watchdog_action').map((e) => e.data?.action);
    expect(actions).toEqual(['data_cycle', 'airplane_cycle']);
    expect(r.phone.flags.airplane).toBe(false);
    await runFor(r, 400);
    expect(r.reboots).toEqual(['watchdog']);
    const last = r.events.filter((e) => e.kind === 'watchdog_action').at(-1);
    expect(last?.data).toMatchObject({ action: 'reboot', step: 3, outcome: 'rebooting' });
  });

  it('reports a data cycle that fixed the internet as recovered', async () => {
    const r = rig();
    r.phone.flags.internetBroken = true;
    await runFor(r, 92);
    r.phone.flags.internetBroken = false; // the cycle "fixed" it
    await runFor(r, 15);
    expect(r.events.find((e) => e.kind === 'watchdog_action')?.data?.outcome).toBe('recovered');
    await runFor(r, 300);
    expect(r.events.filter((e) => e.kind === 'watchdog_action')).toHaveLength(1);
  });

  it('holds the reboot at the reboot limit and reports reboot_blocked', async () => {
    const r = rig({ watchdog: { internet_fail_s: 5, airplane_after_s: 10, reboot_after_s: 30, max_reboots: 1 } });
    r.reboots.push('dashboard');
    r.phone.flags.internetBroken = true;
    await runFor(r, 60);
    expect(r.reboots).toEqual(['dashboard']);
    expect(kinds(r)).toContain('reboot_blocked');
    expect(r.events.find((e) => e.kind === 'reboot_blocked')?.data).toMatchObject({ source: 'watchdog' });
  });

  it('leaves a deliberate mobile-data-off to auto-revert', async () => {
    const r = rig();
    r.phone.flags.dataEnabled = false;
    await runFor(r, 200);
    expect(kinds(r)).not.toContain('watchdog_action');
  });

  it('turns USB tethering back on after tether_fix_after_s, unless it was switched off on purpose', async () => {
    const r = rig();
    r.phone.flags.usbTether = false;
    await runFor(r, 19);
    expect(r.phone.flags.usbTether).toBe(false);
    await runFor(r, 2);
    expect(r.phone.flags.usbTether).toBe(true);
    expect(r.events.at(-1)?.data).toMatchObject({ rule: 'tether', action: 'usb_tethering' });
    r.phone.flags.usbTether = false;
    r.phone.flags.tetherManualOff = true;
    await runFor(r, 60);
    expect(r.phone.flags.usbTether).toBe(false);
  });

  it('applies the charge limit with hysteresis (stop at 60 %, resume at 50 %)', () => {
    const r = rig();
    r.phone.power.percent = 60.1;
    r.wd.tick(1);
    expect(r.phone.power.stopReason).toBe('limit');
    r.phone.power.percent = 50.5;
    r.wd.tick(1);
    expect(r.phone.power.stopReason).toBe('limit');
    r.phone.power.percent = 49.9;
    r.wd.tick(1);
    expect(r.phone.power.chargingEnabled).toBe(true);
    expect(kinds(r)).toEqual(['charging_stopped', 'charging_resumed']);
  });

  it('heat rules: warning at 40 °C, critical + charging stop at 45 °C, normal below 40 °C, radio rest at 50 °C', async () => {
    const r = rig();
    r.phone.heat.battery = 41;
    r.wd.tick(1);
    expect(kinds(r)).toEqual(['temp_warning']);
    r.phone.heat.battery = 45.5;
    r.wd.tick(1);
    expect(kinds(r)).toEqual(['temp_warning', 'temp_critical', 'charging_stopped']);
    expect(r.phone.power.stopReason).toBe('heat');
    r.phone.heat.battery = 42;
    r.wd.tick(1);
    expect(r.phone.power.stopReason).toBe('heat');
    r.phone.heat.battery = 39.5;
    r.wd.tick(1);
    expect(kinds(r).slice(-2)).toEqual(['temp_normal', 'charging_resumed']);
    r.phone.heat.battery = 50.5;
    r.wd.tick(1);
    expect(r.phone.flags.radioResting).toBe(true);
    expect(kinds(r)).toContain('radio_rest');
    await vi.advanceTimersByTimeAsync(600_000);
    expect(r.phone.flags.radioResting).toBe(false);
  });

  it('reports weak power after the battery falls for weak_power_min while plugged in', () => {
    const r = rig({ watchdog: { weak_power_min: 30 } });
    r.phone.power.supplyMa = 320;
    for (let i = 0; i < 25 * 6; i++) {
      r.phone.tick(10);
      r.wd.tick(10);
    }
    expect(kinds(r)).not.toContain('weak_power');
    for (let i = 0; i < 10 * 6; i++) {
      r.phone.tick(10);
      r.wd.tick(10);
    }
    const weak = r.events.filter((e) => e.kind === 'weak_power');
    expect(weak).toHaveLength(1);
    expect(weak[0]?.data?.current_ma as number).toBeLessThan(-60);
  });

  it('does not call a healthy slate-mode drain weak power', () => {
    const r = rig();
    r.phone.power.setStop('limit', true);
    for (let i = 0; i < 360; i++) {
      r.phone.tick(10);
      r.wd.tick(10);
    }
    expect(kinds(r)).not.toContain('weak_power');
  });
});
