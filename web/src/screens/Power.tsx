import { useMemo } from 'react';
import { MetricChart, RangePicker } from '../components/charts/MetricChart';
import { useRange } from '../components/charts/useHistory';
import { CommandCard } from '../components/controls/CommandCard';
import { UpdatedAt, YesNo, useStaleness } from '../components/live';
import { PowerWarning } from '../components/PowerWarning';
import { Badge, Card, KeyValues, PageHeader, Stat, TableWrap, cx, td, th } from '../components/ui/primitives';
import { DASH, formatCurrent, formatPercent, formatTemp, formatThermalStatus, formatVoltage, trimNumber } from '../lib/format';
import { useDocumentTitle } from '../lib/hooks';
import { useCanControl, useDeviceSettings, useSnapshot } from '../store/selectors';

const fmtPct = (v: number) => `${trimNumber(v, 0)}%`;
const fmtMa = (v: number) => `${trimNumber(v, 0)} mA`;
const fmtV = (v: number) => `${trimNumber(v, 2)} V`;
const fmtC = (v: number) => `${trimNumber(v, 1)} °C`;
const mvToV = (v: number) => v / 1000;

const STOP_REASON: Record<string, string> = {
  limit: 'charge limit reached',
  heat: 'battery too hot',
  manual: 'stopped manually',
};

function tempClass(v: number | null | undefined, warn: number, stop: number) {
  if (v == null) return undefined;
  return v >= stop ? 'text-danger' : v >= warn ? 'text-warn' : undefined;
}

