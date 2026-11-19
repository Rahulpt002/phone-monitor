/**
 * Editor for every device setting (plan 6.5 "Every threshold is editable from the Dashboard").
 * Saves a minimal deep patch with PUT /api/settings; viewers get a read-only view. A change to the
 * device profile override (it holds the shell templates the phone runs as root) asks for the
 * admin's password, which goes along as PutSettingsRequest.password.
 */
import type { Settings } from '@hsm/shared';
import { useEffect, useMemo, useState } from 'react';
import { api, errorMessage, isApiError } from '../../lib/api';
import { WEEKDAYS, formatBytes } from '../../lib/format';
import {
  SETTINGS_SECTIONS,
  countChanges,
  defaultFor,
  diffSettings,
  getPath,
  parseIntList,
  sameValue,
  setPath,
  validateSettings,
  type FieldDef,
} from '../../lib/settingsSchema';
import { useData } from '../../store/data';
import { useDeviceId, useDeviceSettings } from '../../store/selectors';
import { useSession } from '../../store/session';
import { toast } from '../../store/toasts';
import { Field, Select, Switch, TextArea, TextInput } from '../ui/forms';
import { Button, Card, Notice, cx } from '../ui/primitives';
import { ReauthDialog } from './ReauthDialog';

/** Text shown in an input for a stored value (numbers and lists are edited as text). */
export function toInputText(def: FieldDef, value: unknown): string {
  if (def.kind === 'int_list') return Array.isArray(value) ? value.join(', ') : '';
  if (value === undefined || value === null) return '';
  return String(value);
}

/** Parses an input's text into the stored value; NaN marks unparseable numbers for validation. */
export function fromInputText(def: FieldDef, text: string): unknown {
  switch (def.kind) {
    case 'int':
    case 'number':
    case 'weekday':
      return text.trim() === '' ? Number.NaN : Number(text);
    case 'int_list':
      return parseIntList(text);
    default:
      return text;
  }
}

function describeDefault(def: FieldDef): string {
  const v = defaultFor(def.path);
  if (def.kind === 'bool') return v ? 'on' : 'off';
  if (def.kind === 'weekday') return WEEKDAYS[v as number] ?? String(v);
  if (def.kind === 'int_list') return (v as number[]).join(', ');
  if (def.path === 'finder.speed_bytes') return formatBytes(v as number);
  return `${String(v)}${def.unit ? ` ${def.unit}` : ''}`;
}

