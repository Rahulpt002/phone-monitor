import { useEffect, useState } from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';
import { formatCountdown } from '../../lib/format';
import { useNow } from '../../lib/hooks';
import { useConn } from '../../store/conn';
import { useToasts } from '../../store/toasts';
import { IconX } from '../ui/icons';
import { Button, Spinner, cx } from '../ui/primitives';

/** Shown while the live socket is down: live values may be stale. */
export function ConnectionBanner() {
  const status = useConn((s) => s.status);
  const nextRetryAt = useConn((s) => s.nextRetryAt);
  const downSince = useConn((s) => s.downSince);
  const retryNow = useConn((s) => s.retryNow);
  const now = useNow(1000);
  // Give the first connection a moment before alarming anyone.
  const [grace, setGrace] = useState(true);
  useEffect(() => {
    const t = setTimeout(() => setGrace(false), 2500);
    return () => clearTimeout(t);
  }, []);

  if (status === 'open' || status === 'idle') return null;
  if (grace && downSince == null) return null;

  const longDown = downSince != null && now - downSince > 20_000;
  return (
    <div role="status" aria-live="polite" className="border-b border-warn/30 bg-warn-soft">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-sm">
        {status === 'waiting' ? (
          <span className="font-medium text-warn">Live connection lost.</span>
        ) : (
          <span className="inline-flex items-center gap-2 font-medium text-warn">
            <Spinner /> Connecting to the server…
          </span>
        )}
        <span className="text-muted">
          {status === 'waiting' && nextRetryAt ? `Retrying in ${formatCountdown(nextRetryAt - now)}. ` : ''}
          {longDown || downSince == null ? 'A sleeping free server can take up to a minute to wake up. ' : ''}
          Values shown may be out of date.
        </span>
        {status === 'waiting' ? (
          <Button size="sm" onClick={retryNow}>
            Retry now
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/** "New version available" prompt and "ready offline" note from the service worker. */
export function UpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    offlineReady: [offlineReady, setOfflineReady],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(_url, reg) {
      // Check for a new version hourly while the app stays open (it often does on the monitoring phone).
      if (reg) setInterval(() => void reg.update().catch(() => undefined), 60 * 60 * 1000);
    },
  });

  useEffect(() => {
    if (!offlineReady) return;
    const t = setTimeout(() => setOfflineReady(false), 5000);
    return () => clearTimeout(t);
  }, [offlineReady, setOfflineReady]);

  if (!needRefresh && !offlineReady) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed inset-x-3 bottom-20 z-50 mx-auto flex max-w-md flex-wrap items-center gap-3 rounded-2xl border border-border bg-surface-2 px-4 py-3 shadow-xl lg:bottom-6"
    >
      <p className="min-w-0 flex-1 text-sm text-fg">
        {needRefresh ? 'A new version of the Dashboard is available.' : 'The Dashboard is ready to open offline.'}
      </p>
      {needRefresh ? (
        <>
          <Button size="sm" variant="primary" onClick={() => void updateServiceWorker(true)}>
            Reload
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setNeedRefresh(false)}>
            Later
          </Button>
        </>
      ) : (
        <Button size="sm" variant="ghost" onClick={() => setOfflineReady(false)}>
          OK
        </Button>
      )}
    </div>
  );
}

export function Toasts() {
  const toasts = useToasts((s) => s.toasts);
  const dismiss = useToasts((s) => s.dismiss);
  return (
    <div aria-live="polite" className="pointer-events-none fixed inset-x-3 top-16 z-[60] mx-auto flex max-w-md flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          role={t.kind === 'error' ? 'alert' : 'status'}
          className={cx(
            'pointer-events-auto flex items-start gap-2 rounded-xl border px-3 py-2.5 text-sm shadow-lg backdrop-blur',
            t.kind === 'error' && 'border-danger/40 bg-surface text-fg',
            t.kind === 'warning' && 'border-warn/40 bg-surface text-fg',
            t.kind === 'success' && 'border-ok/40 bg-surface text-fg',
            t.kind === 'info' && 'border-border bg-surface text-fg',
          )}
        >
          <span
            aria-hidden
            className={cx(
              'mt-1.5 size-2 shrink-0 rounded-full',
              t.kind === 'error' ? 'bg-danger' : t.kind === 'warning' ? 'bg-warn' : t.kind === 'success' ? 'bg-ok' : 'bg-info',
            )}
          />
          <p className="min-w-0 flex-1 break-anywhere">{t.message}</p>
          <button type="button" onClick={() => dismiss(t.id)} className="-m-1 rounded-lg p-1 text-muted hover:text-fg" aria-label="Dismiss">
            <IconX />
          </button>
        </div>
      ))}
    </div>
  );
}