export function PowerScreen() {
  useDocumentTitle('Power and heat');
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const canControl = useCanControl();
  const [range, setRange] = useRange();
  const power = snap?.state.power;
  const heat = snap?.state.heat;
  const powerStale = useStaleness('power');
  const heatStale = useStaleness('heat');
  const w = settings.watchdog;

  const percentLines = useMemo(() => [{ value: power?.charge_limit_pct ?? w.charge_limit_pct, colorVar: '--accent' }], [power?.charge_limit_pct, w.charge_limit_pct]);
  const tempLines = useMemo(
    () => [
      { value: w.temp_warn_c, colorVar: '--warn' },
      { value: w.temp_stop_charge_c, colorVar: '--danger' },
    ],
    [w.temp_warn_c, w.temp_stop_charge_c],
  );
  const zones = Object.entries(heat?.zones ?? {}).sort((a, b) => b[1] - a[1]);

  return (
    <div className="space-y-4">
      <PageHeader title="Power and heat" subtitle="Battery health is the biggest safety risk in a hot outdoor box (plan 4.2)." />
      <PowerWarning power={power} />
      {heat?.radio_resting ? (
        <p className="rounded-xl bg-danger-soft px-3 py-2 text-sm font-medium text-danger">
          The radio is resting to cool down (battery reached {w.temp_radio_rest_c} °C).
        </p>
      ) : null}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <Card title="Battery" stale={powerStale} actions={<UpdatedAt ts={snap?.state_ts.power} />}>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Charge" value={formatPercent(power?.percent)} size="lg" valueClass={power?.percent != null && power.percent <= settings.alerts.low_battery_pct ? 'text-danger' : undefined} />
            <Stat
              label="Net current"
              value={formatCurrent(power?.current_ma)}
              size="lg"
              valueClass={power?.current_ma != null ? (power.current_ma < 0 ? 'text-warn' : 'text-ok') : undefined}
              sub={power?.current_ma != null ? (power.current_ma < 0 ? 'draining' : power.current_ma > 0 ? 'charging' : 'idle') : undefined}
            />
          </div>
          <div className="mt-4">
            <KeyValues
              items={[
                ['Status', power?.status ? power.status.replace('_', ' ') : DASH],
                ['Voltage', formatVoltage(power?.voltage_mv)],
                ['Plugged into', power?.plug ?? DASH],
                ['Health', power?.health ? <Badge key="h" tone={power.health === 'good' ? 'ok' : 'danger'}>{power.health.replace('_', ' ')}</Badge> : DASH],
              ]}
            />
          </div>
        </Card>

        <Card title="Charge limit" stale={powerStale}>
          <KeyValues
            items={[
              ['Charging', <YesNo key="c" value={power?.charging_enabled} yes="Allowed" no="Stopped" />],
              ['Why stopped', power?.charging_enabled === false ? (STOP_REASON[power.stop_reason ?? ''] ?? power.stop_reason ?? 'unknown') : DASH],
              ['Limit in force', power?.charge_limit_pct != null ? `${power.charge_limit_pct}%` : DASH],
              ['Resumes at', `${w.charge_limit_pct - w.charge_resume_drop_pct}%`],
              ['Stops above', `${w.temp_stop_charge_c} °C`],
              ['Control', <span key="m" className="font-mono text-xs break-anywhere">{power?.charge_control ?? 'none found'}</span>],
            ]}
          />
          {!power?.charge_control && snap?.state.power ? (
            <p className="mt-3 text-xs text-warn">No charge-control file found for this phone. Add one to its device profile (Phase 0).</p>
          ) : null}
        </Card>

        <Card title="Temperatures" stale={heatStale} actions={<UpdatedAt ts={snap?.state_ts.heat} />}>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Battery" value={formatTemp(heat?.battery_c)} size="lg" valueClass={tempClass(heat?.battery_c, w.temp_warn_c, w.temp_stop_charge_c)} />
            <Stat label="CPU" value={formatTemp(heat?.cpu_c)} />
            <Stat label="Modem" value={formatTemp(heat?.modem_c)} />
            <Stat label="Skin" value={formatTemp(heat?.skin_c)} />
          </div>
          <p className="mt-3 text-sm text-muted">
            Thermal status: <span className={cx('font-semibold', (heat?.thermal_status ?? 0) >= 3 ? 'text-danger' : (heat?.thermal_status ?? 0) >= 2 ? 'text-warn' : 'text-fg')}>{formatThermalStatus(heat?.thermal_status)}</span>
          </p>
          <p className="mt-1 text-xs text-muted">
            Warning at {w.temp_warn_c} °C, stop charging at {w.temp_stop_charge_c} °C, rest radio at {w.temp_radio_rest_c} °C.
          </p>
        </Card>
      </div>

      {canControl ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <CommandCard name="charge_limit" />
          <CommandCard name="charging" />
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold text-fg">History</h2>
        <RangePicker value={range} onChange={setRange} />
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <MetricChart title="Battery (%)" metrics={['power.percent']} range={range} format={fmtPct} yRange={[0, 100]} thresholds={percentLines} />
        <MetricChart title="Net battery current (mA)" metrics={['power.current_ma']} range={range} format={fmtMa} thresholds={ZERO_LINE} />
        <MetricChart title="Battery voltage (V)" metrics={['power.voltage_mv']} range={range} format={fmtV} transform={mvToV} />
        <MetricChart
          title="Temperatures (°C)"
          metrics={['heat.battery_c', 'heat.cpu_c', 'heat.modem_c', 'heat.skin_c']}
          labels={['Battery', 'CPU', 'Modem', 'Skin']}
          range={range}
          format={fmtC}
          thresholds={tempLines}
        />
      </div>

      {zones.length ? (
        <Card title="Thermal zones">
          <TableWrap label="Thermal zones">
            <table className="w-full min-w-[16rem]">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>Zone</th>
                  <th className={th}>Temperature</th>
                </tr>
              </thead>
              <tbody>
                {zones.map(([name, c]) => (
                  <tr key={name} className="border-b border-border/60 last:border-0">
                    <td className={cx(td, 'font-mono text-xs')}>{name}</td>
                    <td className={cx(td, 'tabular')}>{formatTemp(c)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        </Card>
      ) : null}
    </div>
  );
}

const ZERO_LINE = [{ value: 0 }];
