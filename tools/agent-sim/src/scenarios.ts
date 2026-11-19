import type { SimClock } from './clock.js';
import type { Logger } from './log.js';
import type { Phone } from './phone/phone.js';
import type { Random } from './rng.js';
import { formatDuration } from './util.js';

export const SCENARIO_NAMES = ['normal', 'internet-loss', 'overheat', 'weak-power', 'cell-flap', 'power-cut'] as const;
export type ScenarioName = (typeof SCENARIO_NAMES)[number];

export const SCENARIO_HELP: Record<ScenarioName, string> = {
  normal: 'steady signal, diurnal congestion, charge limit 50-60 %',
  'internet-loss': 'upstream internet dies; the watchdog walks data cycle (90 s), airplane cycle (180 s), reboot (600 s)',
  overheat: 'sun on the box: battery heads for 48 °C (temp_warning, temp_critical, charging stopped), then cools',
  'weak-power': 'USB port supplies only 320 mA: plugged in but discharging, weak_power after watchdog.weak_power_min',
  'cell-flap': 'two cells of similar strength: the serving PCI flips every 15-60 s (cell_change events)',
  'power-cut': 'USB power lost (power_lost), battery runs down, phone dies, power returns and it boots by itself',
};

/** Simulated seconds after the first `hello` before a scenario's effect starts. */
export const SCENARIO_LEAD_S = 10;
/** An internet loss with no fixed duration ends by itself after this long, so the simulator never stays dark. */
const INTERNET_LOSS_SAFETY_S = 1800;
/** power-cut: how long the battery lasts once power is gone (drain compressed so a demo fits). */
const POWER_CUT_RUNTIME_S = 300;
const POWER_CUT_OFF_S = 180;

export interface ScenarioHost {
  phone: Phone;
  log: Logger;
  clock: SimClock;
  rng: Random;
  /** Re-evaluate whether the Agent's link to the Server is up. */
  linkChanged(): void;
  /** USB power and the USB host (router) are gone. */
  powerCut(): void;
  /** The battery is empty: the phone shuts down. */
  batteryDied(): void;
  /** Power is back: the phone boots by itself (plan 4.3). */
  powerRestored(): void;
}

interface Scenario {
  readonly name: string;
  tick(t: number, dt: number): void;
  onBoot?(): void;
}

/** Upstream internet outage (internet-loss) or a faked failing check (watchdog_simulate). */
class InternetLoss implements Scenario {
  private phase: 'pending' | 'down' | 'done' = 'pending';
  private downAt = 0;

  constructor(
    private readonly host: ScenarioHost,
    readonly name: string,
    private readonly startAt: number,
    private readonly durationS: number | null,
    /** true: only the internet check fails and the Agent stays connected (watchdog_simulate). */
    private readonly checkOnly: boolean,
  ) {}

  tick(t: number): void {
    if (this.phase === 'pending' && t >= this.startAt) {
      this.phase = 'down';
      this.downAt = t;
      const until = this.durationS != null ? `for ${formatDuration(this.durationS)}` : 'until the watchdog reboots the phone';
      this.host.log.info('scen', `${this.name}: ${this.checkOnly ? 'internet check now fails' : 'upstream internet lost'} ${until}`);
      this.set(true);
    } else if (this.phase === 'down' && t - this.downAt >= (this.durationS ?? INTERNET_LOSS_SAFETY_S)) {
      this.restore(`after ${formatDuration(t - this.downAt)}`);
    }
  }

  onBoot(): void {
    if (this.phase === 'down') this.restore('the reboot cleared it');
  }

  private restore(why: string): void {
    this.phase = 'done';
    this.host.log.info('scen', `${this.name}: internet restored (${why})`);
    this.set(false);
  }

  private set(down: boolean): void {
    if (this.checkOnly) this.host.phone.flags.probeFailSim = down;
    else this.host.phone.flags.internetBroken = down;
    this.host.linkChanged();
  }
}

class Overheat implements Scenario {
  readonly name = 'overheat';
  private phase: 'pending' | 'heating' | 'cooling' | 'done' = 'pending';
  private at = 0;

  constructor(private readonly host: ScenarioHost) {}

  tick(t: number): void {
    const heat = this.host.phone.heat;
    if (this.phase === 'pending' && t >= SCENARIO_LEAD_S) {
      this.phase = 'heating';
      this.at = t;
      heat.setOverride({ targetC: 48.5, tauS: 150 });
      this.host.log.info('scen', 'overheat: direct sun on the box, battery heading for 48 °C');
    } else if (this.phase === 'heating' && t - this.at >= 600) {
      this.phase = 'cooling';
      heat.setOverride({ targetC: null, tauS: 200 });
      this.host.log.info('scen', 'overheat: shade is back, cooling down');
    } else if (this.phase === 'cooling' && heat.battery < 38) {
      this.phase = 'done';
      heat.setOverride(null);
      this.host.log.info('scen', 'overheat: back to normal temperatures');
    }
  }
}

