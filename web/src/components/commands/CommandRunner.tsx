/**
 * Sends commands (POST /api/commands) with the plan 6.4 safety UX: confirmation dialog for
 * `confirm` commands, password prompt for `reauth` commands, auto-revert explanation, and errors
 * mapped to something a person can act on.
 */
import { COMMANDS, type CommandName, type CommandRecord } from '@hsm/shared';
import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, errorMessage, isApiError } from '../../lib/api';
import { COMMAND_HELP, DESTRUCTIVE, describeCommand, revertExplanation } from '../../lib/commandMeta';
import { useData } from '../../store/data';
import { useSession } from '../../store/session';
import { toast } from '../../store/toasts';
import { useUi } from '../../store/ui';
import { Dialog } from '../ui/Dialog';
import { Field, TextInput } from '../ui/forms';
import { Button, Notice } from '../ui/primitives';

export interface RunOptions {
  /** Extra line shown in the confirmation dialog. */
  detail?: ReactNode;
  /** Pre-filled password (root console keeps it for its session). */
  password?: string;
  /** Called with the password that worked, so the caller can keep it. */
  onPassword?: (pw: string) => void;
  /** Suppress the "sent" toast (callers that show their own progress). */
  quiet?: boolean;
}

type Run = (name: CommandName, args?: Record<string, unknown>, opts?: RunOptions) => Promise<CommandRecord | null>;

const RunnerContext = createContext<Run | null>(null);

export function useRunCommand(): Run {
  const run = useContext(RunnerContext);
  if (!run) throw new Error('useRunCommand must be used inside <CommandRunnerProvider>');
  return run;
}

interface Pending {
  name: CommandName;
  args: Record<string, unknown>;
  opts: RunOptions;
  resolve: (r: CommandRecord | null) => void;
}

