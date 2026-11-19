import { formatDuration, formatRelative, toMs } from '../../lib/format';
import { useNow } from '../../lib/hooks';
import { useData } from '../../store/data';
import { useSnapshot } from '../../store/selectors';
import { useSession } from '../../store/session';
import { useUi } from '../../store/ui';
import { Select } from '../ui/forms';
import { IconLogout, IconMoon, IconPhone, IconSun } from '../ui/icons';
import { Badge, Dot, cx } from '../ui/primitives';

export function OnlinePill({ className }: { className?: string }) {
  const snap = useSnapshot();
  const now = useNow(5000);
  if (!snap) return null;
  if (snap.online) {
    const since = toMs(snap.connected_since);
    return (
      <span className={cx('inline-flex items-center gap-1.5 rounded-full bg-ok-soft px-2.5 py-1 text-xs font-semibold text-ok', className)}>
        <Dot tone="ok" /> Online
        {since != null ? <span className="hidden font-normal text-muted sm:inline">· {formatDuration((now - since) / 1000)}</span> : null}
      </span>
    );
  }
  return (
    <span
      className={cx('inline-flex items-center gap-1.5 rounded-full bg-danger-soft px-2.5 py-1 text-xs font-semibold text-danger', className)}
      title={snap.last_seen ?? undefined}
    >
      <Dot tone="danger" /> Offline
      <span className="font-normal text-muted">· seen {formatRelative(snap.last_seen, now)}</span>
    </span>
  );
}

export function ThemeToggle({ withLabel }: { withLabel?: boolean }) {
  const theme = useUi((s) => s.theme);
  const toggle = useUi((s) => s.toggleTheme);
  const next = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      type="button"
      onClick={toggle}
      className="inline-flex min-h-10 min-w-10 items-center justify-center gap-2 rounded-xl px-2 text-muted hover:bg-surface-2 hover:text-fg"
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
    >
      {theme === 'dark' ? <IconSun className="text-lg" /> : <IconMoon className="text-lg" />}
      {withLabel ? <span className="text-sm">{theme === 'dark' ? 'Light theme' : 'Dark theme'}</span> : null}
    </button>
  );
}

function DeviceSwitcher() {
  const devices = useData((s) => s.devices);
  const deviceId = useUi((s) => s.deviceId);
  const setDeviceId = useUi((s) => s.setDeviceId);
  const snap = useSnapshot();
  const name = snap?.name ?? devices.find((d) => d.device_id === deviceId)?.name ?? 'No phone paired';
  if (devices.length <= 1) {
    return (
      <span className="flex min-w-0 items-center gap-2 font-semibold text-fg">
        <IconPhone className="shrink-0 text-muted" />
        <span className="truncate">{name}</span>
      </span>
    );
  }
  return (
    <label className="flex min-w-0 items-center gap-2">
      <span className="sr-only">Phone</span>
      <Select value={deviceId ?? ''} onChange={(e) => setDeviceId(e.target.value || null)} className="max-w-56 py-1.5 font-semibold">
        {devices.map((d) => (
          <option key={d.device_id} value={d.device_id}>
            {d.name}
            {d.online ? '' : ' (offline)'}
          </option>
        ))}
      </Select>
    </label>
  );
}

export function TopBar() {
  const user = useSession((s) => s.user);
  const logout = useSession((s) => s.logout);
  return (
    <header className="safe-top sticky top-0 z-30 border-b border-border bg-bg/90 backdrop-blur">
      <div className="mx-auto flex min-h-14 max-w-6xl items-center gap-3 px-4">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1 py-2">
          <DeviceSwitcher />
          <OnlinePill />
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <ThemeToggle />
          {user ? (
            <div className="hidden items-center gap-2 pl-1 lg:flex">
              <span className="text-sm text-muted">{user.username}</span>
              <Badge tone={user.role === 'admin' ? 'info' : 'neutral'}>{user.role}</Badge>
              <button
                type="button"
                onClick={() => logout()}
                className="inline-flex min-h-10 min-w-10 items-center justify-center rounded-xl text-muted hover:bg-surface-2 hover:text-fg"
                aria-label="Sign out"
                title="Sign out"
              >
                <IconLogout className="text-lg" />
              </button>
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}
