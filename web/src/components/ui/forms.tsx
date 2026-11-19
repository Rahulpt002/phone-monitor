import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cx } from './primitives';

export const inputClass =
  'block w-full min-w-0 rounded-xl border border-border bg-surface-2 px-3 py-2.5 text-base text-fg placeholder:text-faint disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-danger sm:text-sm';

/** Label + control + help + error, with the ids wired for screen readers. */
export function Field({
  label,
  help,
  error,
  warning,
  children,
  className,
  inline,
}: {
  label: ReactNode;
  help?: ReactNode;
  error?: string | null;
  warning?: string | null;
  children: (ids: { id: string; describedBy: string | undefined; invalid: boolean }) => ReactNode;
  className?: string;
  inline?: boolean;
}) {
  const id = useId();
  const helpId = help ? `${id}-help` : undefined;
  const errId = error ? `${id}-err` : warning ? `${id}-warn` : undefined;
  const describedBy = [helpId, errId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={cx(inline ? 'flex flex-wrap items-center justify-between gap-3' : 'space-y-1.5', className)}>
      <label htmlFor={id} className="block text-sm font-medium text-fg">
        {label}
      </label>
      {children({ id, describedBy, invalid: Boolean(error) })}
      {help ? (
        <p id={helpId} className="w-full text-xs text-muted">
          {help}
        </p>
      ) : null}
      {error ? (
        <p id={errId} className="w-full text-xs font-medium text-danger" role="alert">
          {error}
        </p>
      ) : warning ? (
        <p id={errId} className="w-full text-xs font-medium text-warn">
          {warning}
        </p>
      ) : null}
    </div>
  );
}

export const TextInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function TextInput(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cx(inputClass, className)} {...rest} />;
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, children, ...rest },
  ref,
) {
  return (
    <select ref={ref} className={cx(inputClass, 'pr-8', className)} {...rest}>
      {children}
    </select>
  );
});

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea(
  { className, ...rest },
  ref,
) {
  return <textarea ref={ref} className={cx(inputClass, 'min-h-24', className)} {...rest} />;
});

/** Accessible on/off switch (a real checkbox with role="switch"). */
export function Switch({
  checked,
  onChange,
  label,
  disabled,
  id,
  describedBy,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: string;
  disabled?: boolean;
  id?: string;
  describedBy?: string;
}) {
  return (
    <span className="relative inline-flex shrink-0 items-center">
      <input
        id={id}
        type="checkbox"
        role="switch"
        className="peer absolute inset-0 z-10 m-0 h-full w-full cursor-pointer opacity-0 disabled:cursor-not-allowed"
        checked={checked}
        disabled={disabled}
        aria-label={label}
        aria-describedby={describedBy}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span
        aria-hidden
        className={cx(
          'flex h-7 w-12 items-center rounded-full border border-border p-0.5 transition-colors peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-accent peer-disabled:opacity-50',
          checked ? 'bg-accent' : 'bg-surface-3',
        )}
      >
        <span
          className={cx(
            'size-6 rounded-full bg-white shadow transition-transform',
            checked ? 'translate-x-5' : 'translate-x-0',
          )}
        />
      </span>
    </span>
  );
}

/** Segmented choice (radio group) for small option sets such as the chart range. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  size = 'sm',
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  label: string;
  size?: 'sm' | 'md';
}) {
  const name = useId();
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap rounded-xl border border-border bg-surface-2 p-0.5">
      {options.map((o) => (
        <label
          key={o.value}
          className={cx(
            'relative cursor-pointer rounded-lg font-medium has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-accent',
            size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-sm',
            value === o.value ? 'bg-accent text-accent-fg' : 'text-muted hover:text-fg',
          )}
        >
          <input
            type="radio"
            name={name}
            value={o.value}
            checked={value === o.value}
            onChange={() => onChange(o.value)}
            className="sr-only"
          />
          {o.label}
        </label>
      ))}
    </div>
  );
}
