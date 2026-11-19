/**
 * uPlot line chart. One y axis per chart (never dual-axis); the legend doubles as the hover
 * readout (crosshair + values). Colours come from the theme tokens, so the chart is rebuilt when
 * the theme changes.
 */
import { useEffect, useMemo, useRef } from 'react';
import uPlot, { type AlignedData, type Options } from 'uplot';
import 'uplot/dist/uPlot.min.css';
import { alignSeries, type Point } from '../../lib/series';
import { useUi } from '../../store/ui';
import { Spinner } from '../ui/primitives';

export interface SeriesSpec {
  label: string;
  points: Point[];
  /** CSS variable holding the colour, e.g. "--series-1". */
  colorVar?: string;
}

export interface TimeChartProps {
  series: SeriesSpec[];
  ariaLabel: string;
  height?: number;
  format: (v: number) => string;
  /** Dashed reference lines (grade thresholds, baseline). */
  thresholds?: { value: number; colorVar?: string }[];
  /** Fixed y range; null ends are auto. */
  yRange?: [number | null, number | null];
  loading?: boolean;
  error?: string | null;
  /** Window to show even when the data is sparse. */
  xWindow?: [number, number];
}

const SERIES_VARS = ['--series-1', '--series-2', '--series-3', '--series-4'];

function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export function TimeChart({ series, ariaLabel, height = 200, format, thresholds, yRange, loading, error, xWindow }: TimeChartProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const plotRef = useRef<uPlot | null>(null);
  const theme = useUi((s) => s.theme);
  const formatRef = useRef(format);
  formatRef.current = format;
  const thresholdsRef = useRef(thresholds);
  thresholdsRef.current = thresholds;

  const data: AlignedData = useMemo(() => alignSeries(series.map((s) => s.points)), [series]);
  const shape = series.map((s) => `${s.label}|${s.colorVar ?? ''}`).join(';');
  const hasData = (data[0]?.length ?? 0) > 0;
  const yKey = yRange ? `${yRange[0]}:${yRange[1]}` : 'auto';

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const axis = cssVar('--chart-axis', '#888');
    const grid = cssVar('--chart-grid', 'rgba(127,127,127,0.15)');
    const width = Math.max(200, el.clientWidth);

    const opts: Options = {
      width,
      height,
      padding: [8, 8, 0, 0],
      cursor: { drag: { x: false, y: false }, points: { size: 8 } },
      legend: { show: true, live: true },
      scales: {
        x: { time: true },
        y: {
          auto: true,
          range: (_u, min, max) => {
            const lo = yRange?.[0] ?? (min === max ? min - 1 : min);
            const hi = yRange?.[1] ?? (min === max ? max + 1 : max);
            const pad = yRange ? 0 : (hi - lo) * 0.08;
            return [yRange?.[0] ?? lo - pad, yRange?.[1] ?? hi + pad];
          },
        },
      },
      axes: [
        { stroke: axis, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid, width: 1 } },
        {
          stroke: axis,
          grid: { stroke: grid, width: 1 },
          ticks: { stroke: grid, width: 1 },
          // Wide enough for the longest tick label ("-100 dBm", "12.5 Mbps").
          size: (_u, values) => {
            const longest = values?.reduce((n, v) => Math.max(n, String(v ?? '').length), 0) ?? 6;
            return Math.max(44, Math.ceil(longest * 7 + 18));
          },
          values: (_u, vals) => vals.map((v) => (v == null ? '' : formatRef.current(v))),
        },
      ],
      series: [
        { label: 'Time', value: (_u, v) => (v == null ? '—' : new Date(v * 1000).toLocaleString()) },
        ...series.map((s, i) => ({
          label: s.label,
          stroke: cssVar(s.colorVar ?? SERIES_VARS[i % SERIES_VARS.length]!, '#3987e5'),
          width: 2,
          points: { show: false },
          spanGaps: false,
          value: (_u: uPlot, v: number | null) => (v == null ? '—' : formatRef.current(v)),
        })),
      ],
      hooks: {
        draw: [
          (u) => {
            const lines = thresholdsRef.current;
            if (!lines?.length) return;
            const { ctx } = u;
            const { left, top, width: w, height: h } = u.bbox;
            ctx.save();
            ctx.lineWidth = Math.max(1, devicePixelRatio);
            ctx.setLineDash([6 * devicePixelRatio, 4 * devicePixelRatio]);
            for (const t of lines) {
              const y = u.valToPos(t.value, 'y', true);
              if (y < top || y > top + h) continue;
              ctx.strokeStyle = cssVar(t.colorVar ?? '--chart-axis', axis);
              ctx.globalAlpha = 0.6;
              ctx.beginPath();
              ctx.moveTo(left, y);
              ctx.lineTo(left + w, y);
              ctx.stroke();
            }
            ctx.restore();
          },
        ],
      },
    };

    const plot = new uPlot(opts, data, el);
    plotRef.current = plot;
    if (xWindow) plot.setScale('x', { min: xWindow[0] / 1000, max: xWindow[1] / 1000 });

    const ro = new ResizeObserver(() => {
      const w = Math.max(200, el.clientWidth);
      if (w !== plot.width) plot.setSize({ width: w, height });
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
    // Rebuild only when the structure or theme changes; data updates go through setData below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shape, theme, height, yKey]);

  useEffect(() => {
    const plot = plotRef.current;
    if (!plot) return;
    plot.setData(data, !xWindow);
    if (xWindow) plot.setScale('x', { min: xWindow[0] / 1000, max: xWindow[1] / 1000 });
  }, [data, xWindow?.[0], xWindow?.[1]]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <figure className="relative m-0 min-w-0" aria-label={ariaLabel}>
      <div ref={wrapRef} className="w-full min-w-0 overflow-hidden" aria-hidden />
      {!hasData ? (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center" style={{ height }}>
          {loading ? (
            <Spinner label="Loading chart" />
          ) : (
            <p className="rounded-lg bg-surface/80 px-3 py-1.5 text-sm text-muted">{error ? `Could not load: ${error}` : 'No data for this range'}</p>
          )}
        </div>
      ) : error ? (
        <p className="mt-1 text-xs text-danger">Refresh failed: {error}</p>
      ) : null}
      <ChartSummary series={series} format={format} />
    </figure>
  );
}

/** Text alternative: latest, min and max of each series (also useful at a glance). */
function ChartSummary({ series, format }: { series: SeriesSpec[]; format: (v: number) => string }) {
  const rows = series.map((s) => {
    const vals = s.points.map((p) => p[1]).filter((v): v is number => v != null);
    return {
      label: s.label,
      latest: vals.length ? format(vals[vals.length - 1]!) : '—',
      min: vals.length ? format(Math.min(...vals)) : '—',
      max: vals.length ? format(Math.max(...vals)) : '—',
    };
  });
  return (
    <figcaption className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-muted">
      {rows.map((r) => (
        <span key={r.label} className="tabular">
          {series.length > 1 ? <span className="font-medium text-fg">{r.label}: </span> : null}
          latest {r.latest} · min {r.min} · max {r.max}
        </span>
      ))}
    </figcaption>
  );
}
