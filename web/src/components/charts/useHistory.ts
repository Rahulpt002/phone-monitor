/**
 * History for one or more metrics from GET /api/telemetry, kept live by appending matching values
 * from the Dashboard socket, and refetched after a reconnect of the Dashboard socket or of the
 * phone (neither socket replays the backlog live).
 */
import { METRIC_BY_KEY, type MetricKey } from '@hsm/shared';
import { useEffect, useMemo, useRef, useState } from 'react';
import { api, errorMessage, isAbort } from '../../lib/api';
import { appendPoint, numericAt, type Point } from '../../lib/series';
import { KEYS, storage } from '../../lib/storage';
import { useConn } from '../../store/conn';
import { useData } from '../../store/data';
import { useUi } from '../../store/ui';

export type RangeKey = '1h' | '6h' | '24h' | '7d' | '30d';

export const RANGES: { value: RangeKey; label: string; ms: number }[] = [
  { value: '1h', label: '1 h', ms: 3_600_000 },
  { value: '6h', label: '6 h', ms: 6 * 3_600_000 },
  { value: '24h', label: '24 h', ms: 24 * 3_600_000 },
  { value: '7d', label: '7 d', ms: 7 * 86_400_000 },
  { value: '30d', label: '30 d', ms: 30 * 86_400_000 },
];

/**
 * After the phone comes back online its outbox backlog is stored by the Server but not relayed
 * live (older than live_window_s, docs/protocol.md 3.1), so charts refetch after these delays.
 */
export const BACKLOG_REFETCH_MS = [20_000, 90_000] as const;

export const rangeMs = (r: RangeKey) => RANGES.find((x) => x.value === r)?.ms ?? 3_600_000;

export function useRange(): [RangeKey, (r: RangeKey) => void] {
  const saved = storage.get(KEYS.range) as RangeKey | null;
  const [range, setRange] = useState<RangeKey>(saved && RANGES.some((r) => r.value === saved) ? saved : '6h');
  return [
    range,
    (r) => {
      storage.set(KEYS.range, r);
      setRange(r);
    },
  ];
}

export interface HistoryState {
  series: Record<string, Point[]>;
  loading: boolean;
  error: string | null;
  res: 'raw' | '1m' | null;
  reload: () => void;
}

export function useHistory(metrics: MetricKey[], range: RangeKey): HistoryState {
  const deviceId = useUi((s) => s.deviceId);
  const epoch = useConn((s) => s.epoch);
  const key = metrics.join(',');
  const span = rangeMs(range);
  const [series, setSeries] = useState<Record<string, Point[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [res, setRes] = useState<'raw' | '1m' | null>(null);
  const [tick, setTick] = useState(0);
  const loadedFor = useRef<string>('');

  // Long ranges use 1-minute averages: refetch every few minutes instead of appending raw points.
  const refreshEvery = span > 6 * 3_600_000 ? 5 * 60_000 : 0;

  useEffect(() => {
    if (!deviceId) {
      setSeries({});
      setLoading(false);
      return;
    }
    const ctrl = new AbortController();
    const to = new Date();
    const from = new Date(to.getTime() - span);
    const signature = `${deviceId}|${key}|${range}`;
    if (loadedFor.current !== signature) setSeries({});
    setLoading(true);
    setError(null);
    Promise.all(
      metrics.map((metric) =>
        api.telemetry({ device_id: deviceId, metric, from: from.toISOString(), to: to.toISOString(), res: 'auto' }, ctrl.signal),
      ),
    )
      .then((results) => {
        const next: Record<string, Point[]> = {};
        results.forEach((r, i) => {
          next[metrics[i]!] = (r.points ?? []).filter((p): p is Point => Array.isArray(p) && typeof p[0] === 'number');
        });
        const sameView = loadedFor.current === signature;
        loadedFor.current = signature;
        // Keep live points appended while this request was in flight (newer than its `to`).
        setSeries((cur) => {
          if (!sameView) return next;
          const out = { ...next };
          for (const k of Object.keys(out)) {
            const later = (cur[k] ?? []).filter((p) => p[0] > to.getTime());
            if (later.length) out[k] = [...out[k]!, ...later];
          }
          return out;
        });
        setRes(results[0]?.res ?? null);
      })
      .catch((e: unknown) => {
        if (!isAbort(e)) setError(errorMessage(e));
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, key, range, epoch, tick]);

  useEffect(() => {
    if (!refreshEvery) return;
    const id = setInterval(() => setTick((t) => t + 1), refreshEvery);
    return () => clearInterval(id);
  }, [refreshEvery]);

  // Phone back online after an outage: fetch the gap once its backlog has had time to arrive.
  useEffect(() => {
    if (!deviceId) return;
    let online = useData.getState().snapshots[deviceId]?.online;
    const timers: ReturnType<typeof setTimeout>[] = [];
    const unsubscribe = useData.subscribe((s) => {
      const now = s.snapshots[deviceId]?.online;
      if (now === online) return;
      const cameBack = online === false && now === true;
      online = now;
      if (!cameBack) return;
      for (const delay of BACKLOG_REFETCH_MS) timers.push(setTimeout(() => setTick((t) => t + 1), delay));
    });
    return () => {
      unsubscribe();
      for (const t of timers) clearTimeout(t);
    };
  }, [deviceId]);

  // Live appends for raw-resolution ranges.
  useEffect(() => {
    if (!deviceId || refreshEvery) return;
    const defs = metrics.map((m) => METRIC_BY_KEY[m]).filter((d): d is NonNullable<typeof d> => Boolean(d));
    return useData.subscribe((s, prev) => {
      const msg = s.lastTelemetry;
      if (!msg || msg === prev.lastTelemetry || msg.device_id !== deviceId) return;
      const t = Date.parse(msg.ts);
      if (Number.isNaN(t)) return;
      const relevant = defs.filter((d) => d.tier === msg.tier);
      if (!relevant.length) return;
      setSeries((cur) => {
        let changed = false;
        const next = { ...cur };
        for (const d of relevant) {
          const v = numericAt(msg, d.path);
          if (v == null) continue;
          next[d.key] = appendPoint(cur[d.key] ?? [], [t, v], Date.now() - span);
          changed = true;
        }
        return changed ? next : cur;
      });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, key, span, refreshEvery]);

  return useMemo(
    () => ({ series, loading, error, res, reload: () => setTick((t) => t + 1) }),
    [series, loading, error, res],
  );
}
