/**
 * Confirmation dialog that asks for the signed-in admin's own password (plan 10: changes that could
 * take over accounts or run root commands need the password again). The Server checks it; a wrong
 * one answers 401 reauth_required, which keeps the dialog open and never ends the session.
 */
import { useEffect, useId, useState, type ReactNode } from 'react';
import { errorMessage, isApiError } from '../../lib/api';
import { Dialog } from '../ui/Dialog';
import { Field, TextInput } from '../ui/forms';
import { Button } from '../ui/primitives';

export function reauthErrorMessage(e: unknown): string {
  if (isApiError(e, 'reauth_required')) return 'Wrong password. Try again.';
  return errorMessage(e);
}

export function ReauthDialog({
  open,
  title,
  description,
  confirmLabel,
  danger,
  onClose,
  onConfirm,
  children,
}: {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  onClose: () => void;
  /** Makes the change with the password. Throw to keep the dialog open with the error shown. */
  onConfirm: (password: string) => Promise<void>;
  /** Extra fields shown above the password (they come first for focus). */
  children?: ReactNode;
}) {
  const formId = useId();
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A fresh, empty form each time it opens; the password never outlives the dialog.
  useEffect(() => {
    setPassword('');
    setError(null);
    setBusy(false);
  }, [open]);

  const submit = async () => {
    if (busy) return;
    if (!password) {
      setError('Enter your password to confirm.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onConfirm(password);
      setPassword('');
    } catch (e) {
      setError(reauthErrorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant={danger ? 'danger' : 'primary'} type="submit" form={formId} busy={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        className="space-y-3"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        {children}
        <Field label="Your password" help="Your own password, to confirm it is you." error={error}>
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
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}
