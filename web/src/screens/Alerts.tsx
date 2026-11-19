import { ALERT_KINDS, type AlertRecord, type PublicConfig } from '@hsm/shared';
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { SEVERITY_TONE } from '../components/live';
import { Field, Select } from '../components/ui/forms';
import { Badge, Button, Card, Empty, Notice, PageHeader } from '../components/ui/primitives';
import { usePagedList } from '../components/usePagedList';
import { api, errorMessage } from '../lib/api';
import { formatDateTime, formatRelative } from '../lib/format';
import { useDocumentTitle, useNow } from '../lib/hooks';
import { disablePush, enablePush, pushStatus, type PushStatus } from '../lib/push';
import { alertKindLabel } from '../lib/settingsSchema';
import { useData } from '../store/data';
import { useCanControl, useIsAdmin } from '../store/selectors';
import { toast } from '../store/toasts';

function PushPanel({ config }: { config: PublicConfig | null }) {
  const [status, setStatus] = useState<PushStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    void pushStatus().then(setStatus);
  }, []);
  useEffect(refresh, [refresh]);

  const toggle = async (on: boolean) => {
    setBusy(true);
    setError(null);
    try {
      if (on) await enablePush();
      else await disablePush();
      toast.success(on ? 'Push notifications are on for this browser.' : 'Push notifications are off for this browser.');
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  return (
    <Card title="Push notifications on this device">
      <div className="space-y-3 text-sm">
        {config && !config.push_configured ? <Notice tone="warn">The server has no Web Push keys configured, so push alerts cannot be delivered.</Notice> : null}
        {status === 'unsupported' ? <p className="text-muted">This browser does not support push notifications.</p> : null}
        {status === 'needs_install' ? (
          <Notice tone="info">On iPhone and iPad, add this app to the Home Screen (Share &gt; Add to Home Screen) and open it from there to get push alerts.</Notice>
        ) : null}
        {status === 'denied' ? <Notice tone="warn">Notifications are blocked for this site. Allow them in the browser’s site settings, then come back.</Notice> : null}
        {status === 'on' || status === 'off' ? (
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone={status === 'on' ? 'ok' : 'neutral'}>{status === 'on' ? 'On' : 'Off'}</Badge>
            {status === 'on' ? (
              <Button busy={busy} onClick={() => toggle(false)}>
                Turn off
              </Button>
            ) : (
              <Button variant="primary" busy={busy} onClick={() => toggle(true)}>
                Turn on push alerts
              </Button>
            )}
          </div>
        ) : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
        <p className="text-xs text-muted">
          Telegram: {config ? (config.telegram_configured ? 'configured on the server' : 'not configured on the server') : 'checking…'}. Which alerts are sent and on which channels is set in{' '}
          <Link to="/settings?tab=device&section=alerts" className="text-accent underline">
            Settings &gt; Alerts
          </Link>
          .
        </p>
      </div>
    </Card>
  );
}

const PAGE = 100;

export function AlertsScreen() {
  useDocumentTitle('Alerts');
  const isAdmin = useIsAdmin();
  const canControl = useCanControl();
  const live = useData((s) => s.alerts);
  const now = useNow(15_000);
  const [kind, setKind] = useState('');
  const [config, setConfig] = useState<PublicConfig | null>(null);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    api.config().then(setConfig).catch(() => setConfig(null));
  }, []);

  const list = usePagedList<AlertRecord>({
    fetchPage: (before, signal) => api.alerts({ limit: PAGE, before }, signal),
    resetKey: 'alerts',
    live,
    keyOf: (a) => String(a.id),
    timeOf: (a) => a.ts,
    pageSize: PAGE,
  });
  const rows = list.items.filter((a) => !kind || a.kind === kind);

  const sendTest = async () => {
    setTesting(true);
    try {
      const rec = await api.testAlert();
      if (rec && typeof rec === 'object' && 'id' in rec) useData.getState().mergeAlerts([rec]);
      toast.success('Test alert sent on every configured channel.');
    } catch (e) {
      toast.error(`Test alert failed: ${errorMessage(e)}`);
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title="Alerts"
        subtitle="Everything the server told you about, newest first (plan 8.5)."
        actions={
          isAdmin ? (
            <Button onClick={sendTest} busy={testing} disabled={!canControl} title={!canControl ? 'Set up two-factor authentication first' : undefined}>
              Send test alert
            </Button>
          ) : null
        }
      />
      <PushPanel config={config} />
      <Card title="Alert history">
        <div className="mb-3 max-w-xs">
          <Field label="Kind">
            {({ id }) => (
              <Select id={id} value={kind} onChange={(e) => setKind(e.target.value)}>
                <option value="">All alerts</option>
                {ALERT_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {alertKindLabel(k)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        {list.error ? <Notice tone="danger">{list.error}</Notice> : null}
        {rows.length ? (
          <ul className="divide-y divide-border">
            {rows.map((a) => (
              <li key={a.id} className="py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={SEVERITY_TONE[a.severity] ?? 'neutral'}>{a.severity}</Badge>
                  <span className="text-sm font-semibold text-fg">{a.title}</span>
                  <span className="ml-auto text-xs text-faint" title={formatDateTime(a.ts)}>
                    {formatRelative(a.ts, now)}
                  </span>
                </div>
                <p className="mt-1 text-sm text-fg break-anywhere">{a.message}</p>
                <p className="mt-1 text-xs text-muted">
                  {alertKindLabel(a.kind)} · {formatDateTime(a.ts)} · {a.channels?.length ? `sent via ${a.channels.join(' and ')}` : 'not delivered to any channel'}
                </p>
              </li>
            ))}
          </ul>
        ) : !list.loading ? (
          <Empty>No alerts{kind ? ' of this kind' : ''}.</Empty>
        ) : null}
        <div className="mt-3">
          {list.hasMore ? (
            <Button size="sm" busy={list.loading} onClick={() => void list.loadMore()}>
              Load older
            </Button>
          ) : (
            <span className="text-xs text-faint">{list.loading ? 'Loading…' : 'No older alerts'}</span>
          )}
        </div>
      </Card>
    </div>
  );
}
