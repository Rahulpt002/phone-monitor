import type { Grade } from '@hsm/shared';
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { GRADE_ICON, GRADE_LABEL, gradeBgClass, gradeTextClass } from '../../lib/grades';
import { IconInfo, IconWarning } from './icons';

export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}

// --- layout -----------------------------------------------------------------------------------

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight text-fg">{title}</h1>
        {subtitle ? <p className="mt-1 text-sm text-muted">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  );
}

export function Card({
  title,
  actions,
  children,
  className,
  footer,
  id,
  stale,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  footer?: ReactNode;
  id?: string;
  /** Dims the card and shows why its data may be out of date. */
  stale?: ReactNode;
}) {
  const headingId = id ? `${id}-title` : undefined;
  return (
    <section
      id={id}
      aria-labelledby={title ? headingId : undefined}
      className={cx('min-w-0 rounded-2xl border border-border bg-surface p-4 shadow-sm', className)}
    >
      {title || actions ? (
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title ? (
            <h2 id={headingId} className="text-sm font-semibold uppercase tracking-wide text-muted">
              {title}
            </h2>
          ) : (
            <span />
          )}
          {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
        </div>
      ) : null}
      {stale ? (
        <p className="mb-2 inline-flex items-center gap-1 rounded-md bg-warn-soft px-2 py-0.5 text-xs font-medium text-warn">
          <IconWarning /> {stale}
        </p>
      ) : null}
      <div className={stale ? 'opacity-70' : undefined}>{children}</div>
      {footer ? <div className="mt-3 border-t border-border pt-3 text-sm text-muted">{footer}</div> : null}
    </section>
  );
}

export function Grid({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3', className)}>{children}</div>;
}

// --- buttons ----------------------------------------------------------------------------------

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
type ButtonSize = 'sm' | 'md' | 'lg';

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-accent-fg hover:brightness-110 disabled:hover:brightness-100',
  secondary: 'border border-border bg-surface-2 text-fg hover:bg-surface-3',
  ghost: 'text-fg hover:bg-surface-2',
  danger: 'bg-danger text-danger-fg hover:brightness-110',
};
const SIZE: Record<ButtonSize, string> = {
  sm: 'min-h-9 px-3 text-sm',
  md: 'min-h-11 px-4 text-sm',
  lg: 'min-h-12 px-5 text-base',
};

/** A Button's classes, for links that should look like one (router <Link>). */
export function buttonClass(variant: ButtonVariant = 'secondary', size: ButtonSize = 'md', className?: string): string {
  return cx(
    'inline-flex select-none items-center justify-center gap-2 rounded-xl font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
    VARIANT[variant],
    SIZE[size],
    className,
  );
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  busy?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', busy, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={buttonClass(variant, size, className)}
      {...rest}
    >
      {busy ? <Spinner /> : null}
      {children}
    </button>
  );
});

export function Spinner({ className, label }: { className?: string; label?: string }) {
  return (
    <span
      role={label ? 'status' : undefined}
      aria-label={label}
      className={cx('inline-block size-4 animate-spin rounded-full border-2 border-current border-t-transparent', className)}
    />
  );
}

// --- status -----------------------------------------------------------------------------------

export type Tone = 'ok' | 'warn' | 'danger' | 'info' | 'neutral';

const TONE: Record<Tone, string> = {
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  danger: 'bg-danger-soft text-danger',
  info: 'bg-info-soft text-info',
  neutral: 'bg-surface-3 text-muted',
};

export function Badge({ tone = 'neutral', children, className, title }: { tone?: Tone; children: ReactNode; className?: string; title?: string }) {
  return (
    <span
      title={title}
      className={cx('inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold', TONE[tone], className)}
    >
      {children}
    </span>
  );
}

export function Dot({ tone }: { tone: Tone }) {
  const color = { ok: 'bg-ok', warn: 'bg-warn', danger: 'bg-danger', info: 'bg-info', neutral: 'bg-faint' }[tone];
  return <span aria-hidden className={cx('inline-block size-2.5 shrink-0 rounded-full', color)} />;
}

