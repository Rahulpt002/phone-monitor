import { COMMANDS, COMMAND_NAMES, type CommandRecord, type CommandStatus, type EventKind, type StoredEvent } from '@hsm/shared';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRunCommand } from '../components/commands/CommandRunner';
import { LastResult, StatusBadge } from '../components/commands/CommandStatus';
import { CommandCard } from '../components/controls/CommandCard';
import { EventRow, YesNo, useStaleness } from '../components/live';
import { Field, Select, TextInput } from '../components/ui/forms';
import { IconTerminal } from '../components/ui/icons';
import { Badge, Button, Card, Empty, KeyValues, Notice, PageHeader, Progress, TableWrap, cx, td, th } from '../components/ui/primitives';
import { Tabs } from '../components/ui/Tabs';
import { usePagedList } from '../components/usePagedList';
import { api } from '../lib/api';
import { applyAppChanges, parseAppList } from '../lib/apps';
import { STATUS_META, describeCommand, hasResult } from '../lib/commandMeta';
import { DASH, formatDateTime, formatDuration, formatMb, formatRelative, titleCase, trimNumber } from '../lib/format';
import { useDocumentTitle, useNow } from '../lib/hooks';
import { useCanControl, useCommand, useDeviceCommands, useDeviceEvents, useDeviceId, useDeviceSettings, useIsAdmin, useLastCommands, useSnapshot } from '../store/selectors';

type TabId = 'info' | 'processes' | 'apps' | 'audit' | 'events' | 'console';

export function SystemScreen() {
  useDocumentTitle('System');
  const isAdmin = useIsAdmin();
  const [params, setParams] = useSearchParams();
  const tabs = [
    { id: 'info' as const, label: 'System' },
    { id: 'processes' as const, label: 'Processes' },
    { id: 'apps' as const, label: 'Apps' },
    { id: 'audit' as const, label: 'Command log' },
    { id: 'events' as const, label: 'Events' },
    ...(isAdmin ? [{ id: 'console' as const, label: 'Root console' }] : []),
  ];
  const requested = params.get('tab') as TabId | null;
  const active: TabId = tabs.some((t) => t.id === requested) ? requested! : 'info';

  return (
    <div>
      <PageHeader title="System" subtitle="Health, processes, apps, the command audit log, events and the root console." />
      <Tabs label="System sections" tabs={tabs} active={active} onChange={(id) => setParams({ tab: id }, { replace: true })}>
        {active === 'info' ? <InfoTab /> : null}
        {active === 'processes' ? <ProcessesTab /> : null}
        {active === 'apps' ? <AppsTab /> : null}
        {active === 'audit' ? <AuditTab /> : null}
        {active === 'events' ? <EventsTab /> : null}
        {active === 'console' && isAdmin ? <ConsoleTab /> : null}
      </Tabs>
    </div>
  );
}

// --- info -------------------------------------------------------------------------------------

