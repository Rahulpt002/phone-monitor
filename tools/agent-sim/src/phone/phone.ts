import {
  signalScore,
  type CapabilityMap,
  type DeviceInfo,
  type NetType,
  type NetworkMode,
  type Settings,
  type TelemetryGroups,
  type TetherClient,
  type TetherInfo,
} from '@hsm/shared';
import type { SimClock } from '../clock.js';
import { buildCapabilities, mergeProfile, SIM_PROFILE, type DeviceProfile } from '../profile.js';
import type { Random } from '../rng.js';
import { ScoreSmoother } from '../score.js';
import { hourOfDay } from '../util.js';
import { HeatModel } from './heat.js';
import { NetModel } from './net.js';
import { PowerModel } from './power.js';
import { congestion, RadioModel, type RadioReading } from './radio.js';
import { defaultApps, PositionModel, SystemModel, type AppEntry } from './system.js';

export const AGENT_VERSION = '0.1.0-sim';

/** Static facts about the simulated handset (a Galaxy S21 like the shared fixtures). */
export function simDeviceInfo(deviceId: string | null, imeiTail: string, profileId = SIM_PROFILE.id): DeviceInfo {
  return {
    device_id: deviceId,
    manufacturer: 'samsung',
    model: 'SM-G991B',
    device: 'o1s',
    android_version: '13',
    sdk: 33,
    build_id: 'TP1A.220624.014',
    agent_version: AGENT_VERSION,
    agent_version_code: 1,
    profile_id: profileId,
    rooted: true,
    root_impl: 'magisk 27.0',
    imei_tail: imeiTail,
  };
}

/** Switches and conditions the Agent, the watchdog and the scenarios change. */
export interface PhoneFlags {
  dataEnabled: boolean;
  airplane: boolean;
  wifi: boolean;
  bluetooth: boolean;
  hotspot: boolean;
  hotspotSsid: string | null;
  /** A USB host (router / PC) is attached. */
  usbConnected: boolean;
  usbTether: boolean;
  usbFunction: 'rndis' | 'ncm';
  /** Tethering was switched off on purpose: the watchdog's tether fix leaves it alone. */
  tetherManualOff: boolean;
  networkMode: NetworkMode;
  /** A network-mode change is re-registering the modem and has cut the Agent's link. */
  modeSwitchCut: boolean;
  /** Upstream internet outage (internet-loss scenario): the Agent's link is down too. */
  internetBroken: boolean;
  /** watchdog_simulate: only the internet check fails, the link itself stays up (as on the real Agent). */
  probeFailSim: boolean;
  /** Radio rested because of heat (plan 6.5, 50 °C). */
  radioResting: boolean;
}

/**
 * Switches that are system settings on a real phone and so survive an Agent restart. Scenario
 * conditions (internetBroken, probeFailSim), the heat rest and the physical USB link are not saved.
 */
const PERSISTED_FLAGS = [
  'dataEnabled',
  'airplane',
  'wifi',
  'bluetooth',
  'hotspot',
  'hotspotSsid',
  'usbTether',
  'usbFunction',
  'tetherManualOff',
  'networkMode',
  'modeSwitchCut',
] as const satisfies readonly (keyof PhoneFlags)[];

export type PersistedFlags = Partial<Pick<PhoneFlags, (typeof PERSISTED_FLAGS)[number]>>;

/** The persistable switches, ready for the state file. */
export function persistableFlags(flags: PhoneFlags): PersistedFlags {
  const out: Record<string, unknown> = {};
  for (const k of PERSISTED_FLAGS) out[k] = flags[k];
  return out as PersistedFlags;
}

/** Restores saved switches, ignoring anything with the wrong type. */
export function restoreFlags(flags: PhoneFlags, saved: Record<string, unknown> | undefined): void {
  if (!saved) return;
  const target = flags as unknown as Record<string, unknown>;
  for (const k of PERSISTED_FLAGS) {
    const v = saved[k];
    if (v === undefined) continue;
    const current = target[k];
    if (k === 'hotspotSsid' ? v === null || typeof v === 'string' : typeof v === typeof current) target[k] = v;
  }
  if (!['rndis', 'ncm'].includes(flags.usbFunction)) flags.usbFunction = 'rndis';
  if (!['auto', 'nr_lte', 'lte_only', 'lte_wcdma', 'wcdma_only'].includes(flags.networkMode)) flags.networkMode = 'auto';
}

export interface PhoneInit {
  rng: Random;
  clock: SimClock;
  settings: Settings;
  bootCount: number;
  bootAt: number;
  bootReason: string;
  imeiTail: string;
  batteryPct?: number;
}

/** The simulated handset: all physical models plus the telemetry generators. */
export class Phone {
  powered = true;
  readonly flags: PhoneFlags;
  readonly radio: RadioModel;
  readonly power: PowerModel;
  readonly heat: HeatModel;
  readonly net: NetModel;
  readonly system: SystemModel;
  readonly position: PositionModel;
  readonly apps: AppEntry[] = defaultApps();
  readonly imeiTail: string;
  profile: DeviceProfile = SIM_PROFILE;
  chargeLimitPct: number;
  lastReading: RadioReading | null = null;
  rawScore: number | null = null;
  score: number | null = null;
  private settings: Settings;
  private caps: CapabilityMap;
  private readonly smoother: ScoreSmoother;
  private readonly rng: Random;
  private readonly clock: SimClock;
  private readonly usbClientMac: string;
  private readonly hotspotClientMac: string;

