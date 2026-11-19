import type { DeviceSummary, PairCodeResponse } from '@hsm/shared';
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { SettingsEditor } from '../components/settings/SettingsEditor';
import { MIN_PASSWORD, UsersPanel } from '../components/settings/UsersPanel';
import { TotpEnrollment } from '../components/TotpEnrollment';
import { CopyButton } from '../components/ui/CopyButton';
import { Dialog } from '../components/ui/Dialog';
import { Field, TextInput } from '../components/ui/forms';
import { Badge, Button, Card, Empty, Notice, PageHeader } from '../components/ui/primitives';
import { QrCode } from '../components/ui/QrCode';
import { Tabs } from '../components/ui/Tabs';
import { api, errorMessage } from '../lib/api';
import { formatCountdown, formatDate, formatRelative, toMs } from '../lib/format';
import { useAsync, useDocumentTitle, useNow } from '../lib/hooks';
import { useConn } from '../store/conn';
import { useData } from '../store/data';
import { useCanControl, useIsAdmin } from '../store/selectors';
import { useSession } from '../store/session';
import { toast } from '../store/toasts';
import { useUi } from '../store/ui';

type TabId = 'device' | 'users' | 'account' | 'pairing';

export function SettingsScreen() {
  useDocumentTitle('Settings');
  const isAdmin = useIsAdmin();
  const [params, setParams] = useSearchParams();
  const tabs = [
    { id: 'device' as const, label: 'Device settings' },
    ...(isAdmin ? [{ id: 'users' as const, label: 'Users' }] : []),
    { id: 'account' as const, label: 'My account' },
    ...(isAdmin ? [{ id: 'pairing' as const, label: 'Pairing and phones' }] : []),
  ];
  const requested = params.get('tab') as TabId | null;
  const active: TabId = tabs.some((t) => t.id === requested) ? requested! : 'device';
  return (
    <div>
      <PageHeader title="Settings" subtitle="Thresholds, watchdog rules, alerts, users and pairing." />
      <Tabs label="Settings sections" tabs={tabs} active={active} onChange={(id) => setParams({ tab: id }, { replace: true })}>
        {active === 'device' ? <SettingsEditor focusSection={params.get('section')} /> : null}
        {active === 'users' && isAdmin ? <UsersPanel /> : null}
        {active === 'account' ? <AccountTab /> : null}
        {active === 'pairing' && isAdmin ? <PairingTab /> : null}
      </Tabs>
    </div>
  );
}

// --- my account -------------------------------------------------------------------------------

