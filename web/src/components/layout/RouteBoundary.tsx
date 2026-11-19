/**
 * Suspense + error boundary around a lazily loaded screen. A screen's code chunk can fail to load
 * (offline before the service worker cached it, or a deploy replaced the files); without this the
 * whole app would unmount. The boundary resets when the route changes.
 */
import { Component, Suspense, type ErrorInfo, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { Button, Loading, Notice } from '../ui/primitives';

interface BoundaryState {
  error: Error | null;
}

class ScreenErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  override state: BoundaryState = { error: null };

  static getDerivedStateFromError(error: unknown): BoundaryState {
    return { error: error instanceof Error ? error : new Error(String(error)) };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.error('Screen failed to render', error, info.componentStack);
  }

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    const chunk = /dynamically imported module|Importing a module script failed|Failed to fetch|error loading/i.test(error.message);
    return (
      <Notice
        tone="danger"
        title={chunk ? 'This screen could not be loaded' : 'This screen had a problem'}
        action={
          <Button size="sm" onClick={() => window.location.reload()}>
            Reload
          </Button>
        }
      >
        {chunk ? 'Check the connection, then reload the page. A new version of the Dashboard may have been deployed.' : error.message}
      </Notice>
    );
  }
}

export function ScreenBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  return (
    <ScreenErrorBoundary key={pathname}>
      <Suspense fallback={<Loading label="Loading screen" />}>{children}</Suspense>
    </ScreenErrorBoundary>
  );
}