  constructor(init: PhoneInit) {
    this.rng = init.rng;
    this.clock = init.clock;
    this.settings = init.settings;
    this.imeiTail = init.imeiTail;
    const now = init.clock.now();
    const hour = hourOfDay(now);
    this.flags = {
      dataEnabled: true,
      airplane: false,
      wifi: false,
      bluetooth: false,
      hotspot: false,
      hotspotSsid: null,
      usbConnected: true,
      usbTether: true,
      usbFunction: init.settings.watchdog.usb_function,
      tetherManualOff: false,
      networkMode: 'auto',
      modeSwitchCut: false,
      internetBroken: false,
      probeFailSim: false,
      radioResting: false,
    };
    this.radio = new RadioModel(init.rng);
    this.power = new PowerModel(init.rng, init.batteryPct);
    this.heat = new HeatModel(hour);
    this.system = new SystemModel(init.rng, now, init.bootCount, init.bootAt, init.bootReason);
    this.net = new NetModel(init.rng, now, init.settings.plan.cycle_start_day, (now - init.bootAt) / 1000);
    this.position = new PositionModel(init.rng, now);
    this.position.step(now);
    this.smoother = new ScoreSmoother(init.settings.finder.smooth_s);
    this.chargeLimitPct = init.settings.watchdog.charge_limit_pct;
    this.usbClientMac = macAddress(init.rng);
    this.hotspotClientMac = macAddress(init.rng);
    this.applySettings(init.settings);
    this.caps = buildCapabilities(this.profile);
    this.takeReading(now);
  }

  /** Applies a new settings copy. Returns true when the effective device profile changed. */
  applySettings(settings: Settings): boolean {
    this.settings = settings;
    this.smoother.setWindow(settings.finder.smooth_s);
    this.chargeLimitPct = settings.watchdog.charge_limit_pct;
    const before = JSON.stringify(this.profile);
    this.profile = mergeProfile(SIM_PROFILE, settings.profile_override);
    const changed = JSON.stringify(this.profile) !== before;
    if (changed) this.caps = buildCapabilities(this.profile);
    return changed;
  }

  /** Re-runs the capability probe (capability_probe command). */
  probeCapabilities(): CapabilityMap {
    this.caps = buildCapabilities(this.profile);
    return this.caps;
  }

  get capabilities(): CapabilityMap {
    return this.caps;
  }

  get radioOn(): boolean {
    return this.powered && !this.flags.airplane && !this.flags.radioResting;
  }

  /** What the Agent's HTTP 204 / ping / DNS check sees. */
  get internetOk(): boolean {
    const f = this.flags;
    return this.radioOn && f.dataEnabled && !f.internetBroken && !f.probeFailSim && !f.modeSwitchCut;
  }

  get tetherOn(): boolean {
    return this.flags.usbConnected && this.flags.usbTether;
  }

  get tetherIf(): string | null {
    return this.tetherOn ? `${this.flags.usbFunction}0` : null;
  }

  netType(): NetType {
    return this.radio.netType(this.flags.networkMode, this.radioOn);
  }

  clientCount(): number {
    return (this.tetherOn ? 1 : 0) + (this.flags.hotspot ? 1 : 0);
  }

  /** Current draw of the phone itself (mA). */
  loadMa(): number {
    const f = this.flags;
    let ma = 260;
    if (this.radioOn) {
      ma += 70;
      if (this.lastReading && this.lastReading.rsrp < -105) ma += 90;
      if (this.radio.nrAttached(f.networkMode)) ma += 60;
    }
    ma += Math.min(260, ((this.net.rxBps + this.net.txBps) / 1e6) * 6);
    if (this.system.screenOn) ma += 120 + this.system.brightness * 0.6;
    if (f.hotspot) ma += 140;
    if (f.wifi) ma += 25;
    if (f.bluetooth) ma += 8;
    return ma;
  }

  /** Radio utilisation 0..1, used for heat, CPU load and process CPU. */
  utilisation(): number {
    return Math.min(1, (this.net.rxBps + this.net.txBps) / 50e6);
  }

  /** Advances the phone by `dt` simulated seconds (called once per real second by the Agent). */
  tick(dt: number): void {
    const now = this.clock.now();
    const hour = hourOfDay(now);
    if (this.radioOn) this.radio.step(dt, hour);
    this.takeReading(now);
    this.net.step(dt, {
      online: this.internetOk,
      hour,
      congestion: congestion(hour),
      sinr: this.lastReading?.sinr ?? null,
      clients: this.clientCount(),
      nowMs: now,
      cycleStartDay: this.settings.plan.cycle_start_day,
    });
    const load = this.loadMa();
    this.power.step(dt, load);
    this.heat.step(dt, {
      hour,
      loadMa: load,
      charging: this.power.plugged && this.power.chargingEnabled && this.power.currentMa > 0,
      util: this.utilisation(),
      radioOn: this.radioOn,
    });
    this.system.step(dt, this.utilisation());
    this.position.step(now);
  }

