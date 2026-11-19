import { Link } from 'react-router-dom';
import { PendingReverts } from '../components/commands/PendingReverts';
import { EventList, UpdatedAt, useStaleness } from '../components/live';
import { PowerWarning } from '../components/PowerWarning';
import { Badge, Card, GradeBar, GradeChip, KeyValues, Notice, PageHeader, Progress, Stat, cx } from '../components/ui/primitives';
import {
  DASH,
  formatBps,
  formatBytes,
  formatCurrent,
  formatDuration,
  formatNetType,
  formatPercent,
  formatRelative,
  formatTemp,
  formatThermalStatus,
  trimNumber,
} from '../lib/format';
import { gradeOf, gradeTextClass } from '../lib/grades';
import { useDocumentTitle, useNow } from '../lib/hooks';
import { useDeviceEvents, useDeviceSettings, useSnapshot } from '../store/selectors';

export function OverviewScreen() {
  useDocumentTitle('Overview');
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const events = useDeviceEvents();
  const now = useNow(5000);
  const radio = snap?.state.radio;
  const net = snap?.state.net;
  const power = snap?.state.power;
  const heat = snap?.state.heat;
  const traffic = snap?.state.traffic;
  const system = snap?.state.system;
  const fastStale = useStaleness('radio', 'net');
  const slowStale = useStaleness('power', 'heat');

  const score = radio?.score ?? null;
  const scoreGrade = gradeOf('score', score);
  const quotaBytes = settings.plan.quota_gb > 0 ? settings.plan.quota_gb * 1e9 : 0;
  const cycleUsed = traffic?.cycle_rx != null || traffic?.cycle_tx != null ? (traffic?.cycle_rx ?? 0) + (traffic?.cycle_tx ?? 0) : null;
  const todayUsed = traffic?.today_rx != null || traffic?.today_tx != null ? (traffic?.today_rx ?? 0) + (traffic?.today_tx ?? 0) : null;
  const usedPct = quotaBytes && cycleUsed != null ? (cycleUsed / quotaBytes) * 100 : null;

  const tempTone =
    heat?.battery_c == null
      ? 'text-fg'
      : heat.battery_c >= settings.watchdog.temp_stop_charge_c
        ? 'text-danger'
        : heat.battery_c >= settings.watchdog.temp_warn_c
          ? 'text-warn'
          : 'text-fg';
  const lowBattery = power?.percent != null && power.percent <= settings.alerts.low_battery_pct;

  return (
    <div className="space-y-4">
      <PageHeader title="Overview" subtitle={snap?.info ? `${snap.info.manufacturer} ${snap.info.model} · Android ${snap.info.android_version}` : undefined} />
      <PendingReverts />
      <PowerWarning power={power} />
      {net && !net.online ? (
        <Notice tone="danger" title="Internet down on the phone">
          {net.internet_down_since ? `Since ${formatRelative(net.internet_down_since, now)}. ` : ''}The watchdog recovers it by itself (data
          cycle, airplane cycle, then reboot).
        </Notice>
      ) : null}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <Card title="Status">
          <div className="space-y-3">
            <div className="flex items-center gap-3">
              <span
                className={cx('inline-block size-4 rounded-full', snap?.online ? 'bg-ok' : snap ? 'bg-danger' : 'bg-faint')}
                aria-hidden
              />
              <p className={cx('text-3xl font-semibold', snap?.online ? 'text-ok' : snap ? 'text-danger' : 'text-muted')}>
                {snap ? (snap.online ? 'Online' : 'Offline') : 'Unknown'}
              </p>
            </div>
            <KeyValues
              items={[
                ['Last seen', snap?.last_seen ? formatRelative(snap.last_seen, now) : DASH],
                ['Internet', net ? (net.online ? <Badge tone="ok">Working</Badge> : <Badge tone="danger">Down</Badge>) : DASH],
                ['Network', radio ? `${formatNetType(radio.net_type)}${radio.band ? ` · B${radio.band}` : ''}` : DASH],
                ['Operator', radio?.operator ?? DASH],
                ['Outbox', snap?.outbox != null ? `${snap.outbox} waiting` : DASH],
              ]}
            />
          </div>
        </Card>

        <Card title="Signal score" stale={fastStale} actions={<Link to="/signal" className="text-xs font-medium text-accent hover:underline">Details</Link>}>
          <div className="flex items-end justify-between gap-3">
            <p className={cx('tabular text-6xl font-bold leading-none', gradeTextClass(scoreGrade))}>
              {score == null ? DASH : Math.round(score)}
              <span className="ml-1 text-lg font-medium text-muted">/ 100</span>
            </p>
            <GradeChip grade={scoreGrade} className="mb-1 text-sm" />
          </div>
          <div className="mt-3">
            <GradeBar grade={scoreGrade} value={score} />
          </div>
          <p className="mt-2 text-sm text-muted">
            RSRP {radio?.rsrp ?? DASH} dBm · SINR {radio?.sinr != null ? trimNumber(radio.sinr, 1) : DASH} dB · RSRQ {radio?.rsrq ?? DASH} dB
          </p>
          <div className="mt-1">
            <UpdatedAt ts={snap?.state_ts.radio} />
          </div>
        </Card>

        <Card title="Speed now" stale={fastStale} actions={<Link to="/network" className="text-xs font-medium text-accent hover:underline">Network</Link>}>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Download" value={formatBps(net?.rx_bps)} size="lg" />
            <Stat label="Upload" value={formatBps(net?.tx_bps)} size="lg" />
          </div>
          <p className="mt-3 text-sm text-muted">
            Ping {net?.ping_ms != null ? `${Math.round(net.ping_ms)} ms` : DASH} · loss {formatPercent(net?.ping_loss_pct, 1)} · jitter{' '}
            {net?.jitter_ms != null ? `${trimNumber(net.jitter_ms, 1)} ms` : DASH}
          </p>
        </Card>

        <Card title="Battery" stale={slowStale} actions={<Link to="/power" className="text-xs font-medium text-accent hover:underline">Power</Link>}>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Charge" value={formatPercent(power?.percent)} size="lg" valueClass={lowBattery ? 'text-danger' : undefined} />
            <Stat
              label="Net current"
              value={formatCurrent(power?.current_ma)}
              size="lg"
              valueClass={power?.current_ma != null && power.current_ma < 0 ? 'text-warn' : power?.current_ma ? 'text-ok' : undefined}
            />
          </div>
          <p className="mt-3 text-sm text-muted">
            {power?.status ? power.status.replace('_', ' ') : DASH} · plug {power?.plug ?? DASH}
            {power?.charging_enabled === false ? ` · charging paused (${power.stop_reason ?? 'manual'})` : ''}
            {power?.charge_limit_pct != null ? ` · limit ${power.charge_limit_pct}%` : ''}
          </p>
        </Card>

        <Card title="Temperature" stale={slowStale}>
          <Stat label="Battery" value={formatTemp(heat?.battery_c)} size="lg" valueClass={tempTone} />
          <p className="mt-3 text-sm text-muted">
            CPU {formatTemp(heat?.cpu_c)} · modem {formatTemp(heat?.modem_c)} · thermal {formatThermalStatus(heat?.thermal_status)}
          </p>
          {heat?.radio_resting ? (
            <p className="mt-2">
              <Badge tone="danger">Radio resting (heat)</Badge>
            </p>
          ) : null}
        </Card>

        <Card title="Data this cycle" stale={useStaleness('traffic')}>
          <Stat
            label={quotaBytes ? `of ${formatBytes(quotaBytes)} plan` : 'Used (no quota set)'}
            value={formatBytes(cycleUsed)}
            size="lg"
            sub={usedPct != null ? `${trimNumber(usedPct, 1)}% used` : undefined}
          />
          {usedPct != null ? (
            <div className="mt-3">
              <Progress value={usedPct} label="Data used this cycle" tone={usedPct >= 100 ? 'danger' : usedPct >= 80 ? 'warn' : 'info'} />
            </div>
          ) : null}
          <p className="mt-3 text-sm text-muted">Today {formatBytes(todayUsed)}{traffic?.cycle_start ? ` · cycle started ${traffic.cycle_start}` : ''}</p>
        </Card>

        <Card title="Uptime">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Phone" value={formatDuration(system?.uptime_s)} />
            <Stat label="Agent" value={formatDuration(system?.agent_uptime_s)} />
          </div>
          <p className="mt-3 text-sm text-muted">
            Boot #{system?.boot_count ?? DASH} · {system?.boot_reason ?? 'reason unknown'}
          </p>
        </Card>

        <Card
          title="Latest events"
          className="sm:col-span-2 xl:col-span-2"
          actions={<Link to="/system?tab=events" className="text-xs font-medium text-accent hover:underline">All events</Link>}
        >
          <EventList events={events.slice(0, 8)} />
        </Card>
      </div>
    </div>
  );
}
