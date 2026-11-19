/**
 * Shown instead of the phone screens while no phone is paired (GET /api/devices is empty;
 * device endpoints would answer 404 "No phone is paired yet"). Admins get the way to pair one,
 * viewers an explanation. Settings and Alerts stay usable.
 */
import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../../lib/hooks';
import { useCanControl, useIsAdmin } from '../../store/selectors';
import { IconPhone } from '../ui/icons';
import { Card, Notice, buttonClass } from '../ui/primitives';

/** Route prefixes that still work before a phone is paired. */
export const USABLE_WITHOUT_PHONE = ['/settings', '/alerts', '/enroll'];

export function usableWithoutPhone(pathname: string): boolean {
  return USABLE_WITHOUT_PHONE.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

export function NoPhonePaired() {
  useDocumentTitle('Pair a phone');
  const isAdmin = useIsAdmin();
  const canControl = useCanControl();
  return (
    <Card>
      <div className="mx-auto flex max-w-lg flex-col items-center gap-3 py-8 text-center">
        <IconPhone className="text-4xl text-muted" />
        <h1 className="text-xl font-semibold text-fg">No phone is paired yet</h1>
        {isAdmin ? (
          <>
            <p className="text-sm text-muted">
              Pair the hotspot phone to see its signal, data use, battery and heat here. You get a QR code to scan with the phone, or an ADB command for
              a phone with a dead screen.
            </p>
            <Link to="/settings?tab=pairing" className={buttonClass('primary', 'lg')}>
              Pair a phone
            </Link>
            {!canControl ? (
              <div className="w-full text-left">
                <Notice tone="warn">Set up two-factor authentication first: creating a pairing code is an admin change.</Notice>
              </div>
            ) : null}
          </>
        ) : (
          <p className="text-sm text-muted">
            An admin has to pair the hotspot phone before there is anything to show. This page updates by itself once a phone is paired.
          </p>
        )}
      </div>
    </Card>
  );
}
