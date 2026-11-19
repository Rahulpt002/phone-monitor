import type { HeatInfo } from '@hsm/shared';
import type { Random } from '../rng.js';
import { round1 } from '../util.js';

/** Air temperature inside the shaded outdoor box: about 21.5 °C at 03:00, 32.5 °C at 15:00. */
export function ambientC(hour: number): number {
  return 27 + 5.5 * Math.sin((2 * Math.PI * (hour - 9)) / 24);
}

export interface HeatEnv {
  hour: number;
  loadMa: number;
  charging: boolean;
  /** Share of the radio's capacity in use (0..1). */
  util: number;
  radioOn: boolean;
}

/** The overheat scenario's push: a target temperature and how fast the battery follows it. */
interface HeatOverride {
  targetC: number | null;
  tauS: number;
}

/** First-order thermal model: each part lags towards a target set by ambient, load and charging. */
export class HeatModel {
  battery: number;
  private cpu: number;
  private modem: number;
  private override: HeatOverride | null = null;

  constructor(hour: number) {
    this.battery = ambientC(hour) + 4;
    this.cpu = this.battery + 6;
    this.modem = this.battery + 4;
  }

  /**
   * `targetC` drives the battery towards that temperature with time constant `tauS`;
   * `targetC = null` keeps the normal target but uses `tauS`. `setOverride(null)` restores normal.
   */
  setOverride(override: HeatOverride | null): void {
    this.override = override;
  }

  normalTarget(env: HeatEnv): number {
    return ambientC(env.hour) + 2 + (env.charging ? 2.5 : 0) + (env.loadMa / 600) * 2;
  }

  step(dt: number, env: HeatEnv): void {
    if (dt <= 0) return;
    let target = this.normalTarget(env);
    let tau = 600;
    if (this.override) {
      if (this.override.targetC != null) target = this.override.targetC;
      tau = this.override.tauS;
    }
    this.battery += (target - this.battery) * (1 - Math.exp(-dt / tau));
    const fast = 1 - Math.exp(-dt / 60);
    this.cpu += (this.battery + 5 + env.loadMa / 150 - this.cpu) * fast;
    this.modem += (this.battery + 3 + (env.radioOn ? 1.5 + env.util * 3 : 0) - this.modem) * fast;
  }

  /** Phone off: everything cools towards the box's air temperature. */
  stepOff(dt: number, hour: number): void {
    const a = 1 - Math.exp(-dt / 600);
    const amb = ambientC(hour) + 0.5;
    this.battery += (amb - this.battery) * a;
    this.cpu += (amb - this.cpu) * a;
    this.modem += (amb - this.modem) * a;
  }

  /** PowerManager thermal status (0 none .. 6 shutdown) by battery temperature. */
  thermalStatus(): number {
    const b = this.battery;
    if (b < 39) return 0;
    if (b < 42) return 1;
    if (b < 45) return 2;
    if (b < 48) return 3;
    if (b < 52) return 4;
    if (b < 55) return 5;
    return 6;
  }

  info(rng: Random, radioResting: boolean): HeatInfo {
    const battery = round1(this.battery);
    const cpu = round1(this.cpu + rng.gauss(0, 0.3));
    const modem = round1(this.modem + rng.gauss(0, 0.2));
    const skin = round1(this.battery - 1.3);
    return {
      battery_c: battery,
      cpu_c: cpu,
      modem_c: modem,
      skin_c: skin,
      thermal_status: this.thermalStatus(),
      zones: {
        battery,
        'cpu-0-0-usr': cpu,
        'cpu-1-0-usr': round1(cpu + 1.1),
        'modem-0-usr': modem,
        pa_therm0: round1(modem - 1.6),
        xo_therm: skin,
      },
      radio_resting: radioResting,
    };
  }
}
