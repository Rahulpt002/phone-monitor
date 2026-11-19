import {
  clamp,
  rankSpots,
  signalScore,
  type FinderLive,
  type FinderMode,
  type FinderSpot,
  type Settings,
  type SpeedRun,
  type SpeedSession,
} from '@hsm/shared';
import type { SimClock } from './clock.js';
import type { EventSpec } from './events.js';
import type { Phone } from './phone/phone.js';
import { congestion } from './phone/radio.js';
import type { Random } from './rng.js';
import { hourOfDay, median, newId, round1, stats } from './util.js';

/** Simulated seconds one speed-test run takes (ping, download, upload). */
export const SPEED_RUN_S = 12;

export interface FinderHost {
  clock: SimClock;
  rng: Random;
  phone: Phone;
  settings(): Settings;
  /** Persist and queue `finder_spot`. */
  spotSaved(spot: FinderSpot): void;
  /** Persist and queue `finder_spot_deleted`. */
  spotDeleted(spotId: string): void;
  emit(spec: EventSpec): void;
}

export class FinderAbortedError extends Error {
  constructor() {
    super('aborted: the phone went down during the test');
  }
}

interface Running {
  mode: 'spot' | 'speed';
  label: string;
  totalS: number;
  startedReal: number;
}

/** Signal dB gained or lost by holding the phone a certain way (plan 7.4 step 2). */
export function orientationOffset(orientation: string | null): number {
  if (!orientation || orientation === 'upright') return 0;
  if (orientation === 'flat') return -3.5;
  if (orientation === 'rot90') return 1.5;
  let h = 0;
  for (const ch of orientation) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return ((h % 70) - 40) / 10;
}

/** Signal Finder (plan section 7): live mirror, spot test, speed test, baseline. */
export class Finder {
  /** Rates say a Dashboard has the Finder screen open: report "walk" mode. */
  mirroring = false;
  private readonly spots = new Map<string, FinderSpot>();
  private running: Running | null = null;
  private generation = 0;

  constructor(private readonly host: FinderHost, initial: readonly FinderSpot[] = []) {
    for (const s of initial) if (s && typeof s.id === 'string') this.spots.set(s.id, s);
  }

  get mode(): FinderMode {
    return this.running?.mode ?? (this.mirroring ? 'walk' : 'idle');
  }

  get busy(): boolean {
    return this.running !== null;
  }

  list(): FinderSpot[] {
    return rankSpots([...this.spots.values()]);
  }

  get(id: string): FinderSpot | undefined {
    return this.spots.get(id);
  }

  live(): FinderLive {
    const { phone, clock } = this.host;
    const r = phone.radioOn ? phone.lastReading : null;
    const cell = phone.radio.serving;
    const run = this.running;
    return {
      ts: clock.iso(),
      mode: this.mode,
      score: phone.score,
      score_raw: phone.rawScore,
      rsrp: r?.rsrp ?? null,
      sinr: r?.sinr ?? null,
      rsrq: r?.rsrq ?? null,
      net_type: phone.netType(),
      band: r ? cell.band : null,
      pci: r ? cell.pci : null,
      cell_id: r ? cell.cellId : null,
      ta: r ? cell.ta : null,
      cell_switches_5min: phone.radio.switchesSince(clock.now() - 300_000),
      progress: run ? { label: run.label, elapsed_s: Math.min(run.totalS, Math.round(this.elapsedSim(run))), total_s: run.totalS } : null,
    };
  }

  /** The phone went down: running tests are lost, like on the real phone. */
  abort(): void {
    this.generation++;
    this.running = null;
    this.host.phone.radio.orientationOffsetDb = 0;
  }

