/** Argument forms for each command (plan 6.3). Each validates its input before sending. */
import { COMMANDS, rankSpots, type CommandName, type NetworkMode } from '@hsm/shared';
import { useState, type ComponentType } from 'react';
import { Link } from 'react-router-dom';
import { NETWORK_MODES, hotspotPasswordError } from '../../lib/commandMeta';
import { useSpots } from '../../store/selectors';
import { Field, Select, TextInput } from '../ui/forms';
import { Badge, Button } from '../ui/primitives';
import type { ControlBodyProps } from './CommandCard';

type Body = ComponentType<ControlBodyProps>;

export function RunButton({ name, run, busy, disabled }: ControlBodyProps) {
  const spec = COMMANDS[name];
  const destructive = name === 'reboot' || name === 'power_off';
  return (
    <Button variant={destructive ? 'danger' : 'primary'} busy={busy} disabled={disabled} onClick={() => void run({})}>
      {spec.label}
    </Button>
  );
}

function StateBadge({ value, on = 'On', off = 'Off' }: { value: boolean | null | undefined; on?: string; off?: string }) {
  if (value == null) return <Badge>State unknown</Badge>;
  return <Badge tone={value ? 'ok' : 'neutral'}>Now {value ? on : off}</Badge>;
}

/** Two buttons for an { enabled } command, highlighting the action that changes the state. */
function toggle(stateOf: (p: ControlBodyProps) => boolean | null | undefined, extra?: (p: ControlBodyProps) => Record<string, unknown>): Body {
  return function ToggleBody(p) {
    const current = stateOf(p);
    return (
      <div className="flex flex-wrap items-center gap-2">
        <StateBadge value={current} />
        <Button
          variant={current === false ? 'primary' : 'secondary'}
          busy={p.busy}
          disabled={p.disabled}
          onClick={() => void p.run({ enabled: true, ...(extra?.(p) ?? {}) })}
        >
          Turn on
        </Button>
        <Button
          variant={current === true ? 'primary' : 'secondary'}
          busy={p.busy}
          disabled={p.disabled}
          onClick={() => void p.run({ enabled: false, ...(extra?.(p) ?? {}) })}
        >
          Turn off
        </Button>
      </div>
    );
  };
}

function NumberArgForm({
  p,
  arg,
  label,
  unit,
  min,
  max,
  initial,
  action,
  extraArgs,
}: {
  p: ControlBodyProps;
  arg: string;
  label: string;
  unit?: string;
  min: number;
  max: number;
  initial: number;
  action: string;
  extraArgs?: Record<string, unknown>;
}) {
  const [value, setValue] = useState(String(initial));
  const n = Number(value);
  const error = value.trim() === '' || !Number.isInteger(n) ? 'Enter a whole number.' : n < min || n > max ? `Between ${min} and ${max}.` : null;
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!error) void p.run({ ...(extraArgs ?? {}), [arg]: n });
      }}
    >
      <Field label={`${label}${unit ? ` (${unit})` : ''}`} error={value === String(initial) ? null : error} className="w-36">
        {({ id, describedBy, invalid }) => (
          <TextInput
            id={id}
            type="number"
            inputMode="numeric"
            min={min}
            max={max}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            disabled={p.disabled}
          />
        )}
      </Field>
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled || Boolean(error)}>
        {action}
      </Button>
    </form>
  );
}

function PackageForm(p: ControlBodyProps) {
  const [pkg, setPkg] = useState('');
  const valid = /^[a-zA-Z][\w]*(\.[a-zA-Z_][\w]*)+$/.test(pkg.trim());
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) void p.run({ package: pkg.trim() });
      }}
    >
      <Field label="Package name" error={pkg && !valid ? 'Like com.example.app' : null} className="min-w-0 flex-1">
        {({ id, describedBy, invalid }) => (
          <TextInput
            id={id}
            value={pkg}
            onChange={(e) => setPkg(e.target.value)}
            placeholder="com.example.app"
            autoCapitalize="none"
            spellCheck={false}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            disabled={p.disabled}
            className="font-mono"
          />
        )}
      </Field>
      <Button type="submit" variant={p.name === 'app_disable' ? 'danger' : 'primary'} busy={p.busy} disabled={p.disabled || !valid}>
        {COMMANDS[p.name].label}
      </Button>
      <p className="w-full text-xs text-muted">
        Or pick from the list under{' '}
        <Link to="/system?tab=apps" className="text-accent underline">
          System &gt; Apps
        </Link>
        .
      </p>
    </form>
  );
}

