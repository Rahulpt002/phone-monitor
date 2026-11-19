import agentToServer from '@hsm/shared/fixtures/agent-to-server.json';
import { DEFAULT_SETTINGS, type CommandRecord, type FinderSpot, type PowerInfo } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import { isPluggedButDraining } from '../src/components/PowerWarning';
import { applyAppChanges, parseAppList } from '../src/lib/apps';
import { backoffDelay } from '../src/lib/backoff';
import { csvCell, spotsToCsv, SPOT_CSV_HEADER } from '../src/lib/csv';
import { alignSeries, appendPoint, numericAt, readJsonPath, trailingDuration, type Point } from '../src/lib/series';
import { smsSegments } from '../src/screens/Messages';

const msgs = agentToServer as Record<string, unknown>[];
const slow = msgs.find((m) => m.tier === 'slow')!;
const spot = msgs.find((m) => m.t === 'finder_spot')!.spot as FinderSpot;

describe('json paths from shared metrics', () => {
  it('reads nested values and array indexes', () => {
    expect(readJsonPath(slow, '$.power.percent')).toBe(58);
    expect(readJsonPath(slow, '$.system.cpu_load[0]')).toBe(0.42);
    expect(numericAt(slow, '$.radio.score')).toBeNull();
    expect(numericAt(slow, '$.heat.battery_c')).toBe(36.5);
  });
});

describe('series', () => {
  it('appends in order and trims to the window', () => {
    let pts: Point[] = [
      [1000, 1],
      [2000, 2],
    ];
    pts = appendPoint(pts, [3000, 3], 1500);
    expect(pts).toEqual([
      [2000, 2],
      [3000, 3],
    ]);
    expect(appendPoint(pts, [2500, 9], 0)).toBe(pts);
  });
  it('aligns series on one axis and breaks lines over long gaps', () => {
    const a: Point[] = [
      [0, 1],
      [60_000, 2],
      [120_000, 3],
      [3_600_000, 4],
    ];
    const b: Point[] = [[60_000, 10]];
    const [xs, ya, yb] = alignSeries([a, b]) as unknown as [number[], (number | null | undefined)[], (number | null | undefined)[]];
    expect(xs).toEqual([0, 60, 120, 120.001, 3600]);
    expect(ya).toEqual([1, 2, 3, null, 4]);
    expect(yb).toEqual([undefined, 10, undefined, undefined, undefined]);
  });
  it('measures how long a condition has held at the end', () => {
    const pts: Point[] = [
      [0, 5],
      [60_000, -1],
      [120_000, null],
      [180_000, -3],
    ];
    expect(trailingDuration(pts, (v) => v < 0)).toBe(120_000);
    expect(trailingDuration([], (v) => v < 0)).toBe(0);
  });
});

describe('plugged in but discharging', () => {
  const base: PowerInfo = { plug: 'usb', current_ma: -120, status: 'discharging', charging_enabled: true };
  it('flags a draining battery on USB power', () => {
    expect(isPluggedButDraining(base)).toBe(true);
    expect(isPluggedButDraining({ ...base, status: 'charging', stop_reason: null })).toBe(true);
  });
  it('ignores an intentional pause on input-suspend switches, which report "discharging" (F10)', () => {
    // Qualcomm input_suspend / Samsung batt_slate_mode at the 60 % limit: the phone runs from the battery.
    expect(isPluggedButDraining({ plug: 'usb', status: 'discharging', current_ma: -300, charging_enabled: false, stop_reason: 'limit' })).toBe(false);
    expect(isPluggedButDraining({ ...base, stop_reason: 'heat' })).toBe(false);
    expect(isPluggedButDraining({ ...base, charging_enabled: false })).toBe(false);
    // Charging is supposed to be on: still a warning.
    expect(isPluggedButDraining({ ...base, stop_reason: null })).toBe(true);
  });
  it('ignores the normal pause at the charge limit and unplugged phones', () => {
    expect(isPluggedButDraining(slow.power as PowerInfo)).toBe(false);
    expect(isPluggedButDraining({ ...base, plug: 'none' })).toBe(false);
    expect(isPluggedButDraining({ ...base, current_ma: 50 })).toBe(false);
    expect(isPluggedButDraining(null)).toBe(false);
  });
});

describe('csv export', () => {
  it('quotes and neutralizes formula-like text', () => {
    expect(csvCell('Roof, NE')).toBe('"Roof, NE"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`);
    expect(csvCell(-94)).toBe('-94');
    expect(csvCell(null)).toBe('');
    expect(csvCell(true)).toBe('yes');
  });
  it('writes ranked spots with stats and speeds', () => {
    const slowSpot: FinderSpot = { ...spot, id: 's_2', name: 'Balcony', speed: [], metrics: { ...spot.metrics, score: { ...spot.metrics.score, avg: 80 } } };
    const csv = spotsToCsv([slowSpot, spot]);
    const lines = csv.trim().split('\r\n');
    expect(lines[0]).toBe(SPOT_CSV_HEADER.join(','));
    expect(lines).toHaveLength(3);
    // Measured speed ranks first even with a lower score (plan 7.3).
    expect(lines[1]!.startsWith('1,Roof NE corner,')).toBe(true);
    expect(lines[1]).toContain(',42.1,8.2,41,1,no');
    expect(lines[2]!.startsWith('2,Balcony,')).toBe(true);
  });
});

describe('app list', () => {
  it('parses the app_list output and applies later enable/disable results', () => {
    const rows = parseAppList(JSON.stringify([{ pkg: 'com.b', enabled: true, system: false }, { pkg: 'com.a', enabled: false, system: true }, { bad: 1 }]));
    expect(rows).toEqual([
      { pkg: 'com.a', enabled: false, system: true, label: undefined },
      { pkg: 'com.b', enabled: true, system: false, label: undefined },
    ]);
    const cmd = (over: Partial<CommandRecord>): CommandRecord => ({
      id: 'c',
      device_id: 'd',
      name: 'app_disable',
      args: { package: 'com.b' },
      source: 'dashboard',
      user: 'admin',
      status: 'done',
      created_at: '2026-11-19T10:05:00.000Z',
      ...over,
    });
    const updated = applyAppChanges(rows!, '2026-11-19T10:00:00.000Z', [cmd({}), cmd({ id: 'old', name: 'app_enable', args: { package: 'com.a' }, created_at: '2026-11-19T09:00:00.000Z' })]);
    expect(updated.find((r) => r.pkg === 'com.b')?.enabled).toBe(false);
    expect(updated.find((r) => r.pkg === 'com.a')?.enabled).toBe(false);
    expect(parseAppList('nope')).toBeNull();
  });
});

describe('misc', () => {
  it('backs off exponentially with a cap', () => {
    const mid = () => 0.5; // no jitter
    expect([1, 2, 3, 4, 5, 6, 7].map((a) => backoffDelay(a, 1000, 30_000, mid))).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]);
    expect(backoffDelay(1, 1000, 30_000, () => 0)).toBe(800);
  });
  it('counts SMS parts', () => {
    expect(smsSegments('')).toEqual({ parts: 0, perPart: 160 });
    expect(smsSegments('a'.repeat(160)).parts).toBe(1);
    expect(smsSegments('a'.repeat(161)).parts).toBe(2);
    expect(smsSegments('नमस्ते').perPart).toBe(70);
  });
  it('defaults used by the UI exist', () => {
    expect(DEFAULT_SETTINGS.alerts.low_battery_pct).toBe(20);
  });
});