class WeakPower implements Scenario {
  readonly name = 'weak-power';
  private started = false;

  constructor(private readonly host: ScenarioHost) {}

  tick(t: number): void {
    if (this.started || t < SCENARIO_LEAD_S) return;
    this.started = true;
    this.host.phone.power.supplyMa = 320;
    this.host.log.info('scen', 'weak-power: the USB port now supplies only 320 mA, less than the phone uses');
  }
}

class CellFlap implements Scenario {
  readonly name = 'cell-flap';
  private nextAt: number | null = null;

  constructor(private readonly host: ScenarioHost) {}

  tick(t: number): void {
    const { phone, rng, clock, log } = this.host;
    if (this.nextAt === null) {
      if (t < SCENARIO_LEAD_S) return;
      phone.radio.neighbourGapDb = 1.5;
      this.nextAt = t + rng.range(5, 20);
      log.info('scen', 'cell-flap: two cells of similar strength, the serving cell will keep switching');
      return;
    }
    if (t < this.nextAt) return;
    this.nextAt = t + rng.range(15, 60);
    if (!phone.radioOn) return;
    const { from, to } = phone.radio.switchCell(clock.now());
    log.debug('scen', `cell-flap: handover PCI ${from.pci} -> ${to.pci}`);
  }
}

class PowerCut implements Scenario {
  readonly name = 'power-cut';
  private phase: 'pending' | 'battery' | 'off' | 'done' = 'pending';
  private at = 0;

  constructor(private readonly host: ScenarioHost) {}

  tick(t: number): void {
    const { phone, log } = this.host;
    if (this.phase === 'pending' && t >= SCENARIO_LEAD_S) {
      this.phase = 'battery';
      this.at = t;
      this.host.powerCut();
      phone.power.forcedDrainPctPerS = Math.max(1, phone.power.percent) / POWER_CUT_RUNTIME_S;
      log.info('scen', `power-cut: mains off, running on battery (drain compressed: empty in about ${formatDuration(POWER_CUT_RUNTIME_S)})`);
    } else if (this.phase === 'battery' && phone.power.percent <= 0.5) {
      log.info('scen', `power-cut: battery empty after ${formatDuration(t - this.at)} on battery, phone shuts down`);
      this.phase = 'off';
      this.at = t;
      phone.power.forcedDrainPctPerS = 0;
      this.host.batteryDied();
    } else if (this.phase === 'off' && t - this.at >= POWER_CUT_OFF_S) {
      this.phase = 'done';
      log.info('scen', `power-cut: power back after ${formatDuration(POWER_CUT_OFF_S)} off, phone boots by itself`);
      this.host.powerRestored();
    }
  }
}

/** Runs the selected scenarios on simulated time once the Agent has first connected. */
export class ScenarioRunner {
  private readonly active: Scenario[] = [];
  private t = 0;
  private started = false;

  constructor(private readonly host: ScenarioHost, names: readonly ScenarioName[]) {
    for (const name of new Set(names)) {
      switch (name) {
        case 'normal':
          break;
        case 'internet-loss':
          this.active.push(new InternetLoss(host, 'internet-loss', SCENARIO_LEAD_S, null, false));
          break;
        case 'overheat':
          this.active.push(new Overheat(host));
          break;
        case 'weak-power':
          this.active.push(new WeakPower(host));
          break;
        case 'cell-flap':
          this.active.push(new CellFlap(host));
          break;
        case 'power-cut':
          this.active.push(new PowerCut(host));
          break;
      }
    }
  }

  get elapsedS(): number {
    return this.t;
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    const names = this.active.map((s) => s.name);
    if (names.length) this.host.log.info('scen', `running ${names.join(', ')} (effects start in ${formatDuration(SCENARIO_LEAD_S)} simulated)`);
  }

  tick(dt: number): void {
    if (!this.started) return;
    this.t += dt;
    for (const s of this.active) s.tick(this.t, dt);
  }

  onBoot(): void {
    for (const s of this.active) s.onBoot?.();
  }

  /** watchdog_simulate: the internet check fails for `durationS` simulated seconds; the link stays up. */
  simulateInternetCheckFailure(durationS: number): void {
    this.started = true;
    const s = new InternetLoss(this.host, 'watchdog_simulate', this.t, durationS, true);
    this.active.push(s);
    s.tick(this.t);
  }
}
