/**
 * Time-series helpers for the history charts (GET /api/telemetry) and live appends.
 */
import type { AlignedData } from 'uplot';

export type Point = [number, number | null]; // [unix ms, value]

/**
 * Reads a json_extract-style path from shared/src/metrics.ts ("$.radio.score", "$.system.cpu_load[0]")
 * out of a telemetry message, so live messages can be appended to a chart of that metric.
 */
export function readJsonPath(obj: unknown, path: string): unknown {
  if (!path.startsWith('$')) return undefined;
  const tokens = path
    .slice(1)
    .split(/\.|\[(\d+)\]/)
    .filter((t) => t !== undefined && t !== '');
  let cur: unknown = obj;
  for (const tok of tokens) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = /^\d+$/.test(tok) && Array.isArray(cur) ? cur[Number(tok)] : (cur as Record<string, unknown>)[tok];
  }
  return cur;
}

export function numericAt(obj: unknown, path: string): number | null {
  const v = readJsonPath(obj, path);
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** Appends a point keeping time order; drops points older than `fromMs`. Returns a new array. */
export function appendPoint(points: Point[], p: Point, fromMs: number): Point[] {
  const last = points[points.length - 1];
  let next: Point[];
  if (!last || p[0] > last[0]) next = [...points, p];
  else if (p[0] === last[0]) next = [...points.slice(0, -1), p];
  else return points; // out-of-order live point; the next history fetch has it
  let start = 0;
  while (start < next.length && next[start]![0] < fromMs) start++;
  return start ? next.slice(start) : next;
}

/** Median spacing between points, used to detect gaps (phone offline) worth breaking the line for. */
export function medianStep(points: Point[]): number {
  if (points.length < 3) return 0;
  const steps: number[] = [];
  for (let i = 1; i < points.length; i++) steps.push(points[i]![0] - points[i - 1]![0]);
  steps.sort((a, b) => a - b);
  return steps[Math.floor(steps.length / 2)] ?? 0;
}

/**
 * Merges several series onto one shared x axis (seconds, as uPlot expects).
 * A timestamp missing from one series becomes `undefined` (uPlot joins across it), while a real
 * hole in the data (longer than `gapFactor` x the usual spacing, min `minGapMs`) becomes `null`
 * so the line breaks where the phone was offline.
 */
export function alignSeries(series: Point[][], gapFactor = 4, minGapMs = 3 * 60_000): AlignedData {
  const xsSet = new Set<number>();
  const maps = series.map((pts) => {
    const m = new Map<number, number | null>();
    const step = medianStep(pts);
    const gap = Math.max(minGapMs, step * gapFactor);
    for (let i = 0; i < pts.length; i++) {
      const [t, v] = pts[i]!;
      const prev = pts[i - 1];
      if (prev && t - prev[0] > gap) {
        const breakAt = prev[0] + 1;
        m.set(breakAt, null);
        xsSet.add(breakAt);
      }
      m.set(t, v);
      xsSet.add(t);
    }
    return m;
  });
  const xsMs = [...xsSet].sort((a, b) => a - b);
  const xs = xsMs.map((t) => t / 1000);
  const ys = maps.map((m) => xsMs.map((t) => (m.has(t) ? (m.get(t) as number | null) : undefined)));
  return [xs, ...ys] as unknown as AlignedData;
}

/**
 * How long (ms) the newest points have continuously satisfied `pred`, ending at the last point.
 * Used for "plugged in but discharging for 25 min".
 */
export function trailingDuration(points: Point[], pred: (v: number) => boolean): number {
  let i = points.length - 1;
  while (i >= 0 && points[i]![1] == null) i--;
  if (i < 0) return 0;
  const end = points[i]![0];
  let start = end;
  for (; i >= 0; i--) {
    const v = points[i]![1];
    if (v == null) continue;
    if (!pred(v)) break;
    start = points[i]![0];
  }
  return end - start;
}

/** Mean of the non-null values, or null. */
export function mean(points: Point[]): number | null {
  let sum = 0;
  let n = 0;
  for (const [, v] of points) {
    if (v != null) {
      sum += v;
      n++;
    }
  }
  return n ? sum / n : null;
}
