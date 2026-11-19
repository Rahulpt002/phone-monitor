/**
 * F49: date-only strings (the Agent's LocalDate, e.g. traffic.cycle_start '2026-11-01') are calendar
 * days, not UTC midnight. Runs in a zone west of UTC, where the old parsing showed the day before.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const savedTz = process.env.TZ;
let format: typeof import('../src/lib/format');

beforeAll(async () => {
  process.env.TZ = 'America/Los_Angeles';
  // Imported after the zone is set: the module creates its Intl formatters at load time.
  format = await import('../src/lib/format');
});

afterAll(() => {
  if (savedTz === undefined) delete process.env.TZ;
  else process.env.TZ = savedTz;
});

describe('date-only values', () => {
  it('are read as that calendar day in the local zone', () => {
    expect(new Date(0).getTimezoneOffset()).toBeGreaterThan(0); // the zone switch took effect
    expect(format.toMs('2026-11-01')).toBe(new Date(2026, 10, 1).getTime());
    expect(format.formatDate('2026-11-01')).toBe(
      new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(2026, 10, 1)),
    );
    expect(format.formatDate('2026-11-01')).toMatch(/\b1\b/);
    expect(format.formatDate('2026-11-01')).not.toMatch(/31/);
  });
  it('leave full timestamps and invalid dates alone', () => {
    expect(format.toMs('2026-11-01T00:00:00.000Z')).toBe(Date.UTC(2026, 10, 1));
    expect(format.toMs('2026-02-30')).toBeNull();
    expect(format.toMs('nope')).toBeNull();
  });
});