  /** Phone switched off: only the battery (off-mode charging) and temperatures change. */
  tickOff(dt: number): void {
    this.power.stepOff(dt);
    this.heat.stepOff(dt, hourOfDay(this.clock.now()));
    this.lastReading = null;
    this.rawScore = null;
    this.score = null;
    this.smoother.clear();
  }

  /** Booted: new boot count and pids, fresh interface counters, new carrier address. */
  boot(nowMs: number, reason: string, cold: boolean): void {
    this.powered = true;
    this.system.boot(nowMs, reason);
    this.net.resetInterfaceCounters();
    this.net.renewAddress();
    this.net.downSince = null;
    const f = this.flags;
    // Mobile data, airplane mode and network mode persist across reboots; these do not.
    f.hotspot = false;
    f.radioResting = false;
    f.probeFailSim = false;
    f.modeSwitchCut = false;
    // The default USB configuration is tethering, but after a cold boot the gadget sometimes
    // comes up without it: the watchdog's tether fix then switches it on.
    f.usbTether = f.usbConnected && !cold;
    f.tetherManualOff = false;
    this.smoother.clear();
    this.takeReading(nowMs);
  }

  /** Re-reads the radio right away (after airplane / network-mode changes). */
  refreshReading(): void {
    this.takeReading(this.clock.now());
  }

  private takeReading(now: number): void {
    if (!this.radioOn) {
      this.lastReading = null;
      this.rawScore = null;
      this.score = this.smoother.add(now, null);
      return;
    }
    const r = this.radio.reading();
    this.lastReading = r;
    const f = this.settings.finder;
    const weights = { w_sinr: f.w_sinr, w_rsrp: f.w_rsrp, w_rsrq: f.w_rsrq };
    // 3G has no RSRP, so the LTE-based score is unknown.
    this.rawScore = this.flags.networkMode === 'wcdma_only' ? null : signalScore(r.rsrp, r.sinr, r.rsrq, weights);
    this.score = this.smoother.add(now, this.rawScore);
  }

  tetherInfo(): TetherInfo {
    const f = this.flags;
    const clients: TetherClient[] = [];
    const tetherIf = this.tetherIf;
    if (tetherIf) clients.push({ ip: '192.168.42.100', mac: this.usbClientMac, iface: tetherIf, hostname: null });
    if (f.hotspot) clients.push({ ip: '192.168.43.23', mac: this.hotspotClientMac, iface: 'swlan0', hostname: 'Galaxy-Tab-A' });
    return {
      usb_connected: f.usbConnected,
      usb_function: f.usbConnected ? (f.usbTether ? f.usbFunction : 'mtp') : 'none',
      usb_tether_on: this.tetherOn,
      tether_if: tetherIf,
      tether_ip: tetherIf ? '192.168.42.129' : null,
      hotspot_on: f.hotspot,
      hotspot_ssid: f.hotspot ? f.hotspotSsid : null,
      clients,
    };
  }

  fastGroups(): Required<Pick<TelemetryGroups, 'radio' | 'net'>> {
    const now = this.clock.now();
    const hour = hourOfDay(now);
    return {
      radio: this.radio.info({
        reading: this.lastReading,
        score: this.score,
        radioOn: this.radioOn,
        dataEnabled: this.flags.dataEnabled,
        airplane: this.flags.airplane,
        networkMode: this.flags.networkMode,
        hour,
      }),
      net: this.net.info(this.internetOk, congestion(hour), this.radioOn && this.flags.dataEnabled, (ms) => this.clock.serverIso(ms)),
    };
  }

  slowGroups(rebootsInWindow: number): Required<Pick<TelemetryGroups, 'power' | 'heat' | 'system' | 'tether' | 'traffic' | 'position'>> {
    const now = this.clock.now();
    return {
      power: this.power.info(this.heat.battery, this.profile.charge_control[0]?.path ?? null, this.chargeLimitPct),
      heat: this.heat.info(this.rng, this.flags.radioResting),
      system: this.system.info(now, this.utilisation(), { agent: AGENT_VERSION, android: '13' }, rebootsInWindow, (ms) => this.clock.serverIso(ms)),
      tether: this.tetherInfo(),
      traffic: this.net.traffic(this.tetherIf),
      position: this.position.info((ms) => this.clock.serverIso(ms)),
    };
  }

  staticGroups(deviceId: string | null): Required<Pick<TelemetryGroups, 'device' | 'capabilities'>> {
    return { device: simDeviceInfo(deviceId, this.imeiTail, this.profile.id), capabilities: this.caps };
  }
}

function macAddress(rng: Random): string {
  // Locally administered unicast address.
  const first = ((rng.int(0, 255) & 0xfc) | 0x02).toString(16).padStart(2, '0');
  const rest = Array.from({ length: 5 }, () => rng.int(0, 255).toString(16).padStart(2, '0'));
  return [first, ...rest].join(':');
}
