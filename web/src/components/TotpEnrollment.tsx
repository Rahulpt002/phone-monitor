import type { TotpSetupResponse } from '@hsm/shared';
import { useState } from 'react';
import { api, errorMessage } from '../lib/api';
import { useSession } from '../store/session';
import { toast } from '../store/toasts';
import { CopyButton } from './ui/CopyButton';
import { Field, TextInput } from './ui/forms';
import { Button, Notice } from './ui/primitives';
import { QrCode } from './ui/QrCode';

/** Guided TOTP enrolment: QR (otpauth URL) or manual secret, then verify a code. */
export function TotpEnrollment({ onDone }: { onDone?: () => void }) {
  const [setup, setSetup] = useState<TotpSetupResponse | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      setSetup(await api.totpSetup());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const verify = async () => {
    const clean = code.replace(/\s+/g, '');
    if (!/^\d{6}$/.test(clean)) {
      setError('Enter the 6-digit code from your authenticator app.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.totpEnable(clean);
      useSession.getState().setTotpEnrollmentRequired(false);
      await useSession.getState().refreshMe();
      toast.success('Two-factor authentication is on.');
      onDone?.();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (!setup) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-muted">
          You will need an authenticator app such as Google Authenticator, Aegis, 1Password or Authy. After this, signing in asks for a
          6-digit code as well as your password.
        </p>
        {error ? <Notice tone="danger">{error}</Notice> : null}
        <Button variant="primary" busy={busy} onClick={start}>
          Start setup
        </Button>
      </div>
    );
  }

  const grouped = setup.secret.replace(/(.{4})/g, '$1 ').trim();
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        void verify();
      }}
    >
      <ol className="list-decimal space-y-3 pl-5 text-sm">
        <li>
          <p className="mb-2">Scan this QR code with your authenticator app.</p>
          <QrCode value={setup.otpauth_url} label="QR code for your authenticator app" size={200} />
        </li>
        <li>
          <p>Or type this secret into the app manually:</p>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <code className="rounded-lg bg-surface-2 px-2 py-1 font-mono text-sm tracking-wider break-anywhere">{grouped}</code>
            <CopyButton text={setup.secret} label="Copy secret" />
          </div>
        </li>
        <li>
          <Field label="Enter the 6-digit code the app shows" error={error}>
            {({ id, describedBy, invalid }) => (
              <TextInput
                id={id}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9 ]*"
                maxLength={7}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                className="max-w-40 text-center font-mono text-lg tracking-widest"
              />
            )}
          </Field>
        </li>
      </ol>
      <Button type="submit" variant="primary" busy={busy}>
        Verify and turn on
      </Button>
    </form>
  );
}