function InfoTab() {
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const canControl = useCanControl();
  const sys = snap?.state.system;
  const info = snap?.info;
  const pos = snap?.state.position;
  const caps = snap?.state.capabilities;
  const stale = useStaleness('system');
  const ramUsed = sys?.ram_total_mb != null && sys.ram_avail_mb != null ? sys.ram_total_mb - sys.ram_avail_mb : null;
  const diskUsed = sys?.storage_total_mb != null && sys.storage_free_mb != null ? sys.storage_total_mb - sys.storage_free_mb : null;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <Card title="Uptime and boots" stale={stale}>
          <KeyValues
            items={[
              ['Phone uptime', formatDuration(sys?.uptime_s)],
              ['Agent uptime', formatDuration(sys?.agent_uptime_s)],
              ['Boot count', sys?.boot_count ?? DASH],
              ['Last boot', sys?.last_boot_at ? formatDateTime(sys.last_boot_at) : DASH],
              ['Boot reason', <span key="br" className="font-mono text-xs">{sys?.boot_reason ?? DASH}</span>],
              ['Reboots in window', sys?.reboots_in_window != null ? `${sys.reboots_in_window} of ${settings.watchdog.max_reboots} (${settings.watchdog.reboot_window_h} h)` : DASH],
              ['Screen', <YesNo key="s" value={sys?.screen_on} yes="On" no="Off" />],
            ]}
          />
        </Card>
        <Card title="Load and memory" stale={stale}>
          <KeyValues
            items={[
              ['CPU load (1/5/15 min)', sys?.cpu_load ? sys.cpu_load.map((v) => trimNumber(v, 2)).join(' / ') : DASH],
              ['RAM free', sys?.ram_avail_mb != null ? `${formatMb(sys.ram_avail_mb)} of ${formatMb(sys.ram_total_mb)}` : DASH],
              ['Storage free', sys?.storage_free_mb != null ? `${formatMb(sys.storage_free_mb)} of ${formatMb(sys.storage_total_mb)}` : DASH],
            ]}
          />
          <div className="mt-3 space-y-2">
            {ramUsed != null && sys?.ram_total_mb ? <Progress value={ramUsed} max={sys.ram_total_mb} label="RAM used" tone={ramUsed / sys.ram_total_mb > 0.9 ? 'warn' : 'info'} /> : null}
            {diskUsed != null && sys?.storage_total_mb ? (
              <Progress value={diskUsed} max={sys.storage_total_mb} label="Storage used" tone={diskUsed / sys.storage_total_mb > 0.9 ? 'warn' : 'info'} />
            ) : null}
          </div>
        </Card>
        <Card title="Software">
          <KeyValues
            items={[
              ['Phone', info ? `${info.manufacturer} ${info.model} (${info.device})` : DASH],
              ['Android', info ? `${info.android_version} (SDK ${info.sdk})` : (sys?.android_version ?? DASH)],
              ['Build', <span key="b" className="font-mono text-xs">{info?.build_id ?? DASH}</span>],
              ['Agent', info ? `${info.agent_version}${info.agent_version_code != null ? ` (${info.agent_version_code})` : ''}` : (sys?.agent_version ?? DASH)],
              ['Device profile', info?.profile_id ?? DASH],
              ['Root', info ? (info.rooted ? (info.root_impl ?? 'yes') : 'not rooted') : DASH],
              ['IMEI (last 4)', info?.imei_tail ?? DASH],
            ]}
          />
        </Card>
        <Card title="Position" stale={useStaleness('position')}>
          <KeyValues
            items={[
              ['Location', pos?.lat != null && pos.lon != null ? `${trimNumber(pos.lat, 5)}, ${trimNumber(pos.lon, 5)}` : DASH],
              ['Accuracy', pos?.acc_m != null ? `±${Math.round(pos.acc_m)} m` : DASH],
              ['Fix', pos?.fix_at ? formatRelative(pos.fix_at) : DASH],
              ['Pitch / roll', pos?.pitch_deg != null ? `${trimNumber(pos.pitch_deg, 1)}° / ${trimNumber(pos.roll_deg ?? 0, 1)}°` : DASH],
              ['Compass', pos?.azimuth_deg != null ? `${Math.round(pos.azimuth_deg)}°` : DASH],
              ['Tilt change', pos?.tilt_change_deg != null ? `${trimNumber(pos.tilt_change_deg, 1)}° (alert at ${settings.watchdog.move_tilt_deg}°)` : DASH],
              ['Moved', pos?.moved_m != null ? `${Math.round(pos.moved_m)} m (alert at ${settings.watchdog.move_distance_m} m)` : DASH],
            ]}
          />
          {pos?.lat != null && pos.lon != null ? (
            <a
              className="mt-3 inline-block text-sm text-accent underline"
              href={`https://www.openstreetmap.org/?mlat=${pos.lat}&mlon=${pos.lon}#map=18/${pos.lat}/${pos.lon}`}
              target="_blank"
              rel="noreferrer noopener"
            >
              Open map
            </a>
          ) : null}
        </Card>
      </div>

      {canControl ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <CommandCard name="refresh" compact />
          <CommandCard name="capability_probe" compact />
        </div>
      ) : null}

      <Card title="Capability map">
        {caps && Object.keys(caps).length ? (
          <TableWrap label="Capability map">
            <table className="w-full min-w-[28rem]">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>Command</th>
                  <th className={th}>Supported</th>
                  <th className={th}>Method / note</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(caps)
                  .sort(([a], [b]) => a.localeCompare(b))
                  .map(([name, c]) => (
                    <tr key={name} className="border-b border-border/60 last:border-0">
                      <td className={td}>
                        <span className="font-medium">{COMMANDS[name as keyof typeof COMMANDS]?.label ?? name}</span>
                        <span className="block font-mono text-xs text-faint">{name}</span>
                      </td>
                      <td className={td}>{c.supported ? <Badge tone="ok">Yes</Badge> : <Badge tone="danger">No</Badge>}</td>
                      <td className={cx(td, 'text-xs text-muted break-anywhere')}>
                        {c.method ? <span className="font-mono">{c.method}</span> : null}
                        {c.method && c.note ? ' · ' : ''}
                        {c.note}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </TableWrap>
        ) : (
          <Empty>The phone has not reported its capability map yet.</Empty>
        )}
      </Card>
    </div>
  );
}

