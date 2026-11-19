import type { FinderSpot, RadioInfo, StoredEvent } from '@hsm/shared';
import { useMemo } from 'react';
import { MetricChart, RangePicker } from '../components/charts/MetricChart';
import { useRange } from '../components/charts/useHistory';
import { SignalMetric, UpdatedAt, YesNo, useStaleness } from '../components/live';
import {
  Badge,
  Card,
  Empty,
  GradeChip,
  KeyValues,
  Notice,
  PageHeader,
  TableWrap,
  cx,
  td,
  th,
} from '../components/ui/primitives';
import { api } from '../lib/api';
import { DASH, fmtNum, formatDateTime, formatNetType, formatRelative, formatTaDistance, trimNumber } from '../lib/format';
import { GRADE_LABEL, GRADES, SCORE_LINES, THRESHOLD_TABLE, gradeOf, gradeTextClass } from '../lib/grades';
import { useAsync, useDocumentTitle, useNow } from '../lib/hooks';
import { useConn } from '../store/conn';
import { useDeviceEvents, useDeviceId, useDeviceSettings, useSnapshot } from '../store/selectors';

const fmtScore = (v: number) => trimNumber(v, 0);
const fmtDbm = (v: number) => `${trimNumber(v, 0)} dBm`;
const fmtDb = (v: number) => `${trimNumber(v, 1)} dB`;

function ServingCell({ radio }: { radio: RadioInfo | undefined }) {
  return (
    <KeyValues
      items={[
        ['Network type', formatNetType(radio?.net_type)],
        ['Operator', radio?.operator ? `${radio.operator}${radio.mcc_mnc ? ` (${radio.mcc_mnc})` : ''}` : DASH],
        ['Band', radio?.band != null ? (radio.net_type === 'NR_SA' ? `n${radio.band}` : `B${radio.band}`) : DASH],
        ['Channel (EARFCN)', radio?.earfcn ?? DASH],
        ['PCI', radio?.pci ?? DASH],
        ['Cell ID', radio?.cell_id != null ? `${radio.cell_id} (eNB ${Math.floor(radio.cell_id / 256)})` : DASH],
        ['TAC', radio?.tac ?? DASH],
        ['Timing advance', radio?.ta != null ? `${radio.ta} (${formatTaDistance(radio.ta)})` : DASH],
        ['RSSI', fmtNum(radio?.rssi, ' dBm', 0)],
        ['CQI', radio?.cqi ?? DASH],
        ['Bars', radio?.signal_level != null ? `${radio.signal_level} / 4` : DASH],
        ['SIM', radio?.sim_state ?? DASH],
        ['Roaming', <YesNo key="r" value={radio?.roaming} />],
        ['Mobile data', <YesNo key="d" value={radio?.data_enabled} yes="On" no="Off" />],
        ['Airplane mode', <YesNo key="a" value={radio?.airplane} yes="On" no="Off" />],
      ]}
    />
  );
}

function NrLeg({ radio }: { radio: RadioInfo }) {
  const nr = radio.nr;
  if (!nr) return null;
  return (
    <Card title={radio.net_type === 'NR_SA' ? '5G standalone cell' : '5G leg (NR, non-standalone)'}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SignalMetric label="SS-RSRP" metric="rsrp" value={nr.ss_rsrp} unit="dBm" barRange={[-120, -70]} />
        <SignalMetric label="SS-SINR" metric="sinr" value={nr.ss_sinr} unit="dB" barRange={[-5, 25]} />
        <SignalMetric label="SS-RSRQ" metric="rsrq" value={nr.ss_rsrq} unit="dB" barRange={[-20, -8]} />
      </div>
      <div className="mt-4">
        <KeyValues
          items={[
            ['NR band', nr.band != null ? `n${nr.band}` : DASH],
            ['NR-ARFCN', nr.arfcn ?? DASH],
            ['PCI', nr.pci ?? DASH],
            ['NCI', nr.nci ?? DASH],
          ]}
        />
      </div>
      {radio.net_type === 'NR_NSA' ? (
        <p className="mt-3 text-xs text-muted">The score uses the LTE anchor cell; the 5G leg carries the extra capacity.</p>
      ) : null}
    </Card>
  );
}

