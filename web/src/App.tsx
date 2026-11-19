import { lazy, useEffect, type ComponentType, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { Toasts, UpdatePrompt } from './components/layout/Banners';
import { LoginScreen } from './screens/Login';
import { OverviewScreen } from './screens/Overview';
import { useSession } from './store/session';

/**
 * Every screen except Login and Overview (the first paint) is its own chunk, so history charts
 * (uPlot), Signal Finder, the admin screens, the root console and QR codes load on first use.
 * The service worker precaches all chunks (vite.config.ts globPatterns), so they work offline.
 * AppShell wraps the routes in Suspense and an error boundary (RouteBoundary.tsx).
 */
function lazyScreen<M>(load: () => Promise<M>, pick: (m: M) => ComponentType) {
  return lazy(async () => ({ default: pick(await load()) }));
}
const SignalScreen = lazyScreen(() => import('./screens/Signal'), (m) => m.SignalScreen);
const NetworkScreen = lazyScreen(() => import('./screens/Network'), (m) => m.NetworkScreen);
const PowerScreen = lazyScreen(() => import('./screens/Power'), (m) => m.PowerScreen);
const ControlsScreen = lazyScreen(() => import('./screens/Controls'), (m) => m.ControlsScreen);
const SystemScreen = lazyScreen(() => import('./screens/System'), (m) => m.SystemScreen);
const FinderScreen = lazyScreen(() => import('./screens/Finder'), (m) => m.FinderScreen);
const MessagesScreen = lazyScreen(() => import('./screens/Messages'), (m) => m.MessagesScreen);
const AlertsScreen = lazyScreen(() => import('./screens/Alerts'), (m) => m.AlertsScreen);
const SettingsScreen = lazyScreen(() => import('./screens/Settings'), (m) => m.SettingsScreen);
const EnrollScreen = lazyScreen(() => import('./screens/Enroll'), (m) => m.EnrollScreen);

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
