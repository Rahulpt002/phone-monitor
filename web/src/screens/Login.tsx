import { useState } from 'react';
import { Field, TextInput } from '../components/ui/forms';
import { Button, Notice } from '../components/ui/primitives';
import { ThemeToggle } from '../components/layout/TopBar';
import { api, errorMessage, isApiError } from '../lib/api';
import { useDocumentTitle } from '../lib/hooks';
import { useSession } from '../store/session';

export function LoginScreen() {
  useDocumentTitle('Sign in');
  const notice = useSession((s) => s.notice);
  const [step, setStep] = useState<'password' | 'totp'>('password');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Once the session is set, LoginRoute (App.tsx) redirects to the app or to TOTP enrolment.
  const finish = (token: string, user: Parameters<ReturnType<typeof useSession.getState>['setSession']>[1]) =>
    useSession.getState().setSession(token, user);

  const loginError = (e: unknown) => {
    if (isApiError(e, 'unauthorized')) return step === 'totp' ? 'That code is not valid. Try the current one.' : 'Wrong username or password.';
    if (isApiError(e, 'locked')) return errorMessage(e) || 'Too many failed attempts. Wait 15 minutes and try again.';
    return errorMessage(e);
  };

  const submitPassword = async () => {
    if (!username.trim() || !password) {
      setError('Enter your username and password.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await api.login({ username: username.trim(), password });
      if (res.totp_required) {
        setPending(res.pending_token);
        setStep('totp');
        setCode('');
      } else {
        await finish(res.token, res.user);
      }
    } catch (e) {
      setError(loginError(e));
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async () => {
    const clean = code.replace(/\s+/g, '');
    if (!pending) return;
    if (!/^\d{6}$/.test(clean)) {
      setError('Enter the 6-digit code from your authenticator app.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await api.totpLogin(pending, clean);
      await finish(res.token, res.user);
    } catch (e) {
      if (isApiError(e, 'unauthorized') && /expired/i.test(errorMessage(e))) {
        setStep('password');
        setPending(null);
      }
      setError(loginError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <div className="flex justify-end p-3">
        <ThemeToggle />
      </div>
      <main className="flex flex-1 items-start justify-center px-4 pb-16 pt-6 sm:items-center">
        <div className="w-full max-w-sm">
          <div className="mb-6 flex items-center gap-3">
            <img src="/favicon.svg" alt="" className="size-10 rounded-xl" />
            <div>
              <h1 className="text-xl font-semibold text-fg">Hotspot Monitor</h1>
              <p className="text-sm text-muted">{step === 'password' ? 'Sign in to continue' : 'Two-factor authentication'}</p>
            </div>
          </div>
          <div className="rounded-2xl border border-border bg-surface p-5 shadow-sm">
            {notice && step === 'password' ? (
              <div className="mb-4">
                <Notice tone="warn">{notice}</Notice>
              </div>
            ) : null}
            {step === 'password' ? (
              <form
                className="space-y-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitPassword();
                }}
                noValidate
              >
                <Field label="Username">
                  {({ id }) => (
                    <TextInput
                      id={id}
                      autoComplete="username"
                      autoCapitalize="none"
                      spellCheck={false}
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      autoFocus
                    />
                  )}
                </Field>
                <Field label="Password">
                  {({ id }) => (
                    <TextInput id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                  )}
                </Field>
                {error ? <Notice tone="danger">{error}</Notice> : null}
                <Button type="submit" variant="primary" size="lg" className="w-full" busy={busy}>
                  Sign in
                </Button>
              </form>
            ) : (
              <form
                className="space-y-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitCode();
                }}
                noValidate
              >
                <Field label="6-digit code from your authenticator app">
                  {({ id }) => (
                    <TextInput
                      id={id}
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={7}
                      value={code}
                      onChange={(e) => setCode(e.target.value)}
                      className="text-center font-mono text-2xl tracking-[0.3em]"
                      autoFocus
                    />
                  )}
                </Field>
                {error ? <Notice tone="danger">{error}</Notice> : null}
                <Button type="submit" variant="primary" size="lg" className="w-full" busy={busy}>
                  Verify
                </Button>
                <Button
                  variant="ghost"
                  className="w-full"
                  onClick={() => {
                    setStep('password');
                    setPending(null);
                    setError(null);
                  }}
                >
                  Back
                </Button>
              </form>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
