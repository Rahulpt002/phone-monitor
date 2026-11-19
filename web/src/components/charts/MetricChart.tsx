import { METRIC_BY_KEY, type MetricKey } from '@hsm/shared';
import { useMemo } from 'react';
import { Segmented } from '../ui/forms';
import { Card } from '../ui/primitives';
import { TimeChart } from './TimeChart';
import { RANGES, rangeMs, useHistory, type RangeKey } from './useHistory';

export function RangePicker({ value, onChange }: { value: RangeKey; onChange: (r: RangeKey) => void }) {
  return <Segmented label="Chart range" value={value} options={RANGES.map((r) => ({ value: r.value, label: r.label }))} onChange={onChange} />;
}

/**
 * A card with a history chart of one or more metrics that share a unit (one axis per chart).
 */
export function MetricChart({
  title,
  metrics,
  labels,
  range,
  format,
  thresholds,
  yRange,
  height,
  transform,
}: {
  title: string;
  metrics: MetricKey[];
  labels?: string[];
  range: RangeKey;
  format: (v: number) => string;
  thresholds?: { value: number; colorVar?: string }[];
  yRange?: [number | null, number | null];
  height?: number;
  /** Convert stored values for display (e.g. mV -> V). */
  transform?: (v: number) => number;
}) {
  const h = useHistory(metrics, range);
  const metricsKey = metrics.join(',');
  const labelsKey = labels?.join(',') ?? '';
  const series = useMemo(
    () =>
      metrics.map((m, i) => ({
        label: labels?.[i] ?? METRIC_BY_KEY[m]?.label ?? m,
        points: transform ? (h.series[m] ?? []).map(([t, v]) => [t, v == null ? null : transform(v)] as [number, number | null]) : (h.series[m] ?? []),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [metricsKey, labelsKey, h.series, transform],
  );
  // Rounded so re-renders within the same 5 s do not move the window.
  const now = Math.ceil(Date.now() / 5000) * 5000;
  return (
    <Card title={title} actions={h.res ? <span className="text-xs text-faint">{h.res === 'raw' ? 'raw readings' : '1-minute averages'}</span> : null}>
      <TimeChart
        series={series}
        ariaLabel={`${title} over the last ${range}`}
        format={format}
        thresholds={thresholds}
        yRange={yRange}
        height={height}
        loading={h.loading}
        error={h.error}
        xWindow={[now - rangeMs(range), now]}
      />
    </Card>
  );
}