function UsbTetherBody(p: ControlBodyProps) {
  const tether = p.snapshot?.state.tether;
  const current = tether?.usb_function === 'ncm' || tether?.usb_function === 'rndis' ? tether.usb_function : p.settings.watchdog.usb_function;
  const [fn, setFn] = useState<'rndis' | 'ncm'>(current);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <StateBadge value={tether?.usb_tether_on} />
        {tether?.usb_connected === false ? <Badge tone="warn">USB not connected</Badge> : null}
      </div>
      <Field label="USB function" className="max-w-xs">
        {({ id }) => (
          <Select id={id} value={fn} onChange={(e) => setFn(e.target.value as 'rndis' | 'ncm')} disabled={p.disabled}>
            <option value="rndis">RNDIS (Windows, most routers)</option>
            <option value="ncm">NCM (macOS, Linux)</option>
          </Select>
        )}
      </Field>
      <div className="flex flex-wrap gap-2">
        <Button variant={tether?.usb_tether_on === false ? 'primary' : 'secondary'} busy={p.busy} disabled={p.disabled} onClick={() => void p.run({ enabled: true, function: fn })}>
          Turn on
        </Button>
        <Button variant="secondary" busy={p.busy} disabled={p.disabled} onClick={() => void p.run({ enabled: false })}>
          Turn off
        </Button>
      </div>
    </div>
  );
}

function HotspotBody(p: ControlBodyProps) {
  const tether = p.snapshot?.state.tether;
  const [ssid, setSsid] = useState(tether?.hotspot_ssid ?? '');
  const [password, setPassword] = useState('');
  const ssidErr = !ssid.trim() ? 'Enter a network name.' : new TextEncoder().encode(ssid).length > 32 ? 'At most 32 bytes.' : null;
  const pwErr = hotspotPasswordError(password);
  const [touched, setTouched] = useState(false);
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        setTouched(true);
        if (!ssidErr && !pwErr) void p.run({ enabled: true, ssid: ssid.trim(), password });
      }}
    >
      <StateBadge value={tether?.hotspot_on} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="Network name (SSID)" error={touched ? ssidErr : null}>
          {({ id, describedBy, invalid }) => (
            <TextInput id={id} value={ssid} onChange={(e) => setSsid(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={p.disabled} />
          )}
        </Field>
        <Field label="Password" error={touched ? pwErr : null}>
          {({ id, describedBy, invalid }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-describedby={describedBy}
              aria-invalid={invalid}
              disabled={p.disabled}
            />
          )}
        </Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled}>
          Turn on
        </Button>
        <Button variant="secondary" busy={p.busy} disabled={p.disabled} onClick={() => void p.run({ enabled: false })}>
          Turn off
        </Button>
      </div>
    </form>
  );
}

function NetworkModeBody(p: ControlBodyProps) {
  const fromLast = p.last?.args?.mode as NetworkMode | undefined;
  const [mode, setMode] = useState<NetworkMode>(fromLast ?? 'auto');
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        void p.run({ mode });
      }}
    >
      <Field label="Allowed networks" className="min-w-48 flex-1">
        {({ id }) => (
          <Select id={id} value={mode} onChange={(e) => setMode(e.target.value as NetworkMode)} disabled={p.disabled}>
            {NETWORK_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled}>
        Apply
      </Button>
    </form>
  );
}

function BrightnessBody(p: ControlBodyProps) {
  const [level, setLevel] = useState(10);
  return (
    <form
      className="space-y-2"
      onSubmit={(e) => {
        e.preventDefault();
        void p.run({ level });
      }}
    >
      <Field label={`Brightness: ${level} of 255`}>
        {({ id }) => (
          <input
            id={id}
            type="range"
            min={0}
            max={255}
            step={1}
            value={level}
            onChange={(e) => setLevel(Number(e.target.value))}
            disabled={p.disabled}
            className="w-full accent-[var(--accent)]"
          />
        )}
      </Field>
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled}>
        Apply
      </Button>
    </form>
  );
}

