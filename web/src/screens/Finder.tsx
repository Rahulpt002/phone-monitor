import { rankSpots, spotBestDown, type FinderSpot, type MetricStats } from '@hsm/shared';
import { useEffect, useMemo, useRef, useState } from 'react';
import { TimeChart } from '../components/charts/TimeChart';
import { useRunCommand } from '../components/commands/CommandRunner';
import { CommandCard } from '../components/controls/CommandCard';
import { SignalMetric } from '../components/live';
import { IconDownload, IconStar } from '../components/ui/icons';
import { Badge, Button, Card, Empty, GradeChip, KeyValues, Notice, PageHeader, Progress, cx } from '../components/ui/primitives';
import { spotsToCsv, downloadText } from '../lib/csv';
import { DASH, formatDateTime, formatMbps, formatNetType, formatRelative, formatTaDistance, trimNumber } from '../lib/format';
import { GRADE_LABEL, gradeOf, gradeTextClass } from '../lib/grades';
import { useDocumentTitle, useNow } from '../lib/hooks';
import type { Point } from '../lib/series';
import { useConn } from '../store/conn';
import { useData } from '../store/data';
import { useCanControl, useDeviceId, useSnapshot, useSpots } from '../store/selectors';

const LIVE_WINDOW_MS = 3 * 60_000;
const fmtScore = (v: number) => trimNumber(v, 0);

/** Grade bands of the score (plan 7.2) with a marker at the current value. */
function ScoreBar({ score }: { score: number | null }) {
  const pos = score == null ? null : Math.max(0, Math.min(100, score));
  return (
    <div className="relative mt-2" aria-hidden>
      <div className="flex h-5 overflow-hidden rounded-full">
        <div className="bg-grade-poor" style={{ width: '35%' }} />
        <div className="bg-grade-fair" style={{ width: '20%' }} />
        <div className="bg-grade-good" style={{ width: '20%' }} />
        <div className="bg-grade-excellent" style={{ width: '25%' }} />
      </div>
      {pos != null ? (
        <div className="absolute -top-1.5 h-8 w-1.5 -translate-x-1/2 rounded-full bg-fg shadow ring-2 ring-bg motion-safe:transition-[left]" style={{ left: `${pos}%` }} />
      ) : null}
      <div className="mt-1 flex justify-between text-[10px] text-muted">
        <span>0</span>
        <span>35</span>
        <span>55</span>
        <span>75</span>
        <span>100</span>
      </div>
    </div>
  );
}

