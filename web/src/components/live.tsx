/** Building blocks shared by the live screens. */
import type { StoredEvent, TelemetryGroups } from '@hsm/shared';
import { useState } from 'react';
import { settingsChangeSummary } from '../lib/events';
import { DASH, fmtNum, formatDateTime, formatRelative, titleCase, toMs } from '../lib/format';
import { gradeOf, gradeTextClass, type GradedMetric } from '../lib/grades';
import { useNow } from '../lib/hooks';
import { useDeviceSettings, useSnapshot } from '../store/selectors';
import { Badge, GradeBar, GradeChip, cx, type Tone } from './ui/primitives';

const FAST: (keyof TelemetryGroups)[] = ['radio', 'net'];
const SLOW: (keyof TelemetryGroups)[] = ['power', 'heat', 'system', 'tether', 'traffic', 'position'];

/**
 * Returns a hint when a group's newest reading is older than its tier should allow
 * (plan 8.2 sending tiers), or when the phone is offline.
 */
export function useStaleness(...groups: (keyof TelemetryGroups)[]): string | undefined {
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const now = useNow(5000);
  if (!snap) return undefined;
  const times = groups.map((g) => toMs(snap.state_ts[g])).filter((t): t is number => t != null);
  if (!times.length) return undefined;
  const newest = Math.max(...times);
  const age = now - newest;
  const fast = groups.some((g) => FAST.includes(g));
  const slow = groups.some((g) => SLOW.includes(g));
  const limit = fast
    ? Math.max(75_000, settings.telemetry.fast_active_s * 3000)
    : slow
      ? Math.max(150_000, settings.telemetry.slow_s * 2500)
      : Number.POSITIVE_INFINITY;
  if (!snap.online) return `Phone offline · last reading ${formatRelative(newest, now)}`;
  if (age > limit) return `Stale · last reading ${formatRelative(newest, now)}`;
  return undefined;
}

export function UpdatedAt({ ts }: { ts: string | undefined }) {
  const now = useNow(5000);
  if (!ts) return <span className="text-xs text-faint">No reading yet</span>;
  return (
    <span className="text-xs text-faint" title={formatDateTime(ts)}>
      Updated {formatRelative(ts, now)}
    </span>
  );
}

/** A signal value with unit, grade chip and bar (plan 7.1 colours). */
export function SignalMetric({
  label,
  metric,
  value,
  unit,
  barRange,
  big,
}: {
  label: string;
  metric: GradedMetric;
  value: number | null | undefined;
  unit: string;
  /** [worst, best] for the bar fill. */
  barRange: [number, number];
  big?: boolean;
}) {
  const grade = gradeOf(metric, value);
  const fill = value == null ? null : ((value - barRange[0]) / (barRange[1] - barRange[0])) * 100;
  return (
    <div className="min-w-0 space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
        <GradeChip grade={grade} />
      </div>
      <p className={cx('tabular font-semibold leading-none', big ? 'text-4xl' : 'text-3xl', gradeTextClass(grade))}>
        {value == null ? DASH : fmtNum(value, '', 1)}
        <span className="ml-1 text-base font-medium text-muted">{unit}</span>
      </p>
      <GradeBar grade={grade} value={fill == null ? null : Math.max(0, Math.min(100, fill))} />
    </div>
  );
}

export const SEVERITY_TONE: Record<string, Tone> = { info: 'info', warning: 'warn', critical: 'danger' };

export function EventRow({ event, now }: { event: StoredEvent; now: number }) {
  const [open, setOpen] = useState(false);
  const hasData = event.data && Object.keys(event.data).length > 0;
  return (
    <li className="py-2.5">
      <div className="flex flex-wrap items-start gap-x-2 gap-y-1">
        <Badge tone={SEVERITY_TONE[event.severity] ?? 'neutral'}>{event.severity}</Badge>
        <span className="text-xs font-medium text-muted">{titleCase(event.kind)}</span>
        <span className="ml-auto text-xs text-faint" title={formatDateTime(event.ts)}>
          {formatRelative(event.ts, now)}
        </span>
      </div>
      <p className="mt-1 text-sm text-fg break-anywhere">{event.message}</p>
      {event.kind === 'settings_changed' ? <SettingsChangeDetail data={event.data} /> : null}
      {hasData ? (
        <>
          <button type="button" className="mt-1 text-xs text-accent hover:underline" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
            {open ? 'Hide details' : 'Details'}
          </button>
          {open ? (
            <pre className="mt-1 max-h-48 overflow-auto rounded-lg bg-surface-2 p-2 font-mono text-[11px] whitespace-pre-wrap break-anywhere">
              {JSON.stringify(event.data, null, 2)}
            </pre>
          ) : null}
        </>
      ) : null}
    </li>
  );
}

/** Who changed which settings ('settings_changed', the Server's audit record of PUT /settings). */
function SettingsChangeDetail({ data }: { data: StoredEvent['data'] }) {
  const c = settingsChangeSummary(data);
  const head = [c.user ? `by ${c.user}` : null, c.version != null ? `version ${c.version}` : null].filter(Boolean).join(' · ');
  const line = [head, c.fields.join(', ')].filter(Boolean).join(' · ');
  return (
    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted">
      {line ? <span className="min-w-0 break-anywhere">{line}</span> : null}
      {c.profileOverride ? <Badge tone="warn">Root command templates changed</Badge> : null}
    </div>
  );
}

export function EventList({ events, empty = 'No events yet.' }: { events: StoredEvent[]; empty?: string }) {
  const now = useNow(10_000);
  if (!events.length) return <p className="py-4 text-sm text-muted">{empty}</p>;
  return (
    <ul className="divide-y divide-border">
      {events.map((e) => (
        <EventRow key={e.id} event={e} now={now} />
      ))}
    </ul>
  );
}

export function YesNo({ value, yes = 'Yes', no = 'No' }: { value: boolean | null | undefined; yes?: string; no?: string }) {
  if (value == null) return <span className="text-faint">{DASH}</span>;
  return <Badge tone={value ? 'ok' : 'neutral'}>{value ? yes : no}</Badge>;
}

export function OnOff({ value }: { value: boolean | null | undefined }) {
  return <YesNo value={value} yes="On" no="Off" />;
}