function ChangePassword() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form
      className="grid max-w-md grid-cols-1 gap-3"
      onSubmit={async (e) => {
        e.preventDefault();
        if (next.length < MIN_PASSWORD) return setError(`The new password needs at least ${MIN_PASSWORD} characters.`);
        if (next !== confirm) return setError('The new passwords do not match.');
        if (next === current) return setError('The new password must be different.');
        setBusy(true);
        setError(null);
        try {
          await api.changePassword(current, next);
          setCurrent('');
          setNext('');
          setConfirm('');
          toast.success('Password changed.');
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <input type="text" autoComplete="username" value={useSession.getState().user?.username ?? ''} readOnly hidden />
      <Field label="Current password">
        {({ id }) => <TextInput id={id} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />}
      </Field>
      <Field label="New password" help={`At least ${MIN_PASSWORD} characters.`}>
        {({ id, describedBy }) => <TextInput id={id} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} aria-describedby={describedBy} />}
      </Field>
      <Field label="Repeat new password">
        {({ id }) => <TextInput id={id} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <div>
        <Button type="submit" variant="primary" busy={busy}>
          Change password
        </Button>
      </div>
    </form>
  );
}

function DisableTotp() {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isAdmin = useIsAdmin();
  return (
    <form
      className="grid max-w-md grid-cols-1 gap-3"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!password || !/^\d{6}$/.test(code.trim())) return setError('Enter your password and a current 6-digit code.');
        setBusy(true);
        setError(null);
        try {
          await api.totpDisable(password, code.trim());
          await useSession.getState().refreshMe();
          toast.success('Two-factor authentication is off.');
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      {isAdmin ? <Notice tone="warn">Admins without two-factor authentication cannot use controls or change settings.</Notice> : null}
      <Field label="Password">
        {({ id }) => <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Field label="Current 6-digit code">
        {({ id }) => <TextInput id={id} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} className="max-w-40 font-mono tracking-widest" />}
      </Field>
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <div>
        <Button type="submit" variant="danger" busy={busy}>
          Turn off two-factor
        </Button>
      </div>
    </form>
  );
}

function AccountTab() {
  const user = useSession((s) => s.user);
  const logout = useSession((s) => s.logout);
  const [showDisable, setShowDisable] = useState(false);
  return (
    <div className="space-y-4">
      <Card title="Account">
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-lg font-semibold text-fg">{user?.username}</p>
          <Badge tone={user?.role === 'admin' ? 'info' : 'neutral'}>{user?.role}</Badge>
          <Button size="sm" className="ml-auto" onClick={() => logout()}>
            Sign out
          </Button>
        </div>
      </Card>
      <Card title="Two-factor authentication">
        {user?.totp_enabled ? (
          <div className="space-y-3">
            <p className="text-sm text-fg">
              <Badge tone="ok">On</Badge> Signing in needs a code from your authenticator app.
            </p>
            {showDisable ? (
              <DisableTotp />
            ) : (
              <Button size="sm" variant="ghost" onClick={() => setShowDisable(true)}>
                Turn off…
              </Button>
            )}
          </div>
        ) : (
          <TotpEnrollment />
        )}
      </Card>
      <Card title="Change password">
        <ChangePassword />
      </Card>
    </div>
  );
}

// --- pairing ----------------------------------------------------------------------------------

function PairPanel({ onPaired }: { onPaired: () => void }) {
  const canControl = useCanControl();
  const now = useNow(1000);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pair, setPair] = useState<PairCodeResponse | null>(null);
  const expiresAt = toMs(pair?.expires_at);
  const left = expiresAt != null ? expiresAt - now : 0;
  const active = Boolean(pair) && left > 0;

  // Poll for the new phone while a code is valid, so it shows up without a reload.
  useEffect(() => {
    if (!active) return;
    const id = setInterval(onPaired, 5000);
    return () => clearInterval(id);
  }, [active, onPaired]);

  return (
    <Card title="Pair a phone">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(null);
          try {
            setPair(await api.createPairCode(name.trim() || undefined));
          } catch (err) {
            setError(errorMessage(err));
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="Name for the phone (optional)" className="min-w-48 flex-1">
          {({ id }) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} placeholder="Roof hotspot" disabled={!canControl} />}
        </Field>
        <Button type="submit" variant="primary" busy={busy} disabled={!canControl}>
          {pair ? 'New pairing code' : 'Create pairing code'}
        </Button>
      </form>
      {error ? (
        <div className="mt-3">
          <Notice tone="danger">{error}</Notice>
        </div>
      ) : null}
      {pair ? (
        active ? (
          <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-[auto_minmax(0,1fr)]">
            <QrCode value={pair.deep_link} label="Pairing QR code: scan it with the hotspot phone's camera" size={220} />
            <div className="min-w-0 space-y-3">
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-muted">One-time code</p>
                <p className="tabular font-mono text-3xl font-bold tracking-[0.2em] text-fg">{pair.code}</p>
                <p className="text-sm text-muted" aria-live="off">
                  Expires in <span className="tabular font-semibold text-fg">{formatCountdown(left)}</span>
                </p>
              </div>
              <ol className="list-decimal space-y-2 pl-5 text-sm text-fg">
                <li>Scan the QR code with the hotspot phone’s camera; it opens the Agent’s pairing screen.</li>
                <li>
                  Or, for a phone with a dead screen, run this from a PC with the phone on USB:
                  <pre className="mt-1 overflow-x-auto rounded-lg bg-surface-2 p-2 font-mono text-xs whitespace-pre-wrap break-anywhere">{pair.adb_command}</pre>
                  <div className="mt-1">
                    <CopyButton text={pair.adb_command} label="Copy ADB command" />
                  </div>
                </li>
                <li>Or type the server address and the code into the Agent’s manual pairing form.</li>
              </ol>
            </div>
          </div>
        ) : (
          <div className="mt-3">
            <Notice tone="warn">The pairing code expired. Create a new one.</Notice>
          </div>
        )
      ) : null}
    </Card>
  );
}

