import { clamp, type BatteryStatus, type PowerInfo } from '@hsm/shared';
import type { Random } from '../rng.js';

export type StopReason = 'limit' | 'heat' | 'manual';

/** Which reason is reported when several hold charging off at once. */
const STOP_PRIORITY: StopReason[] = ['heat', 'manual', 'limit'];

/** Open-circuit voltage of a Li-ion cell by state of charge. */
const OCV: [number, number][] = [
  [0, 3400], [5, 3600], [10, 3680], [20, 3730], [40, 3790], [50, 3830],
  [60, 3900], [70, 3970], [80, 4060], [90, 4160], [100, 4330],
];

function ocvMv(percent: number): number {
  for (let i = 1; i < OCV.length; i++) {
    const [p1, v1] = OCV[i] as [number, number];
    const [p0, v0] = OCV[i - 1] as [number, number];
    if (percent <= p1) return v0 + ((percent - p0) / (p1 - p0)) * (v1 - v0);
  }
  return 4330;
}

/**
 * Battery and USB supply. Net battery current = supply - phone load while charging; with
 * charging switched off (Samsung slate mode) the phone runs from USB and the battery rests,
 * leaking a few tens of mA. A weak port (weak-power scenario) cannot cover the load, so the
 * battery drains even though the phone is plugged in.
 */
export class PowerModel {
  readonly capacityMah = 4000;
  percent: number;
  plugged = true;
  /** What the USB port delivers (mA). */
  supplyMa = 900;
  currentMa = 0;
  /** power-cut scenario: compressed drain in percent per simulated second while unplugged. */
  forcedDrainPctPerS = 0;
  private readonly stops = new Set<StopReason>();

  constructor(private readonly rng: Random, percent?: number) {
    this.percent = clamp(percent ?? rng.range(52, 57), 0, 100);
  }

  get stopReason(): StopReason | null {
    return STOP_PRIORITY.find((r) => this.stops.has(r)) ?? null;
  }

  get chargingEnabled(): boolean {
    return this.stops.size === 0;
  }

  hasStop(reason: StopReason): boolean {
    return this.stops.has(reason);
  }

  setStop(reason: StopReason, on: boolean): { before: StopReason | null; after: StopReason | null } {
    const before = this.stopReason;
    if (on) this.stops.add(reason);
    else this.stops.delete(reason);
    return { before, after: this.stopReason };
  }

  step(dt: number, loadMa: number): void {
    let current: number;
    if (!this.plugged) {
      current = -loadMa;
    } else if (this.chargingEnabled) {
      const headroom = this.supplyMa - loadMa;
      const taper = this.percent >= 99.5 ? 0 : this.percent > 85 ? (100 - this.percent) / 15 : 1;
      current = headroom > 0 ? Math.min(1400 * taper, headroom) : headroom;
    } else {
      current = this.supplyMa >= loadMa ? -(25 + this.rng.range(0, 20)) : this.supplyMa - loadMa;
    }
    this.currentMa = Math.round(current + this.rng.gauss(0, 8));
    const dPct =
      this.forcedDrainPctPerS > 0 && !this.plugged
        ? -this.forcedDrainPctPerS * dt
        : ((this.currentMa * dt) / 3600 / this.capacityMah) * 100;
    this.percent = clamp(this.percent + dPct, 0, 100);
  }

  /** Phone switched off: no load; an attached charger still charges (off-mode charging). */
  stepOff(dt: number): void {
    this.currentMa = this.plugged && this.percent < 99.5 ? Math.min(1400, this.supplyMa) : 0;
    this.percent = clamp(this.percent + ((this.currentMa * dt) / 3600 / this.capacityMah) * 100, 0, 100);
  }

  voltageMv(): number {
    return Math.round(ocvMv(this.percent) + this.currentMa * 0.1);
  }

  status(): BatteryStatus {
    if (!this.plugged) return 'discharging';
    if (!this.chargingEnabled) return 'not_charging';
    return this.percent >= 99.5 ? 'full' : 'charging';
  }

  info(batteryC: number, chargeControl: string | null, limitPct: number): PowerInfo {
    return {
      percent: Math.round(this.percent),
      status: this.status(),
      current_ma: this.currentMa,
      voltage_mv: this.voltageMv(),
      health: batteryC >= 55 ? 'overheat' : 'good',
      plug: this.plugged ? 'usb' : 'none',
      charging_enabled: this.chargingEnabled,
      charge_limit_pct: limitPct,
      charge_control: chargeControl,
      stop_reason: this.stopReason,
    };
  }
}