function Neighbours({ radio }: { radio: RadioInfo | undefined }) {
  const list = radio?.neighbours ?? [];
  if (!list.length) return <Empty>No neighbour cells reported.</Empty>;
  const sorted = [...list].sort((a, b) => (b.rsrp ?? -999) - (a.rsrp ?? -999));
  return (
    <TableWrap label="Neighbour cells">
      <table className="w-full min-w-[22rem]">
        <thead>
          <tr className="border-b border-border">
            <th className={th}>Type</th>
            <th className={th}>PCI</th>
            <th className={th}>Channel</th>
            <th className={th}>RSRP</th>
            <th className={th}>RSRQ</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((n, i) => (
            <tr key={i} className="border-b border-border/60 last:border-0">
              <td className={td}>{formatNetType(n.type)}</td>
              <td className={cx(td, 'tabular')}>{n.pci ?? DASH}</td>
              <td className={cx(td, 'tabular')}>{n.earfcn ?? DASH}</td>
              <td className={cx(td, 'tabular font-medium', gradeTextClass(gradeOf('rsrp', n.rsrp)))}>{fmtNum(n.rsrp, ' dBm', 0)}</td>
              <td className={cx(td, 'tabular font-medium', gradeTextClass(gradeOf('rsrq', n.rsrq)))}>{fmtNum(n.rsrq, ' dB', 1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableWrap>
  );
}

function Thresholds() {
  return (
    <TableWrap label="Signal grade thresholds">
      <table className="w-full min-w-[30rem]">
        <thead>
          <tr className="border-b border-border">
            <th className={th}>Metric</th>
            {GRADES.map((g) => (
              <th key={g} className={cx(th, gradeTextClass(g))}>
                {GRADE_LABEL[g]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {(['rsrp', 'sinr', 'rsrq'] as const).map((m) => (
            <tr key={m} className="border-b border-border/60 last:border-0">
              <td className={td}>
                <span className="font-semibold">{THRESHOLD_TABLE[m].label}</span> <span className="text-muted">({THRESHOLD_TABLE[m].unit})</span>
                <p className="text-xs text-muted">{THRESHOLD_TABLE[m].meaning}</p>
              </td>
              {GRADES.map((g) => (
                <td key={g} className={cx(td, 'tabular')}>
                  {THRESHOLD_TABLE[m].bands[g]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </TableWrap>
  );
}

function BaselineComparison({ baseline, radio }: { baseline: FinderSpot | null | undefined; radio: RadioInfo | undefined }) {
  const { settings } = useDeviceSettings();
  if (!baseline) {
    return (
      <Empty>
        No baseline yet. After installation, set the winning spot as the baseline on the Signal Finder screen to compare live values against
        it.
      </Empty>
    );
  }
  const base = baseline.metrics;
  const pct = radio?.score != null && base.score.avg > 0 ? (radio.score / base.score.avg) * 100 : null;
  const dropped = pct != null && pct < settings.alerts.signal_drop_pct;
  const row = (label: string, live: number | null | undefined, avg: number | null | undefined, unit: string) => {
    const delta = live != null && avg != null ? live - avg : null;
    return [
      label,
      <span key={label} className="tabular">
        {live != null ? trimNumber(live, 1) : DASH} vs {avg != null ? trimNumber(avg, 1) : DASH} {unit}
        {delta != null ? (
          <span className={cx('ml-2 font-semibold', delta >= 0 ? 'text-ok' : 'text-warn')}>
            {delta >= 0 ? '+' : ''}
            {trimNumber(delta, 1)}
          </span>
        ) : null}
      </span>,
    ] as [string, React.ReactNode];
  };
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-muted">Live score vs baseline</p>
          <p className={cx('tabular text-4xl font-bold', dropped ? 'text-danger' : pct != null && pct >= 90 ? 'text-ok' : 'text-fg')}>
            {pct != null ? `${Math.round(pct)}%` : DASH}
          </p>
        </div>
        <div className="text-right text-sm text-muted">
          <p className="font-medium text-fg">{baseline.name}</p>
          <p>
            set {formatDateTime(baseline.created_at)} · PCI {baseline.serving.pci ?? DASH}
            {baseline.serving.band ? ` · B${baseline.serving.band}` : ''}
          </p>
        </div>
      </div>
      {dropped ? (
        <Notice tone="danger">
          Below {settings.alerts.signal_drop_pct}% of the installed baseline. Check for a cell change, a moved phone, or congestion.
        </Notice>
      ) : null}
      <KeyValues
        items={[
          row('Score', radio?.score, base.score.avg, ''),
          row('RSRP', radio?.rsrp, base.rsrp.avg, 'dBm'),
          row('SINR', radio?.sinr, base.sinr?.avg, 'dB'),
          row('RSRQ', radio?.rsrq, base.rsrq?.avg, 'dB'),
        ]}
      />
      {radio?.pci != null && baseline.serving.pci != null && radio.pci !== baseline.serving.pci ? (
        <p className="text-sm text-warn">
          Serving cell differs from the baseline (PCI {radio.pci} now, {baseline.serving.pci} at install).
        </p>
      ) : null}
    </div>
  );
}

interface CellRef {
  pci?: number | null;
  cell_id?: number | null;
  band?: number | null;
}

function cellLabel(c: CellRef | undefined): string {
  if (!c) return DASH;
  const parts = [c.pci != null ? `PCI ${c.pci}` : null, c.band != null ? `B${c.band}` : null, c.cell_id != null ? `cell ${c.cell_id}` : null];
  return parts.filter(Boolean).join(' · ') || DASH;
}

function CellTimeline() {
  const deviceId = useDeviceId();
  const epoch = useConn((s) => s.epoch);
  const now = useNow(10_000);
  const live = useDeviceEvents();
  const { data, loading, error } = useAsync(
    (signal) => (deviceId ? api.events({ device_id: deviceId, kind: 'cell_change', limit: 50 }, signal) : Promise.resolve([])),
    [deviceId, epoch],
  );
  const events = useMemo(() => {
    const map = new Map<string, StoredEvent>();
    for (const e of data ?? []) map.set(e.id, e);
    for (const e of live) if (e.kind === 'cell_change') map.set(e.id, e);
    return [...map.values()].sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts));
  }, [data, live]);

  if (loading && !events.length) return <p className="text-sm text-muted">Loading…</p>;
  if (error && !events.length) return <p className="text-sm text-danger">Could not load cell changes.</p>;
  if (!events.length) return <Empty>No cell changes recorded. A steady serving cell is good (plan 7.5).</Empty>;
  return (
    <ol className="relative space-y-3 border-l border-border pl-4">
      {events.map((e) => {
        const d = (e.data ?? {}) as { from?: CellRef; to?: CellRef };
        return (
          <li key={e.id} className="relative">
            <span className="absolute -left-[1.4rem] top-1.5 size-2.5 rounded-full bg-accent" aria-hidden />
            <p className="text-xs text-faint" title={formatDateTime(e.ts)}>
              {formatRelative(e.ts, now)} · {formatDateTime(e.ts)}
            </p>
            <p className="text-sm text-fg">
              {d.from || d.to ? (
                <>
                  {cellLabel(d.from)} <span className="text-muted">→</span> <span className="font-semibold">{cellLabel(d.to)}</span>
                </>
              ) : (
                e.message
              )}
            </p>
          </li>
        );
      })}
    </ol>
  );
}

export function SignalScreen() {
  useDocumentTitle('Signal');
  const snap = useSnapshot();
  const [range, setRange] = useRange();
  const radio = snap?.state.radio;
  const stale = useStaleness('radio');
  const baselineAvg = snap?.baseline?.metrics.score.avg;
  const scoreLines = useMemo(
    () => [...SCORE_LINES.map((v) => ({ value: v })), ...(baselineAvg != null ? [{ value: baselineAvg, colorVar: '--accent' }] : [])],
    [baselineAvg],
  );

  return (
    <div className="space-y-4">
      <PageHeader title="Signal" subtitle="Live radio readings, history and the installed baseline (plan 7.1)." />

      <Card title="Live" stale={stale} actions={<UpdatedAt ts={snap?.state_ts.radio} />}>
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
          <div className="min-w-0 space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <p className="text-xs font-medium uppercase tracking-wide text-muted">Score</p>
              <GradeChip grade={gradeOf('score', radio?.score)} />
            </div>
            <p className={cx('tabular text-5xl font-bold leading-none', gradeTextClass(gradeOf('score', radio?.score)))}>
              {radio?.score != null ? Math.round(radio.score) : DASH}
            </p>
            <p className="text-xs text-muted">
              {formatNetType(radio?.net_type)}
              {radio?.band != null ? ` · band ${radio.band}` : ''}
              {radio?.pci != null ? ` · PCI ${radio.pci}` : ''}
            </p>
          </div>
          <SignalMetric label="RSRP · strength" metric="rsrp" value={radio?.rsrp} unit="dBm" barRange={[-120, -70]} />
          <SignalMetric label="SINR · cleanliness" metric="sinr" value={radio?.sinr} unit="dB" barRange={[-5, 25]} />
          <SignalMetric label="RSRQ · quality" metric="rsrq" value={radio?.rsrq} unit="dB" barRange={[-20, -8]} />
        </div>
      </Card>

      {radio?.nr ? <NrLeg radio={radio} /> : null}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card title="Serving cell" stale={stale}>
          <ServingCell radio={radio} />
        </Card>
        <Card title="Baseline comparison">
          <BaselineComparison baseline={snap?.baseline} radio={radio} />
        </Card>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold text-fg">History</h2>
        <RangePicker value={range} onChange={setRange} />
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <MetricChart title="Signal score" metrics={['radio.score']} range={range} format={fmtScore} yRange={[0, 100]} thresholds={scoreLines} />
        <MetricChart
          title="RSRP (dBm)"
          metrics={['radio.rsrp', 'radio.nr.ss_rsrp']}
          labels={['RSRP (LTE / serving)', 'SS-RSRP (5G)']}
          range={range}
          format={fmtDbm}
          thresholds={THRESHOLD_LINES.rsrp}
        />
        <MetricChart
          title="SINR (dB)"
          metrics={['radio.sinr', 'radio.nr.ss_sinr']}
          labels={['SINR (LTE / serving)', 'SS-SINR (5G)']}
          range={range}
          format={fmtDb}
          thresholds={THRESHOLD_LINES.sinr}
        />
        <MetricChart title="RSRQ (dB)" metrics={['radio.rsrq']} range={range} format={fmtDb} thresholds={THRESHOLD_LINES.rsrq} />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card title="Cell changes">
          <CellTimeline />
        </Card>
        <Card title="Neighbour cells" stale={stale}>
          <Neighbours radio={radio} />
        </Card>
      </div>

      <Card title="Grades (plan 7.1)">
        <Thresholds />
        <p className="mt-3 text-xs text-muted">
          Strong bars alone do not mean fast internet: a clean signal (high SINR) from a less crowded cell often beats a strong signal from a
          busy one. Score = 45% SINR + 35% RSRP + 20% RSRQ (weights editable in Settings).
        </p>
        <p className="mt-2 flex flex-wrap gap-2">
          {GRADES.map((g) => (
            <Badge key={g} className={gradeTextClass(g)}>
              {GRADE_LABEL[g]}: score {g === 'excellent' ? '75+' : g === 'good' ? '55 to 75' : g === 'fair' ? '35 to 55' : 'below 35'}
            </Badge>
          ))}
        </p>
      </Card>
    </div>
  );
}

const THRESHOLD_LINES = {
  rsrp: THRESHOLD_TABLE.rsrp.lines.map((value) => ({ value })),
  sinr: THRESHOLD_TABLE.sinr.lines.map((value) => ({ value })),
  rsrq: THRESHOLD_TABLE.rsrq.lines.map((value) => ({ value })),
};
