import { round1 } from './util.js';

/**
 * `radio.score` smoothing (docs/protocol.md 4): the mean of the raw scores read in the last
 * `finder.smooth_s` seconds. A missing reading (radio off, no LTE) clears the window, so a stale
 * average never outlives the signal.
 */
export class ScoreSmoother {
  private readings: { at: number; score: number }[] = [];
  private windowS: number;

  constructor(windowS: number) {
    this.windowS = Math.max(0, windowS);
  }

  setWindow(windowS: number): void {
    this.windowS = Math.max(0, windowS);
  }

  /** Adds a reading taken at `at` (ms) and returns the smoothed score. */
  add(at: number, raw: number | null): number | null {
    if (raw == null || Number.isNaN(raw)) {
      this.readings = [];
      return null;
    }
    this.readings.push({ at, score: raw });
    return this.value(at);
  }

  value(now: number): number | null {
    // A zero window means "no smoothing": keep only readings taken at this instant.
    const from = this.windowS > 0 ? now - this.windowS * 1000 : now - 1;
    this.readings = this.readings.filter((r) => r.at > from && r.at <= now);
    if (this.readings.length === 0) return null;
    const sum = this.readings.reduce((acc, r) => acc + r.score, 0);
    return round1(sum / this.readings.length);
  }

  clear(): void {
    this.readings = [];
  }
}
