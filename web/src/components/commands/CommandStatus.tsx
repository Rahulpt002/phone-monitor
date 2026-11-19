import type { CommandRecord } from '@hsm/shared';
import { useState } from 'react';
import { api, errorMessage } from '../../lib/api';
import { STATUS_META, describeCommand, revertExplanation } from '../../lib/commandMeta';
import { formatCountdown, formatDateTime, formatRelative, toMs } from '../../lib/format';
import { useNow } from '../../lib/hooks';
import { useData } from '../../store/data';
import { useCanControl } from '../../store/selectors';
import { toast } from '../../store/toasts';
import { Badge, Button, cx } from '../ui/primitives';

export function StatusBadge({ status }: { status: CommandRecord['status'] }) {
  const m = STATUS_META[status] ?? { label: status, tone: 'neutral' as const };
  return <Badge tone={m.tone}>{m.label}</Badge>;
}

/** "Last result" line under a control: status, when, who, and the output or error. */
export function LastResult({ cmd, compact }: { cmd: CommandRecord | undefined; compact?: boolean }) {
  const now = useNow(5000);
  const [open, setOpen] = useState(false);
  if (!cmd) return <p className="text-xs text-faint">Not run yet</p>;
  const text = cmd.error || cmd.output || '';
  const at = cmd.finished_at ?? cmd.acked_at ?? cmd.delivered_at ?? cmd.created_at;
  return (
    <div className="space-y-1 text-xs">
      <div className="flex flex-wrap items-center gap-2 text-muted">
        <StatusBadge status={cmd.status} />
        <span title={formatDateTime(at)}>{formatRelative(at, now)}</span>
        {cmd.user ? <span>by {cmd.user}</span> : <span>by {cmd.source}</span>}
        {cmd.duration_ms != null ? <span>{(cmd.duration_ms / 1000).toFixed(1)} s</span> : null}
      </div>
      {!compact && text ? (
        <div>
          <button type="button" className="text-accent underline-offset-2 hover:underline" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
            {open ? 'Hide output' : cmd.error ? 'Show error' : 'Show output'}
          </button>
          {open ? (
            <pre
              className={cx(
                'mt-1 max-h-64 overflow-auto rounded-lg bg-surface-2 p-2 font-mono text-[11px] leading-relaxed whitespace-pre-wrap break-anywhere',
                cmd.error ? 'text-danger' : 'text-fg',
              )}
            >
              {text}
            </pre>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/** Auto-revert countdown with "Keep" for dashboard-confirmed changes (plan 6.4 rule 1). */
export function RevertCountdown({ cmd }: { cmd: CommandRecord }) {
  const now = useNow(1000);
  const canControl = useCanControl();
  const [busy, setBusy] = useState(false);
  const deadline = toMs(cmd.revert_deadline);
  const left = deadline == null ? null : deadline - now;
  const mode = cmd.revert_confirm ?? 'agent_link';
  const seconds = deadline != null && toMs(cmd.finished_at) != null ? Math.round((deadline - toMs(cmd.finished_at)!) / 1000) : 120;

  const keep = async () => {
    setBusy(true);
    try {
      const rec = await api.keepCommand(cmd.id);
      if (rec?.id) useData.getState().mergeCommands([rec]);
      toast.success(`Kept: ${describeCommand(cmd.name, cmd.args)}`);
    } catch (e) {
      toast.error(`Keep failed: ${errorMessage(e)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border border-warn/40 bg-warn-soft px-3 py-2.5" role="status">
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-fg">
          {describeCommand(cmd.name, cmd.args)}
          <span className="ml-2 tabular text-warn" aria-label="Time left before auto-revert">
            {left == null ? '' : left > 0 ? `reverts in ${formatCountdown(left)}` : 'reverting…'}
          </span>
        </p>
        <p className="mt-0.5 text-xs text-muted">{revertExplanation(mode, seconds)}</p>
      </div>
      {mode === 'dashboard' && canControl ? (
        <Button variant="primary" size="sm" busy={busy} onClick={keep} disabled={left != null && left <= 0}>
          Keep
        </Button>
      ) : null}
    </div>
  );
}
