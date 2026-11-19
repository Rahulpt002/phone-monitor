/** WAI-ARIA tabs: arrow keys / Home / End move between tabs, only the active tab is in the tab order. */
import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { cx } from './primitives';

export interface TabDef<T extends string> {
  id: T;
  label: ReactNode;
}

export function Tabs<T extends string>({
  tabs,
  active,
  onChange,
  label,
  children,
}: {
  tabs: TabDef<T>[];
  active: T;
  onChange: (id: T) => void;
  label: string;
  children: ReactNode;
}) {
  const base = useId();
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const idx = tabs.findIndex((t) => t.id === active);
    let next = -1;
    if (e.key === 'ArrowRight') next = (idx + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (idx - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    if (next < 0) return;
    e.preventDefault();
    const t = tabs[next]!;
    onChange(t.id);
    refs.current[t.id]?.focus();
  };

  return (
    <div>
      <div
        role="tablist"
        aria-label={label}
        onKeyDown={onKeyDown}
        className="mb-4 flex flex-wrap gap-x-1 border-b border-border"
      >
        {tabs.map((t) => (
          <button
            key={t.id}
            ref={(el) => {
              refs.current[t.id] = el;
            }}
            role="tab"
            type="button"
            id={`${base}-tab-${t.id}`}
            aria-selected={active === t.id}
            aria-controls={`${base}-panel`}
            tabIndex={active === t.id ? 0 : -1}
            onClick={() => onChange(t.id)}
            className={cx(
              '-mb-px min-h-11 whitespace-nowrap border-b-2 px-3 text-sm font-medium',
              active === t.id ? 'border-accent text-fg' : 'border-transparent text-muted hover:text-fg',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${base}-panel`} aria-labelledby={`${base}-tab-${active}`}>
        {children}
      </div>
    </div>
  );
}
