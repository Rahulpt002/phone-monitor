import { useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useSession } from '../../store/session';
import { Dialog } from '../ui/Dialog';
import { IconLogout, IconMore } from '../ui/icons';
import { cx } from '../ui/primitives';
import { bottomTabs, navFor } from './nav';
import { ThemeToggle } from './TopBar';

export function SideNav() {
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  return (
    <nav aria-label="Main" className="fixed inset-y-0 left-0 z-40 hidden w-60 flex-col border-r border-border bg-surface lg:flex">
      <div className="flex h-14 items-center gap-2 px-4">
        <img src="/favicon.svg" alt="" className="size-7 rounded-md" />
        <span className="text-base font-semibold text-fg">Hotspot Monitor</span>
      </div>
      <ul className="flex-1 space-y-0.5 overflow-y-auto px-2 py-2">
        {navFor(isAdmin).map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                cx(
                  'flex min-h-11 items-center gap-3 rounded-xl px-3 text-sm font-medium',
                  isActive ? 'bg-accent/15 text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <item.icon className={cx('text-lg', isActive ? 'text-accent' : undefined)} />
                  {item.label}
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

export function BottomTabs() {
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  const user = useSession((s) => s.user);
  const logout = useSession((s) => s.logout);
  const [moreOpen, setMoreOpen] = useState(false);
  const location = useLocation();
  const tabs = bottomTabs(isAdmin);
  const tabPaths = new Set(tabs.map((t) => t.to));
  const more = navFor(isAdmin).filter((n) => !tabPaths.has(n.to));
  const moreActive = more.some((n) => location.pathname.startsWith(n.to) && n.to !== '/');

  const tabClass = (active: boolean) =>
    cx(
      'flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-[11px] font-medium',
      active ? 'text-accent' : 'text-muted hover:text-fg',
    );

  return (
    <>
      <nav aria-label="Main" className="safe-bottom fixed inset-x-0 bottom-0 z-40 border-t border-border bg-surface/95 backdrop-blur lg:hidden">
        <ul className="mx-auto flex max-w-xl">
          {tabs.map((item) => (
            <li key={item.to} className="flex flex-1">
              <NavLink to={item.to} end={item.to === '/'} className={({ isActive }) => tabClass(isActive)}>
                <item.icon className="text-xl" />
                {item.short ?? item.label}
              </NavLink>
            </li>
          ))}
          <li className="flex flex-1">
            <button
              type="button"
              className={tabClass(moreActive)}
              onClick={() => setMoreOpen(true)}
              aria-haspopup="dialog"
              aria-expanded={moreOpen}
            >
              <IconMore className="text-xl" />
              More
            </button>
          </li>
        </ul>
      </nav>
      <Dialog open={moreOpen} onClose={() => setMoreOpen(false)} title="More" variant="sheet">
        <ul className="grid grid-cols-2 gap-2">
          {more.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                onClick={() => setMoreOpen(false)}
                className={({ isActive }) =>
                  cx(
                    'flex min-h-14 items-center gap-3 rounded-xl border border-border px-3 text-sm font-medium',
                    isActive ? 'bg-accent/15 text-fg' : 'bg-surface-2 text-fg hover:bg-surface-3',
                  )
                }
              >
                <item.icon className="text-lg text-muted" />
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
          <ThemeToggle withLabel />
          {user ? (
            <button
              type="button"
              onClick={() => {
                setMoreOpen(false);
                logout();
              }}
              className="inline-flex min-h-10 items-center gap-2 rounded-xl px-3 text-sm text-muted hover:bg-surface-2 hover:text-fg"
            >
              <IconLogout /> Sign out {user.username}
            </button>
          ) : null}
        </div>
      </Dialog>
    </>
  );
}
