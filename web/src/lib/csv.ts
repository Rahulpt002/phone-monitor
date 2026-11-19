/** Client-side CSV export of saved Finder spots (plan 7.3 "Ranking ... Exports CSV"). */
import { rankSpots, spotBestDown, type FinderSpot, type MetricStats } from '@hsm/shared';

type Cell = string | number | boolean | null | undefined;

/** RFC 4180 quoting; text that a spreadsheet would run as a formula is prefixed with a quote. */
export function csvCell(v: Cell): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : '';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  let s = v;
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function toCsv(rows: Cell[][]): string {
  return rows.map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

const stats = (m: MetricStats | null | undefined): Cell[] =>
  m ? [round(m.avg), round(m.min), round(m.max), round(m.std)] : ['', '', '', ''];

const round = (v: number | null | undefined, d = 2): number | null =>
  v == null || !Number.isFinite(v) ? null : Math.round(v * 10 ** d) / 10 ** d;

const best = (vals: (number | null)[], pick: 'max' | 'min'): number | null => {
  const v = vals.filter((x): x is number => x != null);
  if (!v.length) return null;
  return pick === 'max' ? Math.max(...v) : Math.min(...v);
};

export const SPOT_CSV_HEADER = [
  'rank', 'name', 'created_at', 'baseline', 'orientation', 'lat', 'lon', 'accuracy_m', 'compass_deg',
  'net_type', 'band', 'pci', 'cell_id', 'earfcn', 'ta', 'cell_switches',
  'score_avg', 'score_min', 'score_max', 'score_std',
  'rsrp_avg', 'rsrp_min', 'rsrp_max', 'rsrp_std',
  'sinr_avg', 'sinr_min', 'sinr_max', 'sinr_std',
  'rsrq_avg', 'rsrq_min', 'rsrq_max', 'rsrq_std',
  'best_down_mbps', 'best_up_mbps', 'best_ping_ms', 'speed_sessions', 'peak_hour_tested',
];

export function spotsToCsv(spots: FinderSpot[]): string {
  const rows: Cell[][] = [SPOT_CSV_HEADER];
  rankSpots(spots).forEach((s, i) => {
    rows.push([
      i + 1,
      s.name,
      s.created_at,
      s.baseline,
      s.orientation,
      s.lat,
      s.lon,
      s.acc_m,
      s.compass_deg,
      s.serving.net_type,
      s.serving.band,
      s.serving.pci,
      s.serving.cell_id,
      s.serving.earfcn,
      s.serving.ta,
      s.cell_switches,
      ...stats(s.metrics.score),
      ...stats(s.metrics.rsrp),
      ...stats(s.metrics.sinr),
      ...stats(s.metrics.rsrq),
      spotBestDown(s),
      best(s.speed.map((x) => x.up_mbps), 'max'),
      best(s.speed.map((x) => x.ping_ms), 'min'),
      s.speed.length,
      s.speed.some((x) => x.peak_hour),
    ]);
  });
  return toCsv(rows);
}

export function downloadText(filename: string, text: string, mime = 'text/csv;charset=utf-8'): void {
  const blob = new Blob(['﻿', text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