function ScreenBody(p: ControlBodyProps) {
  const on = p.snapshot?.state.system?.screen_on;
  return (
    <div className="flex flex-wrap items-center gap-2">
      <StateBadge value={on} />
      <Button busy={p.busy} disabled={p.disabled} onClick={() => void p.run({ on: true })}>
        Wake
      </Button>
      <Button busy={p.busy} disabled={p.disabled} onClick={() => void p.run({ on: false })}>
        Sleep
      </Button>
    </div>
  );
}

export function ChargeLimitBody(p: ControlBodyProps) {
  const current = p.snapshot?.state.power?.charge_limit_pct ?? p.settings.watchdog.charge_limit_pct;
  const [pct, setPct] = useState(current);
  return (
    <form
      className="space-y-2"
      onSubmit={(e) => {
        e.preventDefault();
        void p.run({ percent: pct });
      }}
    >
      <Field label={`Stop charging at ${pct}%`} help={`Now ${current}%. 50 to 60% is safest for a phone that stays plugged in outdoors (plan 4.2).`}>
        {({ id, describedBy }) => (
          <input
            id={id}
            type="range"
            min={20}
            max={100}
            step={5}
            value={pct}
            onChange={(e) => setPct(Number(e.target.value))}
            aria-describedby={describedBy}
            disabled={p.disabled}
            className="w-full accent-[var(--accent)]"
          />
        )}
      </Field>
      {pct > 80 ? <p className="text-xs text-warn">Above 80% shortens battery life and raises the swelling risk in heat.</p> : null}
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled || pct === current}>
        Set limit
      </Button>
    </form>
  );
}

function SpotSelectBody(p: ControlBodyProps & { optional?: boolean; action: string }) {
  const spots = rankSpots(useSpots());
  const [spot, setSpot] = useState('');
  const required = !p.optional;
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (required && !spot) return;
        void p.run(spot ? { spot_id: spot } : {});
      }}
    >
      <Field label={p.optional ? 'Attach to spot (optional)' : 'Spot'} className="min-w-48 flex-1">
        {({ id }) => (
          <Select id={id} value={spot} onChange={(e) => setSpot(e.target.value)} disabled={p.disabled}>
            <option value="">{p.optional ? 'No spot (just measure)' : spots.length ? 'Choose a spot' : 'No saved spots'}</option>
            {spots.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
                {s.baseline ? ' (baseline)' : ''}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Button type="submit" variant={p.name === 'finder_delete_spot' ? 'danger' : 'primary'} busy={p.busy} disabled={p.disabled || (required && !spot)}>
        {p.action}
      </Button>
    </form>
  );
}

export const ORIENTATIONS = [
  { value: 'upright', label: 'Upright' },
  { value: 'flat', label: 'Flat' },
  { value: 'rot90', label: 'Turned 90°' },
];

export function SpotTestBody(p: ControlBodyProps) {
  const [name, setName] = useState('');
  const [orientation, setOrientation] = useState('upright');
  const [duration, setDuration] = useState(String(p.settings.finder.spot_test_s));
  const d = Number(duration);
  const durErr = !Number.isInteger(d) || d < 10 || d > 300 ? 'Between 10 and 300 s.' : null;
  const nameErr = !name.trim() ? 'Name the spot, e.g. "Roof NE corner".' : name.length > 60 ? 'At most 60 characters.' : null;
  const [touched, setTouched] = useState(false);
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        setTouched(true);
        if (!nameErr && !durErr) void p.run({ name: name.trim(), orientation, duration_s: d });
      }}
    >
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Field label="Spot name" error={touched ? nameErr : null} className="sm:col-span-3">
          {({ id, describedBy, invalid }) => (
            <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={p.disabled} />
          )}
        </Field>
        <Field label="Orientation" className="sm:col-span-2">
          {({ id }) => (
            <Select id={id} value={orientation} onChange={(e) => setOrientation(e.target.value)} disabled={p.disabled}>
              {ORIENTATIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Duration (s)" error={durErr}>
          {({ id, describedBy, invalid }) => (
            <TextInput
              id={id}
              type="number"
              inputMode="numeric"
              value={duration}
              onChange={(e) => setDuration(e.target.value)}
              aria-describedby={describedBy}
              aria-invalid={invalid}
              disabled={p.disabled}
            />
          )}
        </Field>
      </div>
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled}>
        Start spot test
      </Button>
      <p className="text-xs text-muted">Hold the phone still at mounting height for the whole test.</p>
    </form>
  );
}

