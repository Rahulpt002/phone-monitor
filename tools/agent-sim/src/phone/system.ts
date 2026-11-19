import type { PositionInfo, ProcessInfo, SystemInfo } from '@hsm/shared';
import { ouStep, type Random } from '../rng.js';
import { round1, round2 } from '../util.js';

export const AGENT_PACKAGE = 'com.hotspotmonitor.agent';

export interface AppEntry {
  pkg: string;
  enabled: boolean;
  system: boolean;
}

/** Installed packages on the simulated phone; some bloat is already disabled (plan section 5). */
export function defaultApps(): AppEntry[] {
  return [
    { pkg: AGENT_PACKAGE, enabled: true, system: false },
    { pkg: 'com.topjohnwu.magisk', enabled: true, system: false },
    { pkg: 'com.android.phone', enabled: true, system: true },
    { pkg: 'com.android.systemui', enabled: true, system: true },
    { pkg: 'com.android.settings', enabled: true, system: true },
    { pkg: 'com.android.shell', enabled: true, system: true },
    { pkg: 'com.google.android.gms', enabled: true, system: true },
    { pkg: 'com.android.vending', enabled: true, system: true },
    { pkg: 'com.android.chrome', enabled: true, system: true },
    { pkg: 'com.sec.android.app.samsungapps', enabled: false, system: true },
    { pkg: 'com.samsung.android.bixby.agent', enabled: false, system: true },
    { pkg: 'com.samsung.android.app.spage', enabled: false, system: true },
    { pkg: 'com.samsung.android.game.gamehome', enabled: false, system: true },
    { pkg: 'com.samsung.android.smartswitchassistant', enabled: true, system: true },
  ];
}

interface ProcDef {
  name: string;
  pidOffset: number;
  cpu: number;
  mem: number;
  user: string;
  /** Extra CPU per unit of radio utilisation. */
  trafficCpu?: number;
}

const PROCS: ProcDef[] = [
  { name: AGENT_PACKAGE, pidOffset: 3120, cpu: 1.8, mem: 88, user: 'u0_a245' },
  { name: 'system_server', pidOffset: 380, cpu: 3.2, mem: 310, user: 'system' },
  { name: 'com.android.phone', pidOffset: 1124, cpu: 1.5, mem: 120, user: 'radio', trafficCpu: 1.5 },
  { name: 'netd', pidOffset: 140, cpu: 0.6, mem: 12, user: 'root', trafficCpu: 4 },
  { name: 'rild', pidOffset: 162, cpu: 0.5, mem: 14, user: 'radio', trafficCpu: 0.8 },
  { name: 'surfaceflinger', pidOffset: 171, cpu: 0.3, mem: 45, user: 'system' },
  { name: 'com.google.android.gms.persistent', pidOffset: 1510, cpu: 0.5, mem: 160, user: 'u0_a120' },
  { name: 'com.android.systemui', pidOffset: 900, cpu: 0.2, mem: 140, user: 'u0_a95' },
  { name: 'magiskd', pidOffset: 120, cpu: 0.1, mem: 6, user: 'root' },
  { name: 'kworker/u16:3', pidOffset: 9100, cpu: 0.3, mem: 0, user: 'root', trafficCpu: 0.5 },
];

export interface TopProcess extends ProcessInfo {
  user: string;
}

/** Boot, load, memory, storage, processes and screen. */
export class SystemModel {
  bootAt: number;
  agentStartAt: number;
  bootCount: number;
  bootReason: string;
  screenOn = false;
  brightness = 10;
  private load: [number, number, number];
  private ramAvail: number;
  private storageFree: number;
  private pidBase: number;
  readonly ramTotal = 7680;
  readonly storageTotal = 117_000;

  constructor(private readonly rng: Random, nowMs: number, bootCount: number, bootAt: number, bootReason: string) {
    this.bootAt = bootAt;
    this.agentStartAt = nowMs;
    this.bootCount = bootCount;
    this.bootReason = bootReason;
    this.load = [0.42, 0.5, 0.55];
    this.ramAvail = 3150 + rng.gauss(0, 60);
    this.storageFree = 96_000 + rng.int(0, 400);
    this.pidBase = rng.int(0, 400);
  }

  boot(nowMs: number, reason: string): void {
    this.bootCount++;
    this.bootAt = nowMs;
    this.agentStartAt = nowMs;
    this.bootReason = reason;
    this.screenOn = false;
    this.load = [0.9, 0.6, 0.4];
    this.ramAvail = 3600;
    this.pidBase = this.rng.int(0, 400);
  }

  /** The Agent service was restarted (force-stop + keep-alive): new start time and pid. */
  restartAgent(nowMs: number): void {
    this.agentStartAt = nowMs;
    this.pidBase += 7;
  }