  /**
   * Spot test (plan 7.3): one reading per simulated second for `durationS`, then the spot is
   * saved. With --speed the test takes durationS / speed real seconds but still has durationS samples.
   */
  async spotTest(opts: { name: string; orientation: string | null; durationS: number }): Promise<FinderSpot> {
    const { phone, clock } = this.host;
    const gen = this.generation;
    const durationS = Math.max(1, Math.round(opts.durationS));
    const startedAt = clock.now();
    const run: Running = { mode: 'spot', label: `Spot test "${opts.name}"`, totalS: durationS, startedReal: Date.now() };
    this.running = run;
    const switchesBefore = phone.radio.switchCount;
    phone.radio.orientationOffsetDb = orientationOffset(opts.orientation);
    const weights = this.weights();
    const samples: { rsrp: number; sinr: number; rsrq: number; score: number }[] = [];
    let slots = 0;
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setInterval(() => {
          if (gen !== this.generation) {
            clearInterval(timer);
            reject(new FinderAbortedError());
            return;
          }
          const due = Math.min(durationS, Math.floor(this.elapsedSim(run)));
          while (slots < due) {
            slots++;
            if (!phone.radioOn) continue;
            const r = phone.radio.reading();
            const score = signalScore(r.rsrp, r.sinr, r.rsrq, weights);
            if (score != null) samples.push({ ...r, score });
          }
          if (slots >= durationS) {
            clearInterval(timer);
            resolve();
          }
        }, clamp(Math.round(1000 / clock.speed), 20, 250));
      });
    } finally {
      if (gen === this.generation) {
        this.running = null;
        phone.radio.orientationOffsetDb = 0;
      }
    }
    if (samples.length === 0) throw new Error('no LTE signal during the spot test');

    const pos = phone.position.info();
    const angles = this.anglesFor(opts.orientation);
    const cell = phone.radio.serving;
    const spot: FinderSpot = {
      id: newId('s_'),
      name: opts.name,
      created_at: clock.iso(startedAt),
      duration_s: durationS,
      lat: pos.lat ?? null,
      lon: pos.lon ?? null,
      acc_m: pos.acc_m ?? null,
      compass_deg: pos.azimuth_deg ?? null,
      orientation: opts.orientation,
      pitch_deg: angles.pitch,
      roll_deg: angles.roll,
      has_photo: false,
      serving: { net_type: phone.netType(), band: cell.band, pci: cell.pci, cell_id: cell.cellId, earfcn: cell.earfcn, ta: cell.ta },
      cell_switches: phone.radio.switchCount - switchesBefore,
      metrics: {
        score: stats(samples.map((s) => s.score)),
        rsrp: stats(samples.map((s) => s.rsrp)),
        sinr: stats(samples.map((s) => s.sinr)),
        rsrq: stats(samples.map((s) => s.rsrq)),
      },
      speed: [],
      baseline: false,
    };
    this.spots.set(spot.id, spot);
    this.host.spotSaved(spot);
    this.host.emit({
      kind: 'finder',
      severity: 'info',
      message: `Spot test "${spot.name}": score ${spot.metrics.score.avg}`,
      data: { spot_id: spot.id, test: 'spot', score: spot.metrics.score.avg, cell_switches: spot.cell_switches },
    });
    return spot;
  }

  /** Speed test (plan 7.3): `finder.speed_runs` runs of ping, download and upload. */
  async speedTest(spotId: string | null): Promise<{ session: SpeedSession; spot: FinderSpot | null }> {
    const { phone, clock } = this.host;
    if (spotId && !this.spots.has(spotId)) throw new Error(`spot ${spotId} not found`);
    if (!phone.internetOk) throw new Error('no internet connection');
    const gen = this.generation;
    const settings = this.host.settings().finder;
    const runsWanted = clamp(Math.round(settings.speed_runs) || 1, 1, 10);
    const startedAt = clock.now();
    const run: Running = { mode: 'speed', label: `Speed test run 1/${runsWanted}`, totalS: runsWanted * SPEED_RUN_S, startedReal: Date.now() };
    this.running = run;
    const runs: SpeedRun[] = [];
    try {
      for (let i = 0; i < runsWanted; i++) {
        run.label = `Speed test run ${i + 1}/${runsWanted}`;
        const at = clock.iso();
        await new Promise<void>((resolve) => setTimeout(resolve, clock.realMs(SPEED_RUN_S)));
        if (gen !== this.generation) throw new FinderAbortedError();
        runs.push(phone.internetOk ? this.measureRun(at, settings.speed_bytes) : { down_mbps: null, up_mbps: null, ping_ms: null, jitter_ms: null, bytes: 0, at });
      }
    } finally {
      if (gen === this.generation) this.running = null;
    }
    const pick = (k: 'down_mbps' | 'up_mbps' | 'ping_ms' | 'jitter_ms') => {
      const m = median(runs.map((r) => r[k]).filter((v): v is number => v != null));
      return m == null ? null : round1(m);
    };
    const hour = hourOfDay(startedAt);
    const session: SpeedSession = {
      at: clock.iso(startedAt),
      peak_hour: hour >= 20 && hour < 22,
      runs,
      down_mbps: pick('down_mbps'),
      up_mbps: pick('up_mbps'),
      ping_ms: pick('ping_ms'),
      jitter_ms: pick('jitter_ms'),
    };
    if (session.down_mbps == null) throw new Error('every speed-test run failed (no internet)');

    let saved: FinderSpot | null = null;
    const target = spotId ? this.spots.get(spotId) : undefined;
    if (target) {
      saved = { ...target, speed: [...target.speed, session] };
      this.spots.set(saved.id, saved);
      this.host.spotSaved(saved);
    }
    this.host.emit({
      kind: 'finder',
      severity: 'info',
      message: `Speed test${saved ? ` at "${saved.name}"` : ''}: ${session.down_mbps} Mbps down, ${session.up_mbps} Mbps up, ${session.ping_ms} ms`,
      data: { spot_id: saved?.id ?? null, test: 'speed', down_mbps: session.down_mbps, up_mbps: session.up_mbps, ping_ms: session.ping_ms },
    });
    return { session, spot: saved };
  }

  /** Marks one spot as the installed baseline (plan 7.3) and clears the flag on the old one. */
  setBaseline(spotId: string): FinderSpot | null {
    const spot = this.spots.get(spotId);
    if (!spot) return null;
    for (const other of this.spots.values()) {
      if (other.baseline && other.id !== spotId) {
        const cleared = { ...other, baseline: false };
        this.spots.set(cleared.id, cleared);
        this.host.spotSaved(cleared);
      }
    }
    const updated = { ...spot, baseline: true };
    this.spots.set(updated.id, updated);
    this.host.spotSaved(updated);
    return updated;
  }

  deleteSpot(spotId: string): boolean {
    if (!this.spots.delete(spotId)) return false;
    this.host.spotDeleted(spotId);
    return true;
  }

  private weights() {
    const f = this.host.settings().finder;
    return { w_sinr: f.w_sinr, w_rsrp: f.w_rsrp, w_rsrq: f.w_rsrq };
  }

  private elapsedSim(run: Running): number {
    return ((Date.now() - run.startedReal) * this.host.clock.speed) / 1000;
  }

  private anglesFor(orientation: string | null): { pitch: number; roll: number } {
    const o = this.host.phone.position.orientation;
    const rng = this.host.rng;
    if (orientation === 'flat') return { pitch: round1(rng.gauss(2, 0.5)), roll: round1(rng.gauss(0.8, 0.4)) };
    if (orientation === 'rot90') return { pitch: round1(rng.gauss(4, 0.6)), roll: round1(rng.gauss(88, 0.6)) };
    return { pitch: round1(o.pitch), roll: round1(o.roll) };
  }

  private measureRun(at: string, speedBytes: number): SpeedRun {
    const { phone, rng, clock } = this.host;
    const sinr = phone.lastReading?.sinr ?? 0;
    const c = congestion(hourOfDay(clock.now()));
    const down = Math.max(0.3, (sinr + 6) * 2.4 * (1 - 0.4 * c) * rng.gauss(1, 0.08));
    const up = Math.min(25, down * rng.range(0.17, 0.22));
    const upBytes = Math.round(((up * 1e6) / 8) * 3);
    const bytes = Math.max(0, Math.round(speedBytes)) + upBytes;
    phone.net.addBytes(speedBytes, upBytes, false);
    return {
      down_mbps: round1(down),
      up_mbps: round1(up),
      ping_ms: Math.round(Math.max(14, 36 + 25 * c + rng.gauss(0, 3))),
      jitter_ms: Math.round(Math.max(1, 3 + 5 * c + Math.abs(rng.gauss(0, 1.5)))),
      bytes,
      at,
    };
  }
}
