/**
 * One control from the command catalog: label, safety badges, capability note, its own argument
 * form, the last result, and an armed auto-revert countdown.
 */
import { COMMANDS, type CommandName, type CommandRecord, type DeviceSnapshot, type Settings } from '@hsm/shared';
import { useCallback, useState, type ReactNode } from 'react';
import { COMMAND_HELP } from '../../lib/commandMeta';
import { controlState } from '../../lib/controls';
import { useDeviceSettings, useLastCommands, usePendingReverts, useSnapshot } from '../../store/selectors';
import { useSession } from '../../store/session';
import { useRunCommand, type RunOptions } from '../commands/CommandRunner';
import { LastResult, RevertCountdown } from '../commands/CommandStatus';
import { Badge, cx } from '../ui/primitives';
import { COMMAND_FORMS, RunButton } from './forms';

export interface ControlBodyProps {
  name: CommandName;
  run: (args?: Record<string, unknown>, opts?: RunOptions) => Promise<CommandRecord | null>;
  busy: boolean;
  disabled: boolean;
  snapshot: DeviceSnapshot | undefined;
  settings: Settings;
  last: CommandRecord | undefined;
}

export function CommandCard({ name, compact, children }: { name: CommandName; compact?: boolean; children?: ReactNode }) {
  const spec = COMMANDS[name];
  const role = useSession((s) => s.user?.role);
  const totp = useSession((s) => s.totpEnrollmentRequired);
  const snapshot = useSnapshot();
  const { settings } = useDeviceSettings();
  const lastByName = useLastCommands();
  const pending = usePendingReverts();
  const runCommand = useRunCommand();
  const [busy, setBusy] = useState(false);

  const state = controlState(name, {
    role,
    capabilities: snapshot?.state.capabilities,
    settings,
    totpEnrollmentRequired: totp,
  });

  const run = useCallback(
    async (args: Record<string, unknown> = {}, opts?: RunOptions) => {
      setBusy(true);
      try {
        return await runCommand(name, args, opts);
      } finally {
        setBusy(false);
      }
    },
    [name, runCommand],
  );

  if (!state.visible) return null;
  const cap = snapshot?.state.capabilities?.[name];
  const last = lastByName.get(name);
  const armed = pending.filter((c) => c.name === name);
  const Body = COMMAND_FORMS[name];
  const props: ControlBodyProps = { name, run, busy, disabled: state.disabled, snapshot, settings, last };

  return (
    <article className={cx('flex min-w-0 flex-col gap-3 rounded-2xl border border-border bg-surface p-4', state.disabled && 'opacity-90')} aria-label={spec.label}>
      <header className="space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-base font-semibold text-fg">{spec.label}</h3>
          {spec.confirm ? <Badge>Confirm</Badge> : null}
          {spec.reauth ? <Badge tone="warn">Password</Badge> : null}
          {spec.admin_only ? <Badge tone="info">Admin</Badge> : null}
          {spec.revertFor({ enabled: false }) || spec.revertFor({ enabled: true }) || spec.revertFor({}) ? <Badge tone="warn">Auto-revert</Badge> : null}
        </div>
        {!compact && COMMAND_HELP[name] ? <p className="text-sm text-muted">{COMMAND_HELP[name]}</p> : null}
        {cap?.method || cap?.note ? (
          <p className="text-xs text-faint break-anywhere">
            {cap.method ? `Method: ${cap.method}` : ''}
            {cap.method && cap.note ? ' · ' : ''}
            {cap.note ?? ''}
          </p>
        ) : null}
      </header>
      {state.reason ? <p className="rounded-lg bg-surface-2 px-3 py-2 text-xs text-muted">{state.reason}</p> : null}
      <div>{children ?? (Body ? <Body {...props} /> : <RunButton {...props} />)}</div>
      {armed.map((c) => (
        <RevertCountdown key={c.id} cmd={c} />
      ))}
      <div className="mt-auto border-t border-border pt-2">
        <LastResult cmd={last} />
      </div>
    </article>
  );
}
