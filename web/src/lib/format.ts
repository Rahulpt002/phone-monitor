/** Display formatting. Every helper returns DASH for missing values instead of inventing one. */

export const DASH = '—';

type Num = number | null | undefined;

const isNum = (v: Num): v is number => typeof v === 'number' && Number.isFinite(v);

/** Trim to `digits` decimals and drop trailing zeros: 2.10 -> "2.1", 41.0 -> "41". */
export function trimNumber(v: number, digits: number): string {
  const s = v.toFixed(digits);
  return s.includes('.') ? s.replace(/\.?0+$/, '') : s;
}

/** Fewer decimals as the value grows: 1.23, 12.3, 123. */
function smart(v: number): string {
  const a = Math.abs(v);
  return trimNumber(v, a < 10 ? 2 : a < 100 ? 1 : 0);
}

/** Bits per second, decimal units (network convention): 5400000 -> "5.4 Mbps". */
export function formatBps(bps: Num): string {
  if (!isNum(bps)) return DASH;
  const units = ['bps', 'kbps', 'Mbps', 'Gbps', 'Tbps'];
  let v = Math.max(0, bps);
  let i = 0;
  while (v >= 1000 && i < units.length - 1) {
    v /= 1000;
    i++;
  }
  return `${i === 0 ? Math.round(v) : smart(v)} ${units[i]}`;
}

/** Megabits per second value from a speed test. */
export function formatMbps(mbps: Num): string {
  return isNum(mbps) ? `${smart(mbps)} Mbps` : DASH;
}

/** Bytes, decimal units (carriers bill in GB = 10^9 bytes): 2100000000 -> "2.1 GB". */
export function formatBytes(bytes: Num): string {
  if (!isNum(bytes)) return DASH;
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let v = Math.max(0, bytes);
  let i = 0;
  while (v >= 1000 && i < units.length - 1) {
    v /= 1000;
    i++;
  }
  return `${i === 0 ? Math.round(v) : smart(v)} ${units[i]}`;
}

/** Megabytes (as reported by /proc and StatFs) shown as MB or GB. */
export function formatMb(mb: Num): string {
  if (!isNum(mb)) return DASH;
  return mb >= 1000 ? `${smart(mb / 1000)} GB` : `${Math.round(mb)} MB`;
}

/** Seconds -> compact duration with the two largest units: "45 s", "5 min 12 s", "3 h 4 min", "2 d 3 h". */
export function formatDuration(seconds: Num): string {
  if (!isNum(seconds)) return DASH;
  let s = Math.max(0, Math.round(seconds));
  if (s < 60) return `${s} s`;
  const d = Math.floor(s / 86400);
  s -= d * 86400;
  const h = Math.floor(s / 3600);
  s -= h * 3600;
  const m = Math.floor(s / 60);
  s -= m * 60;
  if (d > 0) return h > 0 ? `${d} d ${h} h` : `${d} d`;
  if (h > 0) return m > 0 ? `${h} h ${m} min` : `${h} h`;
  return s > 0 ? `${m} min ${s} s` : `${m} min`;
}

export function toMs(t: string | number | Date | null | undefined): number | null {
  if (t == null) return null;
  if (typeof t === 'number') return Number.isFinite(t) ? t : null;
  if (t instanceof Date) return t.getTime();
  const ms = Date.parse(t);
  return Number.isNaN(ms) ? null : ms;
}

/** "just now", "12 s ago", "5 min ago", "3 h ago", "2 d ago", or "in 30 s" for future times. */
export function formatRelative(t: string | number | Date | null | undefined, now: number = Date.now()): string {
  const ms = toMs(t);
  if (ms == null) return 'never';
  const diff = Math.round((now - ms) / 1000);
  const abs = Math.abs(diff);
  if (abs < 5) return 'just now';
  let body: string;
  if (abs < 60) body = `${abs} s`;
  else if (abs < 3600) body = `${Math.floor(abs / 60)} min`;
  else if (abs < 86400) body = `${Math.floor(abs / 3600)} h`;
  else if (abs < 86400 * 60) body = `${Math.floor(abs / 86400)} d`;
  else return formatDate(ms);
  return diff >= 0 ? `${body} ago` : `in ${body}`;
}

/** Countdown "m:ss" for an auto-revert or a pairing code; negative clamps to 0:00. */
export function formatCountdown(msLeft: number): string {
  const s = Math.max(0, Math.ceil(msLeft / 1000));
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, '0')}`;
}

const dateTimeFmt = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});
const dateFmt = new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' });

export function formatDateTime(t: string | number | Date | null | undefined): string {
  const ms = toMs(t);
  return ms == null ? DASH : dateTimeFmt.format(ms);
}

export function formatDate(t: string | number | Date | null | undefined): string {
  const ms = toMs(t);
  return ms == null ? DASH : dateFmt.format(ms);
}

export function formatTime(t: string | number | Date | null | undefined): string {
  const ms = toMs(t);
  return ms == null ? DASH : timeFmt.format(ms);
}

/** Generic number with unit: fmtNum(-94, ' dBm') -> "-94 dBm". */
export function fmtNum(v: Num, unit = '', digits = 1): string {
  return isNum(v) ? `${trimNumber(v, digits)}${unit}` : DASH;
}

export function formatPercent(v: Num, digits = 0): string {
  return isNum(v) ? `${trimNumber(v, digits)}%` : DASH;
}

export function formatTemp(c: Num): string {
  return isNum(c) ? `${trimNumber(c, 1)} °C` : DASH;
}

/** Signed battery current: +120 mA charging, -35 mA draining. */
export function formatCurrent(ma: Num): string {
  if (!isNum(ma)) return DASH;
  const r = Math.round(ma);
  return `${r > 0 ? '+' : ''}${r} mA`;
}

export function formatVoltage(mv: Num): string {
  return isNum(mv) ? `${trimNumber(mv / 1000, 2)} V` : DASH;
}

/** Timing advance -> rough distance to the tower (LTE: ~78 m per step). */
export function formatTaDistance(ta: Num): string {
  if (!isNum(ta)) return DASH;
  const m = ta * 78;
  return m >= 1000 ? `~${trimNumber(m / 1000, 1)} km` : `~${Math.round(m)} m`;
}

export function titleCase(s: string): string {
  return s.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}

const NET_TYPE_LABEL: Record<string, string> = {
  LTE: '4G LTE',
  NR_NSA: '5G NSA',
  NR_SA: '5G SA',
  WCDMA: '3G WCDMA',
  GSM: '2G GSM',
  CDMA: 'CDMA',
  TDSCDMA: '3G TD-SCDMA',
  UNKNOWN: 'Unknown',
};

export function formatNetType(t: string | null | undefined): string {
  return t ? (NET_TYPE_LABEL[t] ?? t) : DASH;
}

export const THERMAL_STATUS = ['None', 'Light', 'Moderate', 'Severe', 'Critical', 'Emergency', 'Shutdown'] as const;

export function formatThermalStatus(v: Num): string {
  return isNum(v) ? (THERMAL_STATUS[v] ?? String(v)) : DASH;
}

export const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'] as const;
