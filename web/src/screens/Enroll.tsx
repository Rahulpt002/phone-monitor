import { TotpEnrollment } from '../components/TotpEnrollment';
import { ThemeToggle } from '../components/layout/TopBar';
import { Button } from '../components/ui/primitives';
import { useDocumentTitle } from '../lib/hooks';
import { useSession } from '../store/session';

/**
 * Plan 10: password plus TOTP for every Dashboard login (admins and viewers). Shown instead of the
 * app until the signed-in user has enrolled; the Server answers nothing else for that session.
 * Outside the app shell: no dashboard socket and no device data load here. App.tsx (EnrollRoute)
 * leaves this page once enrollment is done.
 */
export function EnrollScreen() {
  useDocumentTitle('Set up two-factor authentication');
  const username = useSession((s) => s.user?.username);
  const logout = useSession((s) => s.logout);
  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <div className="flex items-center justify-end gap-2 p-3">
        {username ? <span className="text-sm text-muted">{username}</span> : null}
        <Button size="sm" variant="ghost" onClick={() => logout()}>
          Sign out
        </Button>
        <ThemeToggle />
      </div>
      <main className="flex flex-1 items-start justify-center px-4 pb-16 pt-2 sm:pt-6">
        <div className="w-full max-w-xl">
          <div className="mb-6 flex items-center gap-3">
            <img src="/favicon.svg" alt="" className="size-10 rounded-xl" />
            <div>
              <h1 className="text-xl font-semibold text-fg">Set up two-factor authentication</h1>
              <p className="text-sm text-muted">Every account needs it before the Dashboard opens.</p>
            </div>
          </div>
          <div className="rounded-2xl border border-border bg-surface p-5 shadow-sm">
            <p className="mb-4 text-sm text-fg">
              The Dashboard shows the phone’s location, network details and controls, so signing in takes your password and a code from an
              authenticator app. Set it up once now; until then nothing else is available for this account.
            </p>
            <TotpEnrollment />
          </div>
        </div>
      </main>
    </div>
  );
}
