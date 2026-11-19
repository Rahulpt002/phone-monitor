import { Link, Navigate, useNavigate } from 'react-router-dom';
import { TotpEnrollment } from '../components/TotpEnrollment';
import { Card, PageHeader } from '../components/ui/primitives';
import { useDocumentTitle } from '../lib/hooks';
import { useSession } from '../store/session';

/** Shown to admins without TOTP right after login (plan 10: password plus TOTP second factor). */
export function EnrollScreen() {
  useDocumentTitle('Set up two-factor authentication');
  const navigate = useNavigate();
  const required = useSession((s) => s.totpEnrollmentRequired);
  const enabled = useSession((s) => s.user?.totp_enabled);
  if (!required && enabled) return <Navigate to="/" replace />;
  return (
    <div className="mx-auto max-w-xl">
      <PageHeader
        title="Set up two-factor authentication"
        subtitle="This account can control the phone with root access. A second factor is required before any control or settings change."
      />
      <Card>
        <TotpEnrollment onDone={() => navigate('/', { replace: true })} />
      </Card>
      <p className="mt-4 text-sm text-muted">
        <Link to="/" className="text-accent underline underline-offset-2">
          Later
        </Link>{' '}
        — you can look around, but controls and settings stay locked until this is done.
      </p>
    </div>
  );
}
