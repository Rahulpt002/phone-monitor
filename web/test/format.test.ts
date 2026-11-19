import { describe, expect, it } from 'vitest';
import {
  DASH,
  formatBps,
  formatBytes,
  formatCountdown,
  formatCurrent,
  formatDuration,
  formatMb,
  formatPercent,
  formatRelative,
  formatTaDistance,
  formatTemp,
  formatVoltage,
  trimNumber,
} from '../src/lib/format';

describe('formatBps', () => {
  it('uses decimal network units', () => {
    expect(formatBps(0)).toBe('0 bps');
    expect(formatBps(999)).toBe('999 bps');
    expect(formatBps(1000)).toBe('1 kbps');
    expect(formatBps(820_000)).toBe('820 kbps');
    expect(formatBps(5_400_000)).toBe('5.4 Mbps');
    expect(formatBps(42_160_000)).toBe('42.2 Mbps');
    expect(formatBps(1_250_000_000)).toBe('1.25 Gbps');
  });
  it('never invents a value', () => {
    expect(formatBps(null)).toBe(DASH);
    expect(formatBps(undefined)).toBe(DASH);
    expect(formatBps(Number.NaN)).toBe(DASH);
  });
  it('clamps negative counters to zero', () => {
    expect(formatBps(-5)).toBe('0 bps');
  });
});

describe('formatBytes', () => {
  it('uses decimal units like carriers bill', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2_100_000_000)).toBe('2.1 GB');
    expect(formatBytes(41_000_000_000)).toBe('41 GB');
    expect(formatBytes(117_000_000_000)).toBe('117 GB');
    expect(formatBytes(1_234_567)).toBe('1.23 MB');
    expect(formatBytes(null)).toBe(DASH);
  });
  it('formats MB values from /proc', () => {
    expect(formatMb(3120)).toBe('3.12 GB');
    expect(formatMb(88)).toBe('88 MB');
  });
});

describe('formatDuration', () => {
  it('shows the two largest units', () => {
    expect(formatDuration(0)).toBe('0 s');
    expect(formatDuration(45)).toBe('45 s');
    expect(formatDuration(312)).toBe('5 min 12 s');
    expect(formatDuration(300)).toBe('5 min');
    expect(formatDuration(3 * 3600 + 4 * 60 + 9)).toBe('3 h 4 min');
    expect(formatDuration(7200)).toBe('2 h');
    expect(formatDuration(86_400 * 2 + 3 * 3600 + 59)).toBe('2 d 3 h');
    expect(formatDuration(86_400)).toBe('1 d');
    expect(formatDuration(null)).toBe(DASH);
  });
});

describe('formatRelative', () => {
  const now = Date.parse('2026-11-19T10:15:00.000Z');
  it('describes the past', () => {
    expect(formatRelative('2026-11-19T10:14:58.000Z', now)).toBe('just now');
    expect(formatRelative('2026-11-19T10:14:48.000Z', now)).toBe('12 s ago');
    expect(formatRelative('2026-11-19T10:10:00.000Z', now)).toBe('5 min ago');
    expect(formatRelative('2026-11-19T07:15:00.000Z', now)).toBe('3 h ago');
    expect(formatRelative('2026-11-17T10:15:00.000Z', now)).toBe('2 d ago');
  });
  it('describes the future and missing values', () => {
    expect(formatRelative('2026-11-19T10:15:30.000Z', now)).toBe('in 30 s');
    expect(formatRelative(null, now)).toBe('never');
    expect(formatRelative('not a date', now)).toBe('never');
  });
  it('accepts epoch milliseconds', () => {
    expect(formatRelative(now - 120_000, now)).toBe('2 min ago');
  });
});

describe('small formatters', () => {
  it('counts down as m:ss and clamps at zero', () => {
    expect(formatCountdown(120_000)).toBe('2:00');
    expect(formatCountdown(61_500)).toBe('1:02');
    expect(formatCountdown(-5)).toBe('0:00');
  });
  it('signs the battery current', () => {
    expect(formatCurrent(120.4)).toBe('+120 mA');
    expect(formatCurrent(-35)).toBe('-35 mA');
    expect(formatCurrent(0)).toBe('0 mA');
  });
  it('formats voltage, temperature, percent', () => {
    expect(formatVoltage(3910)).toBe('3.91 V');
    expect(formatTemp(36.54)).toBe('36.5 °C');
    expect(formatPercent(58)).toBe('58%');
    expect(formatPercent(12.345, 1)).toBe('12.3%');
  });
  it('turns timing advance into a rough distance', () => {
    expect(formatTaDistance(3)).toBe('~234 m');
    expect(formatTaDistance(20)).toBe('~1.6 km');
    expect(formatTaDistance(null)).toBe(DASH);
  });
  it('trims trailing zeros', () => {
    expect(trimNumber(2.1, 2)).toBe('2.1');
    expect(trimNumber(41, 1)).toBe('41');
    expect(trimNumber(-0.25, 1)).toBe('-0.3');
  });
});
