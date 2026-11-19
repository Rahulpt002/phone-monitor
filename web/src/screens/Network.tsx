import { MetricChart, RangePicker } from '../components/charts/MetricChart';
import { useRange } from '../components/charts/useHistory';
import { OnOff, UpdatedAt, YesNo, useStaleness } from '../components/live';
import { Badge, Card, Empty, KeyValues, Notice, PageHeader, Progress, Stat, TableWrap, cx, td, th } from '../components/ui/primitives';
import { DASH, formatBps, formatBytes, formatDate, formatPercent, trimNumber } from '../lib/format';
import { useDocumentTitle } from '../lib/hooks';
import { useDeviceSettings, useSnapshot } from '../store/selectors';

const fmtBps = (v: number) => formatBps(v);
const fmtMs = (v: number) => `${trimNumber(v, 0)} ms`;
const fmtPct = (v: number) => `${trimNumber(v, 1)}%`;

export function NetworkScreen() {
  useDocumentTitle('Network');
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const [range, setRange] = useRange();
  const tether = snap?.state.tether;
  const net = snap?.state.net;
  const traffic = snap?.state.traffic;
  const slowStale = useStaleness('tether');
  const fastStale = useStaleness('net');
  const trafficStale = useStaleness('traffic');

  const quota = settings.plan.quota_gb > 0 ? settings.plan.quota_gb * 1e9 : 0;
  const cycleUsed = traffic?.cycle_rx != null || traffic?.cycle_tx != null ? (traffic?.cycle_rx ?? 0) + (traffic?.cycle_tx ?? 0) : null;
  const pct = quota && cycleUsed != null ? (cycleUsed / quota) * 100 : null;
  const clients = tether?.clients ?? [];
  const tetherProblem = tether?.usb_connected && tether.usb_tether_on === false;

  return (
    <div className="space-y-4">
      <PageHeader title="Network" subtitle="Tethering, hotspot, speed, latency and data usage." />

      {tetherProblem ? (
        <Notice tone="danger" title="USB connected but tethering is off">
          {settings.watchdog.tether_fix
            ? `The watchdog turns it back on after ${settings.watchdog.tether_fix_after_s} s.`
            : 'The watchdog tethering fix is turned off in Settings.'}
        </Notice>
      ) : null}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <Card title="USB tethering" stale={slowStale} actions={<UpdatedAt ts={snap?.state_ts.tether} />}>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            {tether?.usb_tether_on ? <Badge tone="ok">Tethering on</Badge> : tether?.usb_tether_on === false ? <Badge tone="danger">Tethering off</Badge> : <Badge>Unknown</Badge>}
            {tether?.usb_connected ? <Badge tone="info">USB connected</Badge> : tether?.usb_connected === false ? <Badge tone="warn">USB not connected</Badge> : null}
          </div>
          <KeyValues
            items={[
              ['USB function', tether?.usb_function ? tether.usb_function.toUpperCase() : DASH],
              ['Interface', tether?.tether_if ?? DASH],
              ['Gateway IP', tether?.tether_ip ?? DASH],
            ]}
          />
        </Card>

        <Card title="Wi-Fi hotspot" stale={slowStale}>
          <KeyValues
            items={[
              ['Hotspot', <OnOff key="h" value={tether?.hotspot_on} />],
              ['Name (SSID)', tether?.hotspot_ssid ?? DASH],
              ['Clients', String(clients.length)],
            ]}
          />
        </Card>

        <Card title="Internet" stale={fastStale} actions={<UpdatedAt ts={snap?.state_ts.net} />}>
          <KeyValues
            items={[
              ['Online check', net ? (net.online ? <Badge tone="ok">OK</Badge> : <Badge tone="danger">Failing</Badge>) : DASH],
              ['DNS', <YesNo key="dns" value={net?.dns_ok} yes="OK" no="Failing" />],
              ['HTTP check', net?.http_ms != null ? `${Math.round(net.http_ms)} ms` : DASH],
              ['Public IPv4', <span key="v4" className="font-mono text-xs">{net?.public_ipv4 ?? DASH}</span>],
              ['Public IPv6', <span key="v6" className="font-mono text-xs break-anywhere">{net?.public_ipv6 ?? DASH}</span>],
              ['Mobile interface', net?.active_if ?? DASH],
            ]}
          />
        </Card>
      </div>

      <Card title="Connected clients" stale={slowStale}>
        {clients.length ? (
          <TableWrap label="Connected clients">
            <table className="w-full min-w-[26rem]">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>IP</th>
                  <th className={th}>Name</th>
                  <th className={th}>MAC</th>
                  <th className={th}>Interface</th>
                </tr>
              </thead>
              <tbody>
                {clients.map((c) => (
                  <tr key={`${c.ip}-${c.mac ?? ''}`} className="border-b border-border/60 last:border-0">
                    <td className={cx(td, 'font-mono')}>{c.ip}</td>
                    <td className={td}>{c.hostname ?? DASH}</td>
                    <td className={cx(td, 'font-mono')}>{c.mac ?? DASH}</td>
                    <td className={td}>{c.iface ?? DASH}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        ) : (
          <Empty>No clients seen on the tether or hotspot interfaces.</Empty>
        )}
      </Card>

      <Card title="Data usage" stale={trafficStale}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Stat label="Today" value={formatBytes(traffic?.today_rx != null || traffic?.today_tx != null ? (traffic?.today_rx ?? 0) + (traffic?.today_tx ?? 0) : null)} sub={`↓ ${formatBytes(traffic?.today_rx)} · ↑ ${formatBytes(traffic?.today_tx)}`} />
          <Stat
            label={`This cycle${traffic?.cycle_start ? ` (since ${formatDate(traffic.cycle_start)})` : ''}`}
            value={formatBytes(cycleUsed)}
            sub={`↓ ${formatBytes(traffic?.cycle_rx)} · ↑ ${formatBytes(traffic?.cycle_tx)}`}
          />
          <Stat
            label="Plan quota"
            value={quota ? formatBytes(quota) : 'Unlimited'}
            sub={pct != null ? `${formatPercent(pct, 1)} used · ${formatBytes(Math.max(0, quota - (cycleUsed ?? 0)))} left` : 'Set a quota in Settings > Data plan'}
          />
        </div>
        {pct != null ? (
          <div className="mt-4 space-y-1">
            <Progress value={pct} label="Data used this billing cycle" tone={pct >= 100 ? 'danger' : pct >= 80 ? 'warn' : 'info'} />
            <p className="text-xs text-muted">Alerts at {settings.alerts.quota_steps_pct.join('%, ')}% of the plan.</p>
          </div>
        ) : null}
      </Card>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold text-fg">History</h2>
        <RangePicker value={range} onChange={setRange} />
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <MetricChart title="Speed" metrics={['net.rx_bps', 'net.tx_bps']} labels={['Download', 'Upload']} range={range} format={fmtBps} yRange={[0, null]} />
        <MetricChart title="Ping and jitter (ms)" metrics={['net.ping_ms', 'net.jitter_ms']} labels={['Ping', 'Jitter']} range={range} format={fmtMs} yRange={[0, null]} />
        <MetricChart title="Packet loss (%)" metrics={['net.ping_loss_pct']} range={range} format={fmtPct} yRange={[0, 100]} />
        <MetricChart title="Data used this cycle" metrics={['traffic.cycle_rx', 'traffic.cycle_tx']} labels={['Downloaded', 'Uploaded']} range={range} format={formatBytesAxis} yRange={[0, null]} />
      </div>
    </div>
  );
}

const formatBytesAxis = (v: number) => formatBytes(v);