  step(dt: number, util: number): void {
    const instant = 0.25 + util * 1.6 + (this.screenOn ? 0.3 : 0) + Math.abs(this.rng.gauss(0, 0.15));
    const ewma = (prev: number, tau: number) => prev + (instant - prev) * (1 - Math.exp(-dt / tau));
    this.load = [ewma(this.load[0], 60), ewma(this.load[1], 300), ewma(this.load[2], 900)];
    this.ramAvail = ouStep(this.ramAvail, 3150, 120, 600, dt, this.rng);
  }

  top(util: number): TopProcess[] {
    return PROCS.map((p) => {
      const pid = p.name === AGENT_PACKAGE ? p.pidOffset + this.pidBase + 4000 : p.pidOffset + (p.pidOffset > 1000 ? this.pidBase : 0);
      const cpu = p.cpu * (0.6 + 0.8 * this.rng.next()) + (p.trafficCpu ?? 0) * util;
      return { pid, name: p.name, cpu_pct: round1(cpu), mem_mb: p.mem, user: p.user };
    }).sort((a, b) => (b.cpu_pct ?? 0) - (a.cpu_pct ?? 0));
  }

  get cpuLoad(): [number, number, number] {
    return [round2(this.load[0]), round2(this.load[1]), round2(this.load[2])];
  }

  get ramAvailMb(): number {
    return Math.round(this.ramAvail);
  }

  get storageFreeMb(): number {
    return Math.round(this.storageFree);
  }

  info(nowMs: number, util: number, versions: { agent: string; android: string }, rebootsInWindow: number): SystemInfo {
    return {
      uptime_s: Math.max(0, Math.floor((nowMs - this.bootAt) / 1000)),
      boot_count: this.bootCount,
      boot_reason: this.bootReason,
      last_boot_at: new Date(this.bootAt).toISOString(),
      cpu_load: this.cpuLoad,
      ram_total_mb: this.ramTotal,
      ram_avail_mb: this.ramAvailMb,
      storage_total_mb: this.storageTotal,
      storage_free_mb: this.storageFreeMb,
      top: this.top(util).map(({ pid, name, cpu_pct, mem_mb }) => ({ pid, name, cpu_pct, mem_mb })),
      screen_on: this.screenOn,
      agent_version: versions.agent,
      android_version: versions.android,
      agent_uptime_s: Math.max(0, Math.floor((nowMs - this.agentStartAt) / 1000)),
      reboots_in_window: rebootsInWindow,
    };
  }
}

/** Mounting position and orientation, with GPS and accelerometer jitter. */
export class PositionModel {
  private readonly ref = { lat: 12.9716, lon: 77.5946, pitch: 88.5, roll: 1.2, azimuth: 271 };
  private lastFixAt: number;
  private cur = { lat: this.ref.lat, lon: this.ref.lon, pitch: this.ref.pitch, roll: this.ref.roll, azimuth: this.ref.azimuth };

  constructor(private readonly rng: Random, nowMs: number) {
    this.lastFixAt = nowMs - 60_000;
  }

  /** New GPS fix / sensor reading. */
  step(nowMs: number): void {
    const g = (sd: number) => this.rng.gauss(0, sd);
    this.cur = {
      lat: this.ref.lat + g(0.00002),
      lon: this.ref.lon + g(0.00002),
      pitch: this.ref.pitch + g(0.25),
      roll: this.ref.roll + g(0.25),
      azimuth: (this.ref.azimuth + g(1.5) + 360) % 360,
    };
    if (nowMs - this.lastFixAt > 120_000 || this.rng.chance(0.3)) this.lastFixAt = nowMs - this.rng.int(0, 20_000);
  }

  /** Tilt change (degrees) and distance moved (m) against the installed reference. */
  drift(): { tilt: number; moved: number } {
    const tilt = Math.hypot(this.cur.pitch - this.ref.pitch, this.cur.roll - this.ref.roll);
    const dLat = (this.cur.lat - this.ref.lat) * 111_320;
    const dLon = (this.cur.lon - this.ref.lon) * 111_320 * Math.cos((this.ref.lat * Math.PI) / 180);
    return { tilt, moved: Math.hypot(dLat, dLon) };
  }

  get orientation(): { pitch: number; roll: number; azimuth: number } {
    return { pitch: this.cur.pitch, roll: this.cur.roll, azimuth: this.cur.azimuth };
  }

  info(): PositionInfo {
    const { tilt, moved } = this.drift();
    return {
      lat: Math.round(this.cur.lat * 1e6) / 1e6,
      lon: Math.round(this.cur.lon * 1e6) / 1e6,
      acc_m: this.rng.int(6, 15),
      fix_at: new Date(this.lastFixAt).toISOString(),
      pitch_deg: round1(this.cur.pitch),
      roll_deg: round1(this.cur.roll),
      azimuth_deg: Math.round(this.cur.azimuth),
      tilt_change_deg: round1(tilt),
      moved_m: Math.round(moved),
    };
  }
}
