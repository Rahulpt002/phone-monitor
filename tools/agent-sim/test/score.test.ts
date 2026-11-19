import { describe, expect, it } from 'vitest';
import { signalScore } from '@hsm/shared';
import { ScoreSmoother } from '../src/score.js';
import { fixture } from './helpers.js';

describe('ScoreSmoother (radio.score over finder.smooth_s)', () => {
  it('is the mean of the readings in the last window', () => {
    const s = new ScoreSmoother(5);
    const values = [60, 62, 64, 66, 68, 70, 72];
    let out: number | null = null;
    values.forEach((v, i) => {
      out = s.add(i * 1000, v);
    });
    // Readings at 2..6 s are within (6 s - 5 s, 6 s].
    expect(out).toBe(68);
  });

  it('rounds to one decimal', () => {
    const s = new ScoreSmoother(5);
    s.add(0, 61);
    expect(s.add(1000, 61.25)).toBe(61.1);
  });

  it('forgets everything when a reading is missing', () => {
    const s = new ScoreSmoother(5);
    s.add(0, 80);
    s.add(1000, 80);
    expect(s.add(2000, null)).toBeNull();
    expect(s.add(3000, 40)).toBe(40);
  });

  it('expires old readings even without new ones', () => {
    const s = new ScoreSmoother(5);
    s.add(0, 50);
    expect(s.value(4000)).toBe(50);
    expect(s.value(6000)).toBeNull();
  });

  it('follows a new window and treats 0 as no smoothing', () => {
    const s = new ScoreSmoother(10);
    s.add(0, 10);
    s.add(1000, 20);
    expect(s.value(1000)).toBe(15);
    s.setWindow(0);
    expect(s.add(2000, 30)).toBe(30);
  });

  it('smooths the shared score formula (fixture vectors)', () => {
    type Case = { rsrp: number | null; sinr: number | null; rsrq: number | null; score: number | null };
    const cases: Case[] = fixture('score-cases.json');
    const complete = cases.filter((c): c is Case & { score: number } => c.score !== null);
    const s = new ScoreSmoother(cases.length);
    let last: number | null = null;
    complete.forEach((c, i) => {
      last = s.add(i * 1000, signalScore(c.rsrp, c.sinr, c.rsrq));
    });
    const mean = complete.reduce((a, c) => a + c.score, 0) / complete.length;
    expect(last).toBe(Math.round(mean * 10) / 10);

    // The vector without RSRP has no score, which restarts the window.
    const fresh = new ScoreSmoother(cases.length);
    const out = cases.map((c, i) => fresh.add(i * 1000, signalScore(c.rsrp, c.sinr, c.rsrq)));
    const nullAt = cases.findIndex((c) => c.score === null);
    expect(out[nullAt]).toBeNull();
    expect(out[nullAt + 1]).toBe(cases[nullAt + 1]?.score);
  });
});