function LiveMirror() {
  const deviceId = useDeviceId();
  const snap = useSnapshot();
  const entry = useData((s) => (deviceId ? s.finderLive[deviceId] : undefined));
  const connOpen = useConn((s) => s.status === 'open');
  const now = useNow(1000);
  const [trail, setTrail] = useState<Point[]>([]);
  const lastTs = useRef<string | null>(null);

  useEffect(() => {
    if (!entry || entry.live.ts === lastTs.current || entry.live.score == null) return;
    lastTs.current = entry.live.ts;
    const t = Date.parse(entry.live.ts) || entry.received_at;
    setTrail((cur) => [...cur.filter((p) => p[0] > t - LIVE_WINDOW_MS && p[0] < t), [t, entry.live.score]]);
  }, [entry]);

  const live = entry?.live;
  const age = entry ? now - entry.received_at : null;
  const fresh = age != null && age < 6000;
  const grade = gradeOf('score', live?.score);
  const series = useMemo(() => [{ label: 'Score', points: trail }], [trail]);

  return (
    <Card title="Live mirror" actions={live ? <Badge tone={fresh ? 'ok' : 'warn'}>{fresh ? `${live.mode} mode` : `last update ${formatRelative(entry!.received_at, now)}`}</Badge> : null}>
      {!fresh ? (
        <div className="mb-3">
          <Notice tone={snap?.online === false ? 'warn' : 'info'}>
            {snap?.online === false
              ? 'The phone is offline. The mirror starts when it reconnects.'
              : !connOpen
                ? 'Waiting for the live connection to the server…'
                : 'Asking the phone to stream the score once per second. It starts within a few seconds.'}
          </Notice>
        </div>
      ) : null}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <div>
          <div className="flex items-end justify-between gap-3">
            <p className={cx('tabular text-[6rem] font-bold leading-none sm:text-[8rem]', gradeTextClass(grade), !fresh && 'opacity-60')} aria-live="off">
              {live?.score != null ? Math.round(live.score) : DASH}
            </p>
            <div className="mb-3 text-right">
              <GradeChip grade={grade} className="text-sm" />
              {live?.score_raw != null ? <p className="mt-1 text-xs text-muted">raw {Math.round(live.score_raw)}</p> : null}
            </div>
          </div>
          <span className="sr-only">Score {live?.score != null ? Math.round(live.score) : 'unknown'}{grade ? `, ${GRADE_LABEL[grade]}` : ''}</span>
          <ScoreBar score={live?.score ?? null} />
          {live?.progress ? (
            <div className="mt-4 space-y-1">
              <p className="text-sm font-medium text-fg">
                {live.progress.label} · {Math.round(live.progress.elapsed_s)} of {Math.round(live.progress.total_s)} s
              </p>
              <Progress value={live.progress.elapsed_s} max={live.progress.total_s || 1} label={live.progress.label} />
            </div>
          ) : null}
        </div>
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <SignalMetric label="RSRP" metric="rsrp" value={live?.rsrp} unit="dBm" barRange={[-120, -70]} />
            <SignalMetric label="SINR" metric="sinr" value={live?.sinr} unit="dB" barRange={[-5, 25]} />
            <SignalMetric label="RSRQ" metric="rsrq" value={live?.rsrq} unit="dB" barRange={[-20, -8]} />
          </div>
          <KeyValues
            items={[
              ['Network', formatNetType(live?.net_type)],
              ['Band', live?.band ?? DASH],
              ['PCI', live?.pci ?? DASH],
              ['Cell ID', live?.cell_id ?? DASH],
              ['Timing advance', live?.ta != null ? `${live.ta} (${formatTaDistance(live.ta)})` : DASH],
              [
                'Cell switches (5 min)',
                live?.cell_switches_5min != null ? (
                  <Badge key="cs" tone={live.cell_switches_5min > 0 ? 'warn' : 'ok'}>
                    {live.cell_switches_5min}
                  </Badge>
                ) : (
                  DASH
                ),
              ],
            ]}
          />
          {live?.cell_switches_5min ? (
            <p className="text-xs text-warn">This spot keeps switching cells; speed will be unstable even if the bars look good (plan 7.5).</p>
          ) : null}
        </div>
      </div>
      <div className="mt-4">
        <TimeChart series={series} ariaLabel="Score over the last 3 minutes" format={fmtScore} yRange={[0, 100]} height={140} xWindow={[now - LIVE_WINDOW_MS, now]} />
      </div>
    </Card>
  );
}

function statLine(m: MetricStats | null | undefined, unit: string) {
  if (!m) return DASH;
  return `${trimNumber(m.avg, 1)} ${unit} (${trimNumber(m.min, 0)} to ${trimNumber(m.max, 0)}, ±${trimNumber(m.std, 1)})`;
}

