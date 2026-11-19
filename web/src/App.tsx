import { useEffect, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { Toasts, UpdatePrompt } from './components/layout/Banners';
import { AlertsScreen } from './screens/Alerts';
import { ControlsScreen } from './screens/Controls';
import { EnrollScreen } from './screens/Enroll';
import { FinderScreen } from './screens/Finder';
import { LoginScreen } from './screens/Login';
import { MessagesScreen } from './screens/Messages';
import { NetworkScreen } from './screens/Network';
import { OverviewScreen } from './screens/Overview';
import { PowerScreen } from './screens/Power';
import { SettingsScreen } from './screens/Settings';
import { SignalScreen } from './screens/Signal';
import { SystemScreen } from './screens/System';
import { useSession } from './store/session';

function RequireSession({ children }: { children: ReactNode }) {
  const phase = useSession((s) => s.phase);
  const location = useLocation();
  if (phase !== 'ready') return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** Plan 9: viewers do not see Controls, root shell or Messages. */
function AdminOnly({ children }: { children: ReactNode }) {
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  return isAdmin ? <>{children}</> : <Navigate to="/" replace />;
}

function LoginRoute() {
  const phase = useSession((s) => s.phase);
  const enroll = useSession((s) => s.totpEnrollmentRequired);
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from;
  if (phase === 'ready') return <Navigate to={enroll ? '/enroll' : from && from !== '/login' ? from : '/'} replace />;
  return <LoginScreen />;
}

/** Notification clicks ask an already open window to navigate (see sw.ts). */
function ServiceWorkerNavigation() {
  const navigate = useNavigate();
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;
    const onMessage = (e: MessageEvent) => {
      const data = e.data as { type?: string; url?: string } | null;
      if (data?.type === 'navigate' && typeof data.url === 'string' && data.url.startsWith('/')) {
        navigate(data.url);
      }
    };
    navigator.serviceWorker.addEventListener('message', onMessage);
    return () => navigator.serviceWorker.removeEventListener('message', onMessage);
  }, [navigate]);
  return null;
}

export function App() {
  useEffect(() => {
    // Validate a stored session in the background; a 401 sends the user back to the login screen.
    void useSession.getState().refreshMe();
  }, []);

  return (
    <BrowserRouter>
      <ServiceWorkerNavigation />
      <Routes>
        <Route path="/login" element={<LoginRoute />} />
        <Route
          element={
            <RequireSession>
              <AppShell />
            </RequireSession>
          }
        >
          <Route index element={<OverviewScreen />} />
          <Route path="signal" element={<SignalScreen />} />
          <Route path="network" element={<NetworkScreen />} />
          <Route path="power" element={<PowerScreen />} />
          <Route
            path="controls"
            element={
              <AdminOnly>
                <ControlsScreen />
              </AdminOnly>
            }
          />
          <Route path="system" element={<SystemScreen />} />
          <Route path="finder" element={<FinderScreen />} />
          <Route
            path="messages"
            element={
              <AdminOnly>
                <MessagesScreen />
              </AdminOnly>
            }
          />
          <Route path="alerts" element={<AlertsScreen />} />
          <Route path="settings" element={<SettingsScreen />} />
          <Route path="enroll" element={<EnrollScreen />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
      <Toasts />
      <UpdatePrompt />
    </BrowserRouter>
  );
}