function SettingRow({
  def,
  value,
  text,
  onText,
  onValue,
  error,
  warning,
  readOnly,
}: {
  def: FieldDef;
  value: unknown;
  text: string;
  onText: (t: string) => void;
  onValue: (v: unknown) => void;
  error?: string;
  warning?: string;
  readOnly: boolean;
}) {
  const isDefault = sameValue(value, defaultFor(def.path));
  const reset = !readOnly && !isDefault ? (
    <button type="button" className="text-xs text-accent hover:underline" onClick={() => onValue(defaultFor(def.path))}>
      Reset to default ({describeDefault(def)})
    </button>
  ) : null;

  if (def.kind === 'bool') {
    return (
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-3">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-fg" id={`lbl-${def.path}`}>
            {def.label}
          </p>
          {def.help ? <p className="text-xs text-muted">{def.help}</p> : null}
          {reset}
        </div>
        <Switch checked={value === true} onChange={(v) => onValue(v)} label={def.label} disabled={readOnly} />
      </div>
    );
  }

  return (
    <div className="py-3">
      <Field label={def.label} help={def.help} error={error} warning={warning}>
        {({ id, describedBy, invalid }) => (
          <div className="flex flex-wrap items-center gap-2">
            {def.kind === 'select' ? (
              <Select id={id} value={String(value)} onChange={(e) => onValue(e.target.value)} disabled={readOnly} aria-describedby={describedBy} className="max-w-sm">
                {def.options?.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            ) : def.kind === 'weekday' ? (
              <Select id={id} value={String(value)} onChange={(e) => onValue(Number(e.target.value))} disabled={readOnly} aria-describedby={describedBy} className="max-w-48">
                {WEEKDAYS.map((d, i) => (
                  <option key={d} value={i}>
                    {d}
                  </option>
                ))}
              </Select>
            ) : (
              <TextInput
                id={id}
                type={def.kind === 'time' ? 'time' : def.kind === 'url' ? 'url' : 'text'}
                inputMode={def.kind === 'int' ? 'numeric' : def.kind === 'number' ? 'decimal' : undefined}
                value={text}
                onChange={(e) => onText(e.target.value)}
                disabled={readOnly}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                spellCheck={false}
                className={cx(def.kind === 'int' || def.kind === 'number' || def.kind === 'time' ? 'max-w-40' : def.kind === 'int_list' ? 'max-w-56' : 'max-w-xl', (def.kind === 'url' || def.kind === 'host') && 'font-mono text-sm')}
              />
            )}
            {def.unit ? <span className="text-sm text-muted">{def.unit}</span> : null}
            {def.path === 'finder.speed_bytes' && typeof value === 'number' && Number.isFinite(value) ? (
              <span className="text-xs text-faint">= {formatBytes(value)}</span>
            ) : null}
            {def.min !== undefined && def.max !== undefined && (def.kind === 'int' || def.kind === 'number') ? (
              <span className="w-full text-xs text-faint">
                Allowed {def.min} to {def.max}
              </span>
            ) : null}
          </div>
        )}
      </Field>
      {reset}
    </div>
  );
}

function ProfileOverride({
  value,
  onChange,
  readOnly,
  error,
}: {
  value: string;
  onChange: (t: string) => void;
  readOnly: boolean;
  error: string | null;
}) {
  return (
    <Card title="Device profile override" id="section-profile">
      <p className="mb-3 text-sm text-muted">
        JSON merged over the phone’s built-in device profile (charge-control path, USB function, thermal zones, command templates). Leave empty to use
        the built-in profile. Fill this in from the Phase 0 results.
      </p>
      <Field label="Override (JSON object)" error={error}>
        {({ id, describedBy, invalid }) => (
          <TextArea
            id={id}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            rows={8}
            spellCheck={false}
            disabled={readOnly}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            className="font-mono text-xs"
            placeholder={'{\n  "charge_control": [{ "path": "/sys/class/power_supply/battery/batt_slate_mode", "on": "0", "off": "1" }]\n}'}
          />
        )}
      </Field>
    </Card>
  );
}

export function parseOverride(text: string): { value: Record<string, unknown> | null; error: string | null } {
  if (!text.trim()) return { value: null, error: null };
  try {
    const v: unknown = JSON.parse(text);
    if (typeof v !== 'object' || v === null || Array.isArray(v)) return { value: null, error: 'Must be a JSON object like { "usb_function": "ncm" }.' };
    return { value: v as Record<string, unknown>, error: null };
  } catch (e) {
    return { value: null, error: `Invalid JSON: ${(e as Error).message}` };
  }
}

const overrideText = (v: Record<string, unknown> | null | undefined) => (v ? JSON.stringify(v, null, 2) : '');

function textsFor(s: Settings): Record<string, string> {
  const out: Record<string, string> = {};
  for (const sec of SETTINGS_SECTIONS) for (const f of sec.fields) out[f.path] = toInputText(f, getPath(s, f.path));
  return out;
}

export function SettingsEditor({ focusSection }: { focusSection?: string | null }) {
  const deviceId = useDeviceId();
  const server = useDeviceSettings();
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  const totp = useSession((s) => s.totpEnrollmentRequired);
  // Settings are stored per phone, so there is nothing to save to until one is paired.
  const readOnly = !isAdmin || totp || !deviceId;

  const [base, setBase] = useState<Settings>(server.settings);
  const [baseVersion, setBaseVersion] = useState<number | null>(server.version);
  const [draft, setDraft] = useState<Settings>(server.settings);
  const [texts, setTexts] = useState<Record<string, string>>(() => textsFor(server.settings));
  const [override, setOverride] = useState(() => overrideText(server.settings.profile_override));
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  /** The password prompt is open (profile override change, or the Server asked for it). */
  const [reauth, setReauth] = useState(false);

  const parsedOverride = parseOverride(override);
  const effective = useMemo<Settings>(
    () => ({ ...draft, profile_override: parsedOverride.error ? draft.profile_override : parsedOverride.value }),
    [draft, parsedOverride.value, parsedOverride.error],
  );
  const patch = useMemo(() => diffSettings(base, effective), [base, effective]);
  const changes = countChanges(patch);
  const { errors, warnings } = useMemo(() => validateSettings(effective), [effective]);
  const overrideError = parsedOverride.error ?? errors.profile_override ?? null;
  const errorCount = Object.keys(errors).length + (parsedOverride.error ? 1 : 0);
  const remoteChanged = server.version !== null && baseVersion !== null && server.version !== baseVersion;
  const overrideChanged = patch != null && 'profile_override' in patch;

  const resetTo = (s: Settings, version: number | null) => {
    setBase(s);
    setBaseVersion(version);
    setDraft(s);
    setTexts(textsFor(s));
    setOverride(overrideText(s.profile_override));
    setSaveError(null);
  };

  // Follow server updates while there are no local edits (another admin, or the charge_limit command).
  useEffect(() => {
    if (server.version === baseVersion) return;
    if (!changes || baseVersion === null) resetTo(server.settings, server.version);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [server.version, server.settings]);

  // Device switch: start over.
  useEffect(() => {
    resetTo(server.settings, server.version);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId]);

  useEffect(() => {
    if (!focusSection) return;
    document.getElementById(`section-${focusSection}`)?.scrollIntoView({ block: 'start' });
  }, [focusSection]);

  const setValue = (path: string, v: unknown, def: FieldDef) => {
    setDraft((d) => setPath(d, path, v));
    setTexts((t) => ({ ...t, [path]: toInputText(def, v) }));
  };
  const setText = (path: string, text: string, def: FieldDef) => {
    setTexts((t) => ({ ...t, [path]: text }));
    setDraft((d) => setPath(d, path, fromInputText(def, text)));
  };

  /** One PUT: the Server replaces profile_override as a whole, and the patch carries it complete. Throws. */
  const put = async (password?: string) => {
    if (!deviceId || !patch) return;
    const res = await api.putSettings({ device_id: deviceId, settings: patch, ...(password ? { password } : {}) });
    useData.getState().setSettings(res.device_id ?? deviceId, res.settings, res.version);
    resetTo(res.settings, res.version);
    toast.success(`Settings saved (version ${res.version}). The phone applies them when connected.`);
  };

  const save = async () => {
    if (!deviceId || !patch || errorCount) return;
    setSaveError(null);
    if (overrideChanged) {
      setReauth(true);
      return;
    }
    setSaving(true);
    try {
      await put();
    } catch (e) {
      // The Server decides what needs the password; ask for it rather than failing.
      if (isApiError(e, 'reauth_required')) setReauth(true);
      else setSaveError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  const saveWithPassword = async (password: string) => {
    setSaving(true);
    try {
      await put(password);
      setReauth(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4 pb-24">
      {!isAdmin ? <Notice tone="info">Viewer accounts can read the settings but not change them.</Notice> : null}
      {isAdmin && totp ? <Notice tone="warn">Set up two-factor authentication before changing settings.</Notice> : null}
      {!deviceId ? (
        <Notice tone="info" title="No phone is paired yet">
          Settings are stored per phone. These are the defaults a newly paired phone starts with; you can change them once it is paired.
        </Notice>
      ) : !server.loaded ? (
        <Notice tone="info">Showing the default values until the phone’s settings load.</Notice>
      ) : null}
      {remoteChanged && changes ? (
        <Notice
          tone="warn"
          title={`Settings changed elsewhere (now version ${server.version})`}
          action={
            <Button size="sm" onClick={() => resetTo(server.settings, server.version)}>
              Discard mine and reload
            </Button>
          }
        >
          Saving now applies your edits on top of the newer version.
        </Notice>
      ) : null}

      <nav aria-label="Settings sections" className="flex flex-wrap gap-2">
        {SETTINGS_SECTIONS.map((s) => (
          <a key={s.id} href={`#section-${s.id}`} className="rounded-full border border-border bg-surface-2 px-3 py-1 text-xs font-medium text-muted hover:text-fg">
            {s.title}
          </a>
        ))}
        <a href="#section-profile" className="rounded-full border border-border bg-surface-2 px-3 py-1 text-xs font-medium text-muted hover:text-fg">
          Device profile
        </a>
      </nav>

      {SETTINGS_SECTIONS.map((section) => (
        <Card key={section.id} title={section.title} id={`section-${section.id}`}>
          <p className="-mt-1 mb-1 text-sm text-muted">{section.description}</p>
          <div className="divide-y divide-border">
            {section.fields.map((def) => (
              <SettingRow
                key={def.path}
                def={def}
                value={getPath(draft, def.path)}
                text={texts[def.path] ?? ''}
                onText={(t) => setText(def.path, t, def)}
                onValue={(v) => setValue(def.path, v, def)}
                error={errors[def.path]}
                warning={warnings[def.path]}
                readOnly={readOnly}
              />
            ))}
          </div>
        </Card>
      ))}

      <ProfileOverride value={override} onChange={setOverride} readOnly={readOnly} error={overrideError} />

      {!readOnly ? (
        <div className="fixed inset-x-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-30 border-t border-border bg-surface/95 backdrop-blur lg:bottom-0 lg:left-60">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3">
            <p className="min-w-0 flex-1 text-sm text-muted" aria-live="polite">
              {saveError ? (
                <span className="text-danger">{saveError}</span>
              ) : errorCount ? (
                <span className="text-danger">
                  {errorCount} {errorCount === 1 ? 'value needs' : 'values need'} fixing
                </span>
              ) : changes ? (
                `${changes} unsaved ${changes === 1 ? 'change' : 'changes'}`
              ) : (
                `No changes${baseVersion != null ? ` · version ${baseVersion}` : ''}`
              )}
            </p>
            <Button onClick={() => resetTo(base, baseVersion)} disabled={!changes || saving}>
              Discard
            </Button>
            <Button variant="primary" onClick={save} busy={saving} disabled={!changes || Boolean(errorCount)}>
              Save
            </Button>
          </div>
        </div>
      ) : null}

      <ReauthDialog
        open={reauth}
        onClose={() => setReauth(false)}
        title={overrideChanged ? 'Confirm the device profile change' : 'Confirm the settings change'}
        description={
          overrideChanged
            ? 'The profile override holds the charge-control paths and command templates the phone runs as root. Enter your password to save it. The change is recorded in the event log.'
            : 'The Server asks for your password to save this change.'
        }
        confirmLabel="Confirm and save"
        onConfirm={saveWithPassword}
      />
    </div>
  );
}