function SpotCard({ spot, rank }: { spot: FinderSpot; rank: number }) {
  const canControl = useCanControl();
  const run = useRunCommand();
  const [busy, setBusy] = useState<string | null>(null);
  const grade = gradeOf('score', spot.metrics.score.avg);
  const best = spotBestDown(spot);
  const peakTested = spot.speed.some((s) => s.peak_hour);
  const act = async (key: string, fn: () => Promise<unknown>) => {
    setBusy(key);
    try {
      await fn();
    } finally {
      setBusy(null);
    }
  };

  return (
    <li className={cx('rounded-2xl border bg-surface p-4', spot.baseline ? 'border-accent' : 'border-border')}>
      <div className="flex flex-wrap items-start gap-3">
        <span className="tabular flex size-10 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-lg font-bold text-fg" aria-label={`Rank ${rank}`}>
          {rank}
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="flex flex-wrap items-center gap-2 text-base font-semibold text-fg">
            <span className="break-anywhere">{spot.name}</span>
            {spot.baseline ? (
              <Badge tone="info">
                <IconStar /> Baseline
              </Badge>
            ) : null}
            {spot.cell_switches > 0 ? <Badge tone="warn">{spot.cell_switches} cell switches</Badge> : null}
            {peakTested ? <Badge tone="ok">Peak-hour tested</Badge> : spot.speed.length ? <Badge>Not tested at peak</Badge> : null}
          </h3>
          <p className="mt-0.5 text-xs text-muted">
            {formatDateTime(spot.created_at)} · {spot.orientation ?? 'orientation not set'} · {formatNetType(spot.serving.net_type)}
            {spot.serving.band != null ? ` B${spot.serving.band}` : ''} · PCI {spot.serving.pci ?? DASH}
            {spot.compass_deg != null ? ` · facing ${Math.round(spot.compass_deg)}°` : ''}
          </p>
        </div>
        <div className="text-right">
          <p className={cx('tabular text-3xl font-bold leading-none', gradeTextClass(grade))}>{Math.round(spot.metrics.score.avg)}</p>
          <GradeChip grade={grade} className="mt-1" />
        </div>
      </div>

      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <KeyValues
          items={[
            ['Score', statLine(spot.metrics.score, '')],
            ['RSRP', statLine(spot.metrics.rsrp, 'dBm')],
            ['SINR', statLine(spot.metrics.sinr, 'dB')],
            ['RSRQ', statLine(spot.metrics.rsrq, 'dB')],
          ]}
        />
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-muted">Best download</p>
          <p className="tabular text-2xl font-semibold text-fg">{formatMbps(best)}</p>
          {spot.speed.length ? (
            <ul className="mt-1 space-y-1 text-xs text-muted">
              {spot.speed.map((s) => (
                <li key={s.at}>
                  {formatDateTime(s.at)}
                  {s.peak_hour ? ' (peak)' : ''}: ↓ {formatMbps(s.down_mbps)} · ↑ {formatMbps(s.up_mbps)} · ping {s.ping_ms != null ? `${Math.round(s.ping_ms)} ms` : DASH} · jitter{' '}
                  {s.jitter_ms != null ? `${trimNumber(s.jitter_ms, 1)} ms` : DASH} · {s.runs.length} runs
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-muted">No speed test yet.</p>
          )}
        </div>
      </div>

      {canControl ? (
        <div className="mt-3 flex flex-wrap gap-2 border-t border-border pt-3">
          <Button size="sm" busy={busy === 'speed'} onClick={() => act('speed', () => run('speed_test', { spot_id: spot.id }))}>
            Speed test here
          </Button>
          {!spot.baseline ? (
            <Button size="sm" busy={busy === 'base'} onClick={() => act('base', () => run('finder_set_baseline', { spot_id: spot.id }, { detail: `Spot: ${spot.name}` }))}>
              Set as baseline
            </Button>
          ) : null}
          <Button size="sm" variant="danger" busy={busy === 'del'} onClick={() => act('del', () => run('finder_delete_spot', { spot_id: spot.id }, { detail: `Spot: ${spot.name}` }))}>
            Delete
          </Button>
        </div>
      ) : null}
    </li>
  );
}

export function FinderScreen() {
  useDocumentTitle('Signal Finder');
  const setFinder = useConn((s) => s.setFinder);
  const canControl = useCanControl();
  const spots = useSpots();
  const ranked = useMemo(() => rankSpots(spots), [spots]);
  const snap = useSnapshot();

  // Ask for the 1 s mirror only while this screen is mounted (protocol 5.2).
  useEffect(() => {
    setFinder(true);
    return () => setFinder(false);
  }, [setFinder]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="Signal Finder"
        subtitle="Walk with the hotspot phone and read the score here (plan 7). Speed first, score second decides the ranking."
        actions={
          spots.length ? (
            <Button
              size="sm"
              onClick={() => downloadText(`spots-${(snap?.name ?? 'phone').replace(/[^\w-]+/g, '_')}-${new Date().toISOString().slice(0, 10)}.csv`, spotsToCsv(spots))}
            >
              <IconDownload /> Export CSV
            </Button>
          ) : null
        }
      />
      <LiveMirror />

      {canControl ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <CommandCard name="finder_spot_test" />
          <CommandCard name="speed_test" />
        </div>
      ) : null}

      <section aria-labelledby="spots-title" className="space-y-3">
        <h2 id="spots-title" className="text-lg font-semibold text-fg">
          Saved spots ({spots.length})
        </h2>
        {ranked.length ? (
          <ol className="space-y-3">
            {ranked.map((s, i) => (
              <SpotCard key={s.id} spot={s} rank={i + 1} />
            ))}
          </ol>
        ) : (
          <Empty>No spots saved yet. Run a spot test at each candidate place, then speed test the best three (plan 7.4).</Empty>
        )}
        <p className="text-xs text-muted">
          Survey tips: test two or three orientations per spot, repeat speed tests at peak time (8 to 10 pm), and prefer a spot that stays on one
          cell.
        </p>
      </section>
    </div>
  );
}
