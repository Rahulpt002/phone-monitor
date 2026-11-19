import { Link } from 'react-router-dom';
import { PendingReverts } from '../components/commands/PendingReverts';
import { CommandCard } from '../components/controls/CommandCard';
import { Notice, PageHeader } from '../components/ui/primitives';
import { GROUP_LABEL, GROUP_ORDER, visibleControls } from '../lib/controls';
import { useDocumentTitle } from '../lib/hooks';
import { useDeviceSettings, useSnapshot } from '../store/selectors';
import { useSession } from '../store/session';

export function ControlsScreen() {
  useDocumentTitle('Controls');
  const snap = useSnapshot();
  const { settings } = useDeviceSettings();
  const role = useSession((s) => s.user?.role);
  const totp = useSession((s) => s.totpEnrollmentRequired);
  const caps = snap?.state.capabilities;
  const groups = visibleControls({ role, capabilities: caps, settings, totpEnrollmentRequired: totp });
  const hidden = caps ? Object.entries(caps).filter(([, c]) => c.supported === false) : [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Controls"
        subtitle="Every action is audited. Changes that can cut the phone's own link undo themselves unless confirmed (plan 6.4)."
      />
      {totp ? (
        <Notice tone="warn" title="Controls are locked" action={<Link className="font-semibold text-accent underline" to="/enroll">Set up 2FA</Link>}>
          Admins need two-factor authentication before sending commands.
        </Notice>
      ) : null}
      {snap && !snap.online ? (
        <Notice tone="warn" title="The phone is offline">
          Commands are queued and delivered when it reconnects, unless they expire first ({settings.commands.expiry_s} s).
        </Notice>
      ) : null}
      {!caps ? (
        <Notice tone="info">The phone has not reported its capability map yet, so every control is shown. Unsupported ones are hidden once it does.</Notice>
      ) : null}
      <PendingReverts />

      {GROUP_ORDER.map((g) =>
        groups[g].length ? (
          <section key={g} aria-labelledby={`group-${g}`} className="space-y-3">
            <h2 id={`group-${g}`} className="text-lg font-semibold text-fg">
              {GROUP_LABEL[g]}
            </h2>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              {groups[g].map((name) => (
                <CommandCard key={name} name={name} />
              ))}
            </div>
          </section>
        ) : null,
      )}

      {hidden.length ? (
        <details className="rounded-2xl border border-border bg-surface p-4 text-sm">
          <summary className="cursor-pointer font-medium text-fg">Hidden: not supported on this phone ({hidden.length})</summary>
          <ul className="mt-3 space-y-1 text-muted">
            {hidden.map(([name, c]) => (
              <li key={name}>
                <span className="font-mono text-fg">{name}</span>
                {c.note ? ` — ${c.note}` : ''}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}