function DevicesList({ devices, reload }: { devices: DeviceSummary[]; reload: () => void }) {
  const canControl = useCanControl();
  const now = useNow(15_000);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [unpair, setUnpair] = useState<DeviceSummary | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rename = async (d: DeviceSummary) => {
    const n = newName.trim();
    if (!n || n.length > 60) return toast.error('Name must be 1 to 60 characters.');
    try {
      await api.renameDevice(d.device_id, n);
      setRenaming(null);
      reload();
      toast.success('Renamed.');
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };

  return (
    <Card title="Paired phones">
      {devices.length ? (
        <ul className="divide-y divide-border">
          {devices.map((d) => (
            <li key={d.device_id} className="flex flex-wrap items-center gap-3 py-3">
              <div className="min-w-0 basis-full sm:basis-0 sm:flex-1">
                {renaming === d.device_id ? (
                  <form
                    className="flex flex-wrap items-center gap-2"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void rename(d);
                    }}
                  >
                    <label htmlFor={`name-${d.device_id}`} className="sr-only">
                      New name
                    </label>
                    <TextInput id={`name-${d.device_id}`} value={newName} onChange={(e) => setNewName(e.target.value)} className="max-w-60" autoFocus />
                    <Button size="sm" type="submit" variant="primary">
                      Save
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setRenaming(null)}>
                      Cancel
                    </Button>
                  </form>
                ) : (
                  <p className="font-semibold text-fg">{d.name}</p>
                )}
                <p className="mt-0.5 text-xs text-muted">
                  {d.info ? `${d.info.manufacturer} ${d.info.model} · Agent ${d.info.agent_version} · ` : ''}
                  {d.online ? 'online' : `last seen ${formatRelative(d.last_seen, now)}`} · paired {formatDate(d.paired_at)} · <span className="font-mono">{d.device_id}</span>
                </p>
              </div>
              <Badge tone={d.online ? 'ok' : 'danger'}>{d.online ? 'Online' : 'Offline'}</Badge>
              {canControl && renaming !== d.device_id ? (
                <div className="flex gap-1">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setRenaming(d.device_id);
                      setNewName(d.name);
                    }}
                  >
                    Rename
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-danger"
                    onClick={() => {
                      setUnpair(d);
                      setPassword('');
                      setError(null);
                    }}
                  >
                    Unpair
                  </Button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      ) : (
        <Empty>No phone paired yet.</Empty>
      )}
      <Dialog
        open={Boolean(unpair)}
        onClose={() => setUnpair(null)}
        title={`Unpair ${unpair?.name ?? ''}?`}
        description="Its device token is revoked immediately; the phone shows “Not paired” and needs a new pairing code. History is kept."
        footer={
          <>
            <Button onClick={() => setUnpair(null)}>Cancel</Button>
            <Button variant="danger" type="submit" form="unpair-form" busy={busy}>
              Unpair
            </Button>
          </>
        }
      >
        <form
          id="unpair-form"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!unpair) return;
            if (!password) return setError('Enter your password.');
            setBusy(true);
            try {
              await api.unpairDevice(unpair.device_id, password);
              toast.success(`${unpair.name} unpaired.`);
              setUnpair(null);
              reload();
            } catch (err) {
              setError(errorMessage(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          <Field label="Your password" error={error}>
            {({ id, describedBy, invalid }) => (
              <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} data-autofocus />
            )}
          </Field>
        </form>
      </Dialog>
    </Card>
  );
}

function PairingTab() {
  const epoch = useConn((s) => s.epoch);
  const [tick, setTick] = useState(0);
  const { data, error } = useAsync((signal) => api.devices(signal), [epoch, tick]);

  useEffect(() => {
    if (!data) return;
    const before = useData.getState().devices.length;
    useData.getState().setDevices(data);
    const cur = useUi.getState().deviceId;
    if (!cur || !data.some((d) => d.device_id === cur)) useUi.getState().setDeviceId(data[0]?.device_id ?? null);
    if (data.length > before && before >= 0 && tick > 0) toast.success('Phone paired.');
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return (
    <div className="space-y-4">
      <PairPanel onPaired={reload} />
      {error ? <Notice tone="danger">{errorMessage(error)}</Notice> : null}
      <DevicesList devices={data ?? useData.getState().devices} reload={reload} />
    </div>
  );
}
