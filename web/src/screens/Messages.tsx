import { useMemo, useState } from 'react';
import { useRunCommand } from '../components/commands/CommandRunner';
import { StatusBadge } from '../components/commands/CommandStatus';
import { Field, Segmented, TextArea, TextInput } from '../components/ui/forms';
import { Badge, Button, Card, Empty, Notice, PageHeader, cx } from '../components/ui/primitives';
import { formatDateTime, formatRelative } from '../lib/format';
import { useDocumentTitle, useNow } from '../lib/hooks';
import { useCanControl, useCommand, useDeviceCommands, useDeviceSms } from '../store/selectors';

/** GSM-7 messages split at 153 chars per part, anything else (UCS-2) at 67. */
export function smsSegments(body: string): { parts: number; perPart: number } {
  // eslint-disable-next-line no-control-regex
  const gsm = /^[\x0A\x0D\x20-\x5F\x61-\x7E£¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ¡ÄÖÑÜ§¿äöñüà]*$/.test(body);
  const single = gsm ? 160 : 70;
  const multi = gsm ? 153 : 67;
  if (body.length <= single) return { parts: body.length ? 1 : 0, perPart: single };
  return { parts: Math.ceil(body.length / multi), perPart: multi };
}

function Inbox() {
  const sms = useDeviceSms();
  const now = useNow(30_000);
  const [dir, setDir] = useState<'all' | 'in' | 'out'>('all');
  const list = sms.filter((m) => dir === 'all' || m.direction === dir);
  return (
    <Card title="SMS inbox" actions={<Segmented label="Direction" value={dir} onChange={setDir} options={[{ value: 'all', label: 'All' }, { value: 'in', label: 'Received' }, { value: 'out', label: 'Sent' }]} />}>
      {list.length ? (
        <ul className="divide-y divide-border">
          {list.map((m) => (
            <li key={m.id} className="py-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={m.direction === 'in' ? 'info' : 'neutral'}>{m.direction === 'in' ? 'From' : 'To'}</Badge>
                <span className="font-mono text-sm font-semibold text-fg">{m.peer}</span>
                <span className="ml-auto text-xs text-faint" title={formatDateTime(m.at)}>
                  {formatRelative(m.at, now)}
                </span>
              </div>
              <p className="mt-1 text-sm whitespace-pre-wrap text-fg break-anywhere">{m.body}</p>
            </li>
          ))}
        </ul>
      ) : (
        <Empty>No messages. Carrier messages about plan expiry and quota arrive here (and as alerts).</Empty>
      )}
    </Card>
  );
}

function SendSms() {
  const run = useRunCommand();
  const canControl = useCanControl();
  const [to, setTo] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [touched, setTouched] = useState(false);
  const [sentId, setSentId] = useState<string | null>(null);
  const sent = useCommand(sentId);
  const toErr = !/^\+?[0-9]{3,15}$/.test(to.replace(/[\s-]/g, '')) ? 'Enter a phone number, e.g. +919876543210.' : null;
  const bodyErr = !body.trim() ? 'Write a message.' : body.length > 918 ? 'At most 918 characters (6 parts).' : null;
  const seg = smsSegments(body);

  return (
    <Card title="Send SMS">
      <form
        className="space-y-3"
        onSubmit={async (e) => {
          e.preventDefault();
          setTouched(true);
          if (toErr || bodyErr) return;
          setBusy(true);
          try {
            const rec = await run('send_sms', { to: to.replace(/[\s-]/g, ''), body }, { detail: <p className="whitespace-pre-wrap break-anywhere">“{body}”</p> });
            if (rec) {
              setSentId(rec.id);
              setBody('');
              setTouched(false);
            }
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="To" error={touched ? toErr : null}>
          {({ id, describedBy, invalid }) => (
            <TextInput id={id} type="tel" inputMode="tel" autoComplete="off" value={to} onChange={(e) => setTo(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={!canControl} />
          )}
        </Field>
        <Field label="Message" error={touched ? bodyErr : null} help={`${body.length} characters · ${seg.parts} part${seg.parts === 1 ? '' : 's'}`}>
          {({ id, describedBy, invalid }) => (
            <TextArea id={id} value={body} onChange={(e) => setBody(e.target.value)} rows={4} aria-describedby={describedBy} aria-invalid={invalid} disabled={!canControl} />
          )}
        </Field>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" variant="primary" busy={busy} disabled={!canControl}>
            Send
          </Button>
          {sent ? (
            <span className="flex items-center gap-2 text-sm text-muted">
              Last message: <StatusBadge status={sent.status} />
              {sent.error ? <span className="text-danger">{sent.error}</span> : null}
            </span>
          ) : null}
        </div>
      </form>
    </Card>
  );
}

function Ussd() {
  const run = useRunCommand();
  const canControl = useCanControl();
  const commands = useDeviceCommands();
  const now = useNow(30_000);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [lastId, setLastId] = useState<string | null>(null);
  const current = useCommand(lastId);
  const valid = /^[*#][0-9*#]{1,30}#$/.test(code.trim());
  const history = useMemo(() => commands.filter((c) => c.name === 'ussd').slice(0, 10), [commands]);

  return (
    <Card title="USSD">
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!valid) return;
          setBusy(true);
          try {
            const rec = await run('ussd', { code: code.trim() }, { quiet: true });
            if (rec) setLastId(rec.id);
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="Code" error={code && !valid ? 'Like *123# or *121*1#' : null} className="w-48">
          {({ id, describedBy, invalid }) => (
            <TextInput id={id} inputMode="tel" placeholder="*123#" value={code} onChange={(e) => setCode(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={!canControl} className="font-mono" />
          )}
        </Field>
        <Button type="submit" variant="primary" busy={busy} disabled={!canControl || !valid}>
          Run
        </Button>
      </form>
      {current ? (
        <div className="mt-4 space-y-2" aria-live="polite">
          <div className="flex items-center gap-2 text-sm">
            <span className="font-mono">{String(current.args.code ?? '')}</span>
            <StatusBadge status={current.status} />
          </div>
          {current.output || current.error ? (
            <pre className={cx('rounded-xl bg-surface-2 p-3 text-sm whitespace-pre-wrap break-anywhere', current.error ? 'text-danger' : 'text-fg')}>{current.error || current.output}</pre>
          ) : ['queued', 'sent', 'acked'].includes(current.status) ? (
            <p className="text-sm text-muted">Waiting for the network’s reply…</p>
          ) : null}
        </div>
      ) : null}
      {history.length ? (
        <div className="mt-4">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-muted">Recent codes</h3>
          <ul className="mt-2 divide-y divide-border">
            {history.map((c) => (
              <li key={c.id} className="py-2 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono">{String(c.args.code ?? '')}</span>
                  <StatusBadge status={c.status} />
                  <span className="ml-auto text-xs text-faint">{formatRelative(c.created_at, now)}</span>
                </div>
                {c.output ? <p className="mt-1 text-xs whitespace-pre-wrap text-muted break-anywhere">{c.output}</p> : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </Card>
  );
}

export function MessagesScreen() {
  useDocumentTitle('Messages');
  const canControl = useCanControl();
  return (
    <div className="space-y-4">
      <PageHeader title="Messages" subtitle="The SIM's text messages and USSD codes (balance and plan checks)." />
      {!canControl ? <Notice tone="warn">Set up two-factor authentication to send messages or run codes.</Notice> : null}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Inbox />
        <div className="space-y-4">
          <SendSms />
          <Ussd />
        </div>
      </div>
    </div>
  );
}