// --- processes --------------------------------------------------------------------------------

function ProcessesTab() {
  const snap = useSnapshot();
  const canControl = useCanControl();
  const run = useRunCommand();
  const last = useLastCommands().get('process_list');
  const [busy, setBusy] = useState(false);
  const top = snap?.state.system?.top ?? [];
  return (
    <div className="space-y-4">
      <Card title="Top processes" stale={useStaleness('system')} actions={<span className="text-xs text-faint">from the last slow reading</span>}>
        {top.length ? (
          <TableWrap label="Top processes">
            <table className="w-full min-w-[24rem]">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>PID</th>
                  <th className={th}>Name</th>
                  <th className={th}>CPU</th>
                  <th className={th}>Memory</th>
                </tr>
              </thead>
              <tbody>
                {top.map((p) => (
                  <tr key={p.pid} className="border-b border-border/60 last:border-0">
                    <td className={cx(td, 'tabular')}>{p.pid}</td>
                    <td className={cx(td, 'font-mono text-xs break-anywhere')}>{p.name}</td>
                    <td className={cx(td, 'tabular')}>{p.cpu_pct != null ? `${trimNumber(p.cpu_pct, 1)}%` : DASH}</td>
                    <td className={cx(td, 'tabular')}>{p.mem_mb != null ? formatMb(p.mem_mb) : DASH}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>
        ) : (
          <Empty>No process list in the last reading.</Empty>
        )}
      </Card>
      <Card
        title="Full process list (top -n 1)"
        actions={
          canControl ? (
            <Button
              size="sm"
              variant="primary"
              busy={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await run('process_list', {});
                } finally {
                  setBusy(false);
                }
              }}
            >
              Refresh process list
            </Button>
          ) : null
        }
      >
        {last ? (
          <div className="space-y-2">
            <LastResult cmd={last} compact />
            {hasResult(last) && (last.output || last.error) ? (
              <pre className="max-h-[32rem] overflow-auto rounded-lg bg-surface-2 p-3 font-mono text-[11px] leading-snug whitespace-pre">{last.error || last.output}</pre>
            ) : (
              <p className="text-sm text-muted">Waiting for the phone…</p>
            )}
          </div>
        ) : (
          <Empty>No process list yet.{canControl ? ' Press "Refresh process list".' : ''}</Empty>
        )}
      </Card>
    </div>
  );
}

// --- apps -------------------------------------------------------------------------------------

function AppsTab() {
  const canControl = useCanControl();
  const run = useRunCommand();
  const commands = useDeviceCommands();
  const lastList = useLastCommands().get('app_list');
  const [busy, setBusy] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<'user' | 'system' | 'all'>('user');
  const [state, setState] = useState<'all' | 'enabled' | 'disabled'>('all');

  const rows = useMemo(() => {
    const parsed = lastList?.status === 'done' ? parseAppList(lastList.output) : null;
    return parsed && lastList ? applyAppChanges(parsed, lastList.created_at, commands) : null;
  }, [lastList, commands]);

  const filtered = (rows ?? []).filter(
    (r) =>
      (kind === 'all' || (kind === 'system') === r.system) &&
      (state === 'all' || (state === 'enabled') === r.enabled) &&
      (!query || r.pkg.toLowerCase().includes(query.toLowerCase()) || r.label?.toLowerCase().includes(query.toLowerCase())),
  );

  const act = async (name: 'app_force_stop' | 'app_disable' | 'app_enable', pkg: string) => {
    setBusy(`${name}:${pkg}`);
    try {
      await run(name, { package: pkg });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card
      title="Installed apps"
      actions={
        canControl ? (
          <Button
            size="sm"
            variant="primary"
            busy={busy === 'list'}
            onClick={async () => {
              setBusy('list');
              try {
                await run('app_list', {});
              } finally {
                setBusy(null);
              }
            }}
          >
            {rows ? 'Reload list' : 'Load app list'}
          </Button>
        ) : null
      }
    >
      {lastList ? (
        <div className="mb-3">
          <LastResult cmd={lastList} compact />
        </div>
      ) : null}
      {lastList && lastList.status !== 'done' && !['failed', 'rejected', 'expired'].includes(lastList.status) ? (
        <p className="text-sm text-muted">Waiting for the phone to list its apps…</p>
      ) : null}
      {lastList?.status === 'done' && !rows ? <Notice tone="warn">The phone sent an app list this Dashboard cannot read.</Notice> : null}
      {rows ? (
        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Field label="Search">
              {({ id }) => <TextInput id={id} type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Package name" />}
            </Field>
            <Field label="Type">
              {({ id }) => (
                <Select id={id} value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
                  <option value="user">User apps</option>
                  <option value="system">System apps</option>
                  <option value="all">All</option>
                </Select>
              )}
            </Field>
            <Field label="State">
              {({ id }) => (
                <Select id={id} value={state} onChange={(e) => setState(e.target.value as typeof state)}>
                  <option value="all">Any</option>
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                </Select>
              )}
            </Field>
          </div>
          <p className="text-xs text-muted">
            {filtered.length} of {rows.length} apps
          </p>
          <ul className="divide-y divide-border">
            {filtered.slice(0, 300).map((r) => (
              <li key={r.pkg} className="flex flex-wrap items-center gap-2 py-2">
                <div className="min-w-0 flex-1">
                  <p className="font-mono text-sm break-anywhere">{r.pkg}</p>
                  <p className="mt-0.5 flex flex-wrap gap-1">
                    {r.system ? <Badge>system</Badge> : <Badge tone="info">user</Badge>}
                    {r.enabled ? null : <Badge tone="warn">disabled</Badge>}
                  </p>
                </div>
                {canControl ? (
                  <div className="flex flex-wrap gap-1.5">
                    <Button size="sm" busy={busy === `app_force_stop:${r.pkg}`} onClick={() => act('app_force_stop', r.pkg)} disabled={!r.enabled}>
                      Force stop
                    </Button>
                    {r.enabled ? (
                      <Button size="sm" variant="danger" busy={busy === `app_disable:${r.pkg}`} onClick={() => act('app_disable', r.pkg)}>
                        Disable
                      </Button>
                    ) : (
                      <Button size="sm" variant="primary" busy={busy === `app_enable:${r.pkg}`} onClick={() => act('app_enable', r.pkg)}>
                        Enable
                      </Button>
                    )}
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
          {filtered.length > 300 ? <p className="text-xs text-muted">Showing the first 300. Narrow the search to see more.</p> : null}
        </div>
      ) : !lastList ? (
        <Empty>No app list yet.{canControl ? ' Press "Load app list".' : ''}</Empty>
      ) : null}
    </Card>
  );
}

// --- audit log --------------------------------------------------------------------------------

const AUDIT_PAGE = 50;

function AuditTab() {
  const deviceId = useDeviceId();
  const live = useDeviceCommands();
  const now = useNow(10_000);
  const [name, setName] = useState('');
  const [status, setStatus] = useState<CommandStatus | ''>('');
  const [source, setSource] = useState('');
  const [open, setOpen] = useState<string | null>(null);

  const accept = useCallback(
    (c: CommandRecord) => (!name || c.name === name) && (!status || c.status === status) && (!source || c.source === source),
    [name, status, source],
  );
  const list = usePagedList<CommandRecord>({
    fetchPage: (before, signal) => api.commands({ device_id: deviceId, limit: AUDIT_PAGE, before }, signal),
    resetKey: `${deviceId}`,
    live,
    keyOf: (c) => c.id,
    timeOf: (c) => c.created_at,
    pageSize: AUDIT_PAGE,
  });
  const rows = list.items.filter(accept);

  return (
    <Card title="Command audit log">
      <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Field label="Command">
          {({ id }) => (
            <Select id={id} value={name} onChange={(e) => setName(e.target.value)}>
              <option value="">All commands</option>
              {COMMAND_NAMES.map((n) => (
                <option key={n} value={n}>
                  {COMMANDS[n].label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Status">
          {({ id }) => (
            <Select id={id} value={status} onChange={(e) => setStatus(e.target.value as CommandStatus | '')}>
              <option value="">Any status</option>
              {Object.entries(STATUS_META).map(([k, m]) => (
                <option key={k} value={k}>
                  {m.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Source">
          {({ id }) => (
            <Select id={id} value={source} onChange={(e) => setSource(e.target.value)}>
              <option value="">Any source</option>
              {['dashboard', 'watchdog', 'schedule', 'local_ui', 'sms', 'lan'].map((s) => (
                <option key={s} value={s}>
                  {titleCase(s)}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {list.error ? <Notice tone="danger">{list.error}</Notice> : null}
      {rows.length ? (
        <ul className="divide-y divide-border">
          {rows.map((c) => (
            <li key={c.id} className="py-2.5">
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge status={c.status} />
                <span className="min-w-0 flex-1 text-sm font-medium text-fg break-anywhere">{describeCommand(c.name, c.args)}</span>
                <span className="text-xs text-faint" title={formatDateTime(c.created_at)}>
                  {formatRelative(c.created_at, now)}
                </span>
              </div>
              <p className="mt-0.5 text-xs text-muted">
                {c.user ? `by ${c.user}` : `by ${titleCase(c.source)}`} · {formatDateTime(c.created_at)}
                {c.duration_ms != null ? ` · ${(c.duration_ms / 1000).toFixed(1)} s` : ''}
                {c.error ? ` · ${c.error}` : ''}
              </p>
              {c.output || c.error ? (
                <>
                  <button type="button" className="mt-1 text-xs text-accent hover:underline" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>
                    {open === c.id ? 'Hide output' : 'Show output'}
                  </button>
                  {open === c.id ? (
                    <pre className="mt-1 max-h-72 overflow-auto rounded-lg bg-surface-2 p-2 font-mono text-[11px] whitespace-pre-wrap break-anywhere">
                      {c.error || c.output}
                    </pre>
                  ) : null}
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : !list.loading ? (
        <Empty>No commands match.</Empty>
      ) : null}
      <div className="mt-3 flex items-center gap-3">
        {list.hasMore ? (
          <Button size="sm" busy={list.loading} onClick={() => void list.loadMore()}>
            Load older
          </Button>
        ) : (
          <span className="text-xs text-faint">{list.loading ? 'Loading…' : 'End of the log'}</span>
        )}
      </div>
    </Card>
  );
}

// --- events -----------------------------------------------------------------------------------

const EVENT_KINDS: EventKind[] = [
  'agent_start', 'boot', 'connectivity', 'cell_change', 'net_type_change', 'tether_up', 'tether_down', 'usb_connected',
  'usb_disconnected', 'power_connected', 'power_lost', 'weak_power', 'temp_warning', 'temp_critical', 'temp_normal',
  'charging_stopped', 'charging_resumed', 'radio_rest', 'watchdog_action', 'command', 'revert', 'moved', 'sms_received',
  'reboot_blocked', 'settings_applied', 'finder', 'error',
];
const EVENTS_PAGE = 100;

function EventsTab() {
  const deviceId = useDeviceId();
  const live = useDeviceEvents();
  const now = useNow(10_000);
  const [kind, setKind] = useState<EventKind | ''>('');
  const list = usePagedList<StoredEvent>({
    fetchPage: (before, signal) => api.events({ device_id: deviceId, kind, limit: EVENTS_PAGE, before }, signal),
    resetKey: `${deviceId}|${kind}`,
    live,
    keyOf: (e) => e.id,
    timeOf: (e) => e.ts,
    accept: (e) => !kind || e.kind === kind,
    pageSize: EVENTS_PAGE,
  });

  return (
    <Card title="Event timeline">
      <div className="mb-3 max-w-xs">
        <Field label="Kind">
          {({ id }) => (
            <Select id={id} value={kind} onChange={(e) => setKind(e.target.value as EventKind | '')}>
              <option value="">All events</option>
              {EVENT_KINDS.map((k) => (
                <option key={k} value={k}>
                  {titleCase(k)}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {list.error ? <Notice tone="danger">{list.error}</Notice> : null}
      {list.items.length ? (
        <ul className="divide-y divide-border">
          {list.items.map((e) => (
            <EventRow key={e.id} event={e} now={now} />
          ))}
        </ul>
      ) : !list.loading ? (
        <Empty>No events{kind ? ' of this kind' : ''}.</Empty>
      ) : null}
      <div className="mt-3">
        {list.hasMore ? (
          <Button size="sm" busy={list.loading} onClick={() => void list.loadMore()}>
            Load older
          </Button>
        ) : (
          <span className="text-xs text-faint">{list.loading ? 'Loading…' : 'Start of the timeline'}</span>
        )}
      </div>
    </Card>
  );
}

// --- root console -----------------------------------------------------------------------------

interface ConsoleEntry {
  key: number;
  cmd: string;
  id: string | null;
}

const HISTORY_KEY = 'hsm.shell_history';

function loadHistory(): string[] {
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(HISTORY_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').slice(-50) : [];
  } catch {
    return [];
  }
}

function saveHistory(h: string[]) {
  try {
    sessionStorage.setItem(HISTORY_KEY, JSON.stringify(h.slice(-50)));
  } catch {
    /* storage unavailable */
  }
}

function ConsoleOutput({ entry }: { entry: ConsoleEntry }) {
  const cmd = useCommand(entry.id);
  return (
    <div className="border-b border-white/10 py-2 last:border-0">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[#7ee787]">#</span>
        <span className="min-w-0 flex-1 text-[#e6edf3] break-anywhere">{entry.cmd}</span>
        {cmd ? <StatusBadge status={cmd.status} /> : entry.id ? null : <Badge tone="danger">not sent</Badge>}
      </div>
      {cmd && (cmd.output || cmd.error) ? (
        <pre className={cx('mt-1 whitespace-pre-wrap break-anywhere', cmd.error ? 'text-[#ff7b72]' : 'text-[#c9d1d9]')}>{cmd.error ? `${cmd.error}\n${cmd.output ?? ''}` : cmd.output}</pre>
      ) : cmd && ['queued', 'sent', 'acked'].includes(cmd.status) ? (
        <p className="mt-1 text-[#8b949e]">waiting for the phone…</p>
      ) : null}
    </div>
  );
}

function ConsoleTab() {
  const canControl = useCanControl();
  const run = useRunCommand();
  const [input, setInput] = useState('');
  const [timeout, setTimeoutS] = useState('30');
  const [entries, setEntries] = useState<ConsoleEntry[]>([]);
  const [history, setHistory] = useState<string[]>(loadHistory);
  const [cursor, setCursor] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  // Kept in memory only while this console is open, so each command does not re-prompt.
  const passwordRef = useRef<string>('');
  const scrollRef = useRef<HTMLDivElement>(null);
  const keyRef = useRef(0);

  useEffect(() => () => {
    passwordRef.current = '';
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [entries]);

  const submit = async () => {
    const cmd = input.trim();
    if (!cmd || busy) return;
    const t = Number(timeout);
    setBusy(true);
    try {
      const rec = await run(
        'shell',
        { cmd, timeout_s: Number.isInteger(t) && t >= 1 && t <= 600 ? t : 30 },
        { password: passwordRef.current, onPassword: (pw) => (passwordRef.current = pw), quiet: true },
      );
      if (!rec) return;
      const nextHistory = [...history.filter((h) => h !== cmd), cmd];
      setHistory(nextHistory);
      saveHistory(nextHistory);
      setEntries((e) => [...e, { key: keyRef.current++, cmd, id: rec.id }].slice(-100));
      setInput('');
      setCursor(null);
    } finally {
      setBusy(false);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowUp' && history.length) {
      e.preventDefault();
      const next = cursor == null ? history.length - 1 : Math.max(0, cursor - 1);
      setCursor(next);
      setInput(history[next] ?? '');
    } else if (e.key === 'ArrowDown' && cursor != null) {
      e.preventDefault();
      const next = cursor + 1;
      if (next >= history.length) {
        setCursor(null);
        setInput('');
      } else {
        setCursor(next);
        setInput(history[next] ?? '');
      }
    }
  };

  if (!canControl) return <Notice tone="warn">Set up two-factor authentication to use the root console.</Notice>;

  return (
    <Card title="Root console" actions={<Button size="sm" variant="ghost" onClick={() => setEntries([])}>Clear</Button>}>
      <Notice tone="warn">
        Commands run as root on the phone. Each one is confirmed with your password (remembered while this tab stays open) and stored in the audit log.
      </Notice>
      <div
        ref={scrollRef}
        className="mt-3 h-80 overflow-y-auto rounded-xl bg-[#0d1117] p-3 font-mono text-xs leading-relaxed"
        role="log"
        aria-live="polite"
        aria-label="Console output"
      >
        {entries.length ? entries.map((e) => <ConsoleOutput key={e.key} entry={e} />) : <p className="text-[#8b949e]">Output appears here.</p>}
      </div>
      <form
        className="mt-3 flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Field label="Command" className="min-w-0 flex-1" help="Up and down arrows recall earlier commands.">
          {({ id, describedBy }) => (
            <div className="flex items-center gap-2">
              <IconTerminal className="shrink-0 text-muted" />
              <TextInput
                id={id}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={onKeyDown}
                placeholder="dumpsys battery"
                autoCapitalize="none"
                autoComplete="off"
                spellCheck={false}
                aria-describedby={describedBy}
                className="font-mono"
              />
            </div>
          )}
        </Field>
        <Field label="Timeout (s)" className="w-28">
          {({ id }) => <TextInput id={id} type="number" min={1} max={600} value={timeout} onChange={(e) => setTimeoutS(e.target.value)} />}
        </Field>
        <Button type="submit" variant="danger" busy={busy} disabled={!input.trim()}>
          Run as root
        </Button>
      </form>
    </Card>
  );
}
