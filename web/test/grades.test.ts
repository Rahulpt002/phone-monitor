import scoreCases from '@hsm/shared/fixtures/score-cases.json';
import { signalScore } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import { GRADE_LABEL, GRADE_TEXT, gradeBgClass, gradeOf, gradeTextClass } from '../src/lib/grades';

describe('gradeOf (plan 7.1 thresholds)', () => {
  it('RSRP: boundaries belong to the better grade', () => {
    expect(gradeOf('rsrp', -79)).toBe('excellent');
    expect(gradeOf('rsrp', -80)).toBe('good');
    expect(gradeOf('rsrp', -90)).toBe('good');
    expect(gradeOf('rsrp', -90.1)).toBe('fair');
    expect(gradeOf('rsrp', -100)).toBe('fair');
    expect(gradeOf('rsrp', -101)).toBe('poor');
  });
  it('SINR', () => {
    expect(gradeOf('sinr', 21)).toBe('excellent');
    expect(gradeOf('sinr', 20)).toBe('good');
    expect(gradeOf('sinr', 13)).toBe('good');
    expect(gradeOf('sinr', 12.5)).toBe('fair');
    expect(gradeOf('sinr', 0)).toBe('fair');
    expect(gradeOf('sinr', -0.1)).toBe('poor');
  });
  it('RSRQ', () => {
    expect(gradeOf('rsrq', -9)).toBe('excellent');
    expect(gradeOf('rsrq', -10)).toBe('good');
    expect(gradeOf('rsrq', -15)).toBe('good');
    expect(gradeOf('rsrq', -16)).toBe('fair');
    expect(gradeOf('rsrq', -20)).toBe('fair');
    expect(gradeOf('rsrq', -21)).toBe('poor');
  });
  it('score', () => {
    expect(gradeOf('score', 75)).toBe('excellent');
    expect(gradeOf('score', 61.2)).toBe('good');
    expect(gradeOf('score', 35)).toBe('fair');
    expect(gradeOf('score', 34.9)).toBe('poor');
  });
  it('missing values have no grade', () => {
    expect(gradeOf('rsrp', null)).toBeNull();
    expect(gradeOf('sinr', undefined)).toBeNull();
    expect(gradeOf('score', Number.NaN)).toBeNull();
  });
});

describe('grade colour mapping', () => {
  it('maps every grade to its theme token class', () => {
    expect(gradeTextClass('excellent')).toBe('text-grade-excellent');
    expect(gradeTextClass('good')).toBe('text-grade-good');
    expect(gradeTextClass('fair')).toBe('text-grade-fair');
    expect(gradeTextClass('poor')).toBe('text-grade-poor');
    expect(gradeBgClass('poor')).toBe('bg-grade-poor');
  });
  it('uses a neutral colour without data', () => {
    expect(gradeTextClass(null)).toBe('text-muted');
    expect(gradeBgClass(null)).toBe('bg-surface-3');
  });
  it('has a label for every coloured grade (never colour alone)', () => {
    for (const g of Object.keys(GRADE_TEXT)) expect(GRADE_LABEL[g as keyof typeof GRADE_LABEL]).toBeTruthy();
  });
  it('colours the shared score test vectors consistently', () => {
    for (const c of scoreCases as { rsrp: number | null; sinr: number | null; rsrq: number | null; score: number | null }[]) {
      const s = signalScore(c.rsrp, c.sinr, c.rsrq);
      expect(s).toBe(c.score);
      expect(gradeOf('score', s)).toBe(c.score == null ? null : gradeOf('score', c.score));
    }
  });
});