export function GradeChip({ grade, className }: { grade: Grade | null; className?: string }) {
  if (!grade) return <Badge className={className}>No data</Badge>;
  return (
    <span
      className={cx(
        'grade-chip inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-surface-2 px-2 py-0.5 text-xs font-semibold',
        gradeTextClass(grade),
        className,
      )}
    >
      <span aria-hidden>{GRADE_ICON[grade]}</span>
      {GRADE_LABEL[grade]}
    </span>
  );
}

export function GradeBar({ grade, value, max = 100 }: { grade: Grade | null; value: number | null; max?: number }) {
  const pct = value == null ? 0 : Math.max(0, Math.min(100, (value / max) * 100));
  return (
    <div className="h-2 w-full overflow-hidden rounded-full bg-surface-3" aria-hidden>
      <div className={cx('h-full rounded-full motion-safe:transition-[width]', gradeBgClass(grade))} style={{ width: `${pct}%` }} />
    </div>
  );
}

export function Notice({
  tone = 'info',
  title,
  children,
  action,
}: {
  tone?: Tone;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const Icon = tone === 'info' || tone === 'neutral' || tone === 'ok' ? IconInfo : IconWarning;
  return (
    <div
      role={tone === 'danger' || tone === 'warn' ? 'alert' : 'status'}
      className={cx('flex flex-wrap items-start gap-3 rounded-xl px-3 py-2.5 text-sm', TONE[tone])}
    >
      <Icon className="mt-0.5 shrink-0 text-base" />
      <div className="min-w-0 flex-1 break-anywhere">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children ? <div className="text-fg/90">{children}</div> : null}
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted">{children}</p>;
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-6 text-sm text-muted">
      <Spinner /> {label}…
    </div>
  );
}

// --- values -----------------------------------------------------------------------------------

export function Stat({
  label,
  value,
  sub,
  valueClass,
  size = 'md',
}: {
  label: ReactNode;
  value: ReactNode;
  sub?: ReactNode;
  valueClass?: string;
  size?: 'md' | 'lg' | 'xl';
}) {
  const sizeClass = { md: 'text-2xl', lg: 'text-3xl sm:text-4xl', xl: 'text-5xl sm:text-6xl' }[size];
  // "-140 mA" -> big number, smaller unit, so values stay on one line in half-width tiles.
  const parts = typeof value === 'string' ? /^([-+~]?[\d.,]+)\s(\D+)$/.exec(value) : null;
  return (
    <div className="min-w-0">
      <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
      <p className={cx('tabular mt-0.5 font-semibold leading-tight text-fg', sizeClass, valueClass)}>
        {parts ? (
          <>
            {parts[1]}
            <span className="ml-1 text-[0.5em] font-medium">{parts[2]}</span>
          </>
        ) : (
          value
        )}
      </p>
      {sub ? <div className="mt-0.5 text-sm text-muted">{sub}</div> : null}
    </div>
  );
}

export function KeyValues({ items, className }: { items: [ReactNode, ReactNode][]; className?: string }) {
  return (
    <dl className={cx('grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm', className)}>
      {items.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-muted">{k}</dt>
          <dd className="tabular break-anywhere text-right font-medium text-fg">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Progress({
  value,
  max = 100,
  tone = 'info',
  label,
}: {
  value: number;
  max?: number;
  tone?: Tone;
  label: string;
}) {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  const bar = { ok: 'bg-ok', warn: 'bg-warn', danger: 'bg-danger', info: 'bg-accent', neutral: 'bg-faint' }[tone];
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={Math.round(value)}
      className="h-3 w-full overflow-hidden rounded-full bg-surface-3"
    >
      <div className={cx('h-full rounded-full motion-safe:transition-[width]', bar)} style={{ width: `${pct}%` }} />
    </div>
  );
}

/** Scrollable table container so wide tables never widen the page. */
export function TableWrap({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <div className="-mx-4 overflow-x-auto px-4" role={label ? 'region' : undefined} aria-label={label} tabIndex={label ? 0 : undefined}>
      {children}
    </div>
  );
}

export const th = 'whitespace-nowrap px-2 py-2 text-left text-xs font-semibold uppercase tracking-wide text-muted';
export const td = 'px-2 py-2 align-top text-sm';