function SmsBody() {
  return (
    <p className="text-sm text-muted">
      Write and send messages on the{' '}
      <Link to="/messages" className="text-accent underline">
        Messages
      </Link>{' '}
      screen.
    </p>
  );
}

function UssdBody(p: ControlBodyProps) {
  const [code, setCode] = useState('');
  const valid = /^[*#][0-9*#]{1,30}#$/.test(code.trim());
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) void p.run({ code: code.trim() });
      }}
    >
      <Field label="USSD code" error={code && !valid ? 'Like *123# or *121*1#' : null} className="w-44">
        {({ id, describedBy, invalid }) => (
          <TextInput id={id} value={code} onChange={(e) => setCode(e.target.value)} inputMode="tel" placeholder="*123#" aria-describedby={describedBy} aria-invalid={invalid} disabled={p.disabled} className="font-mono" />
        )}
      </Field>
      <Button type="submit" variant="primary" busy={p.busy} disabled={p.disabled || !valid}>
        Run
      </Button>
    </form>
  );
}

function ShellBody() {
  return (
    <Link to="/system?tab=console" className="inline-flex min-h-11 items-center rounded-xl bg-accent px-4 text-sm font-medium text-accent-fg">
      Open root console
    </Link>
  );
}

function AppListBody(p: ControlBodyProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <RunButton {...p} />
      <Link to="/system?tab=apps" className="text-sm text-accent underline">
        View in System &gt; Apps
      </Link>
    </div>
  );
}

function WatchdogSimBody(p: ControlBodyProps) {
  return (
    <NumberArgForm
      p={p}
      arg="duration_s"
      label="Simulated outage"
      unit="s"
      min={30}
      max={3600}
      initial={Math.max(120, p.settings.watchdog.airplane_after_s + 30)}
      action="Simulate"
      extraArgs={{ scenario: 'internet_down' }}
    />
  );
}

export const COMMAND_FORMS: Partial<Record<CommandName, Body>> = {
  mobile_data: toggle((p) => p.snapshot?.state.radio?.data_enabled),
  airplane_mode: toggle((p) => p.snapshot?.state.radio?.airplane),
  wifi: toggle(() => null),
  bluetooth: toggle(() => null),
  charging: toggle((p) => p.snapshot?.state.power?.charging_enabled),
  data_cycle: (p) => (
    <NumberArgForm p={p} arg="off_seconds" label="Data off for" unit="s" min={1} max={120} initial={p.settings.watchdog.data_off_s} action="Cycle data" />
  ),
  airplane_cycle: (p) => (
    <NumberArgForm p={p} arg="off_seconds" label="Airplane on for" unit="s" min={3} max={120} initial={p.settings.watchdog.airplane_off_s} action="Cycle airplane" />
  ),
  usb_tethering: UsbTetherBody,
  hotspot: HotspotBody,
  network_mode: NetworkModeBody,
  screen: ScreenBody,
  brightness: BrightnessBody,
  charge_limit: ChargeLimitBody,
  app_list: AppListBody,
  app_force_stop: PackageForm,
  app_disable: PackageForm,
  app_enable: PackageForm,
  send_sms: SmsBody,
  ussd: UssdBody,
  shell: ShellBody,
  watchdog_simulate: WatchdogSimBody,
  speed_test: (p) => <SpotSelectBody {...p} optional action="Run speed test" />,
  finder_spot_test: SpotTestBody,
  finder_set_baseline: (p) => <SpotSelectBody {...p} action="Set baseline" />,
  finder_delete_spot: (p) => <SpotSelectBody {...p} action="Delete spot" />,
};
