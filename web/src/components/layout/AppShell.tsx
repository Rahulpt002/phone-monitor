import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { startSocket } from '../../store/conn';
import { useData } from '../../store/data';
import { useSession } from '../../store/session';
import { CommandRunnerProvider } from '../commands/CommandRunner';
import { Button, Loading, Notice } from '../ui/primitives';
import { ConnectionBanner, TotpBanner } from './Banners';
import { BottomTabs, SideNav } from './Navigation';
import { NoPhonePaired, usableWithoutPhone } from './NoPhone';
import { ScreenBoundary } from './RouteBoundary';
import { TopBar } from './TopBar';
import { useDataLoader } from './useDataLoader';

export function AppShell() {
  const token = useSession((s) => s.token);
  const loader = useDataLoader();

  useEffect(() => {
    if (token) startSocket(token);
  }, [token]);

  return (
    <CommandRunnerProvider>
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[70] focus:rounded-lg focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-fg"
      >
        Skip to content
      </a>
      <SideNav />
      <div className="min-h-dvh lg:pl-60">
        <TopBar />
        <ConnectionBanner />
        <TotpBanner />
        <main id="main" tabIndex={-1} className="mx-auto max-w-6xl px-4 pb-28 pt-4 outline-none lg:pb-12">
          {loader.error ? (
            <div className="mb-4">
              <Notice
                tone="warn"
                action={
                  <Button size="sm" onClick={loader.retry}>
                    Retry
                  </Button>
                }
              >
                {loader.error}
              </Notice>
            </div>
          ) : null}
          <MainContent loaded={loader.devicesLoaded} />
        </main>
      </div>
      <BottomTabs />
    </CommandRunnerProvider>
  );
}

export function MainContent({ loaded }: { loaded: boolean }) {
  const devices = useData((s) => s.devices);
  const { pathname } = useLocation();
  if (!loaded && !devices.length) {
    return <Loading label="Connecting to the server (a sleeping free server can take up to a minute)" />;
  }
  // The phone screens have nothing to show without a phone; Settings (pairing) and Alerts still work.
  if (loaded && !devices.length && !usableWithoutPhone(pathname)) return <NoPhonePaired />;
  return (
    <ScreenBoundary>
      <Outlet />
    </ScreenBoundary>
  );
}