export function CommandRunnerProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const pendingRef = useRef<Pending | null>(null);
  pendingRef.current = pending;

  const submit = useCallback(
    async (name: CommandName, args: Record<string, unknown>, opts: RunOptions, pw?: string): Promise<CommandRecord> => {
      const spec = COMMANDS[name];
      const deviceId = useUi.getState().deviceId;
      if (!deviceId) throw new Error('No phone is paired yet.');
      const rec = await api.sendCommand({
        device_id: deviceId,
        name,
        args,
        confirmed: spec.confirm ? true : undefined,
        password: spec.reauth ? pw : undefined,
      });
      if (rec && typeof rec === 'object' && 'id' in rec) useData.getState().mergeCommands([rec]);
      if (!opts.quiet) {
        const offline = useData.getState().snapshots[deviceId]?.online === false;
        if (offline) toast.warning(`${describeCommand(name, args)}: queued. The phone is offline; it expires if not delivered in time.`);
        else toast.success(`${describeCommand(name, args)}: sent`);
      }
      return rec;
    },
    [],
  );

  const handleError = useCallback(
    (e: unknown): string => {
      // The API client already switched the app to the enrollment page (plan 10).
      if (isApiError(e, 'totp_enrollment_required')) return 'Set up two-factor authentication first.';
      if (isApiError(e, 'reauth_required')) return 'Wrong password. Try again.';
      if (isApiError(e, 'forbidden')) return 'Your account is not allowed to send this command.';
      if (isApiError(e, 'device_offline')) return 'The phone is offline. The command is queued until it expires.';
      return errorMessage(e);
    },
    [],
  );

  const run: Run = useCallback(
    async (name, args = {}, opts = {}) => {
      const session = useSession.getState();
      const spec = COMMANDS[name];
      if (!spec) {
        toast.error(`Unknown command ${name}`);
        return null;
      }
      if (session.user?.role !== 'admin') {
        toast.error('Viewer accounts cannot send commands.');
        return null;
      }
      if (session.totpEnrollmentRequired) {
        toast.warning('Set up two-factor authentication before using controls.');
        return null;
      }
      if (spec.confirm || spec.reauth) {
        pendingRef.current?.resolve(null);
        setPassword(opts.password ?? '');
        setError(null);
        setBusy(false);
        return new Promise<CommandRecord | null>((resolve) => setPending({ name, args, opts, resolve }));
      }
      try {
        return await submit(name, args, opts);
      } catch (e) {
        toast.error(`${describeCommand(name, args)}: ${handleError(e)}`);
        return null;
      }
    },
    [submit, handleError],
  );

  const close = () => {
    pending?.resolve(null);
    setPending(null);
    setPassword('');
  };

  const confirm = async () => {
    if (!pending) return;
    const spec = COMMANDS[pending.name];
    if (spec.reauth && !password) {
      setError('Enter your password.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const rec = await submit(pending.name, pending.args, pending.opts, password);
      if (spec.reauth) pending.opts.onPassword?.(password);
      pending.resolve(rec);
      setPending(null);
      setPassword('');
    } catch (e) {
      const msg = handleError(e);
      if (isApiError(e, 'totp_enrollment_required') || isApiError(e, 'forbidden')) {
        toast.error(msg);
        pending.resolve(null);
        setPending(null);
      } else {
        setError(msg);
      }
    } finally {
      setBusy(false);
    }
  };

  const deviceId = useUi((s) => s.deviceId);
  const revertAfter = useData((s) => (deviceId && s.settings[deviceId]?.settings.commands.revert_after_s) || 120);
  const rebootInfo = useData((s) => {
    const id = deviceId;
    const set = id ? s.settings[id]?.settings.watchdog : undefined;
    const used = id ? s.snapshots[id]?.state.system?.reboots_in_window : undefined;
    return set ? `${used ?? '?'} of ${set.max_reboots} reboots used in the last ${set.reboot_window_h} h.` : null;
  });

  const spec = pending ? COMMANDS[pending.name] : null;
  const revertMode = pending && spec ? spec.revertFor(pending.args) : null;
  const destructive = pending ? DESTRUCTIVE.includes(pending.name) : false;

  return (
    <RunnerContext.Provider value={run}>
      {children}
      <Dialog
        open={Boolean(pending)}
        onClose={close}
        title={pending && spec ? (spec.reauth ? `Confirm with password: ${spec.label}` : `Confirm: ${spec.label}`) : ''}
        description={pending ? describeCommand(pending.name, pending.args) : undefined}
        footer={
          <>
            <Button onClick={close}>Cancel</Button>
            <Button variant={destructive ? 'danger' : 'primary'} busy={busy} type="submit" form="cmd-confirm-form">
              {spec?.label ?? 'Confirm'}
            </Button>
          </>
        }
      >
        {pending && spec ? (
          <form
            id="cmd-confirm-form"
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              void confirm();
            }}
          >
            {COMMAND_HELP[pending.name] ? <p className="text-sm text-muted">{COMMAND_HELP[pending.name]}</p> : null}
            {pending.opts.detail ? <div className="text-sm">{pending.opts.detail}</div> : null}
            {spec.is_reboot && rebootInfo ? <Notice tone="warn">{rebootInfo}</Notice> : null}
            {revertMode ? (
              <Notice tone="warn" title={`Auto-revert after ${revertAfter} s`}>
                {revertExplanation(revertMode, revertAfter)}
              </Notice>
            ) : null}
            {pending.name === 'shell' ? (
              <pre className="max-h-40 overflow-auto rounded-lg bg-surface-2 p-2 font-mono text-xs break-anywhere whitespace-pre-wrap">
                {String(pending.args.cmd ?? '')}
              </pre>
            ) : null}
            {spec.reauth ? (
              <Field label="Your password" error={error}>
                {({ id, describedBy, invalid }) => (
                  <TextInput
                    id={id}
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    aria-describedby={describedBy}
                    aria-invalid={invalid}
                    data-autofocus
                    required
                  />
                )}
              </Field>
            ) : error ? (
              <Notice tone="danger">{error}</Notice>
            ) : null}
          </form>
        ) : null}
      </Dialog>
    </RunnerContext.Provider>
  );
}

/** Convenience: run state for a single button (busy flag while the POST is in flight). */
export function useCommandAction() {
  const run = useRunCommand();
  const [busy, setBusy] = useState(false);
  const [lastId, setLastId] = useState<string | null>(null);
  const exec = useCallback(
    async (name: CommandName, args?: Record<string, unknown>, opts?: RunOptions) => {
      setBusy(true);
      try {
        const rec = await run(name, args, opts);
        if (rec) setLastId(rec.id);
        return rec;
      } finally {
        setBusy(false);
      }
    },
    [run],
  );
  return useMemo(() => ({ exec, busy, lastId }), [exec, busy, lastId]);
}
