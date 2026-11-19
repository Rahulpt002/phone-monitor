/** Readable details for event kinds whose `data` carries more than the message says. */
import { ALERT_KINDS } from '@hsm/shared';
import { FIELD_BY_PATH, alertKindLabel } from './settingsSchema';

export interface SettingsChangeSummary {
  user: string | null;
  version: number | null;
  /** Editor labels of the changed settings (the dotted path when there is no label). */
  fields: string[];
  /** The device profile override (root command templates) changed. */
  profileOverride: boolean;
}

const ALERT_PREFIX = 'alerts.enabled.';

function settingLabel(path: string): string {
  if (path === 'profile_override' || path.startsWith('profile_override.')) return 'Device profile override';
  if (path.startsWith(ALERT_PREFIX) && (ALERT_KINDS as readonly string[]).includes(path.slice(ALERT_PREFIX.length))) {
    return `${alertKindLabel(path.slice(ALERT_PREFIX.length))} alert`;
  }
  return FIELD_BY_PATH[path]?.label ?? path;
}

/**
 * The Server's audit record of PUT /api/settings ('settings_changed'):
 * data { user, version, changed: dotted paths, profile_override_changed }.
 */
export function settingsChangeSummary(data: Record<string, unknown> | undefined): SettingsChangeSummary {
  const d = data ?? {};
  const changed = Array.isArray(d.changed) ? d.changed.filter((p): p is string => typeof p === 'string') : [];
  return {
    user: typeof d.user === 'string' ? d.user : null,
    version: typeof d.version === 'number' ? d.version : null,
    fields: [...new Set(changed.map(settingLabel))],
    profileOverride: d.profile_override_changed === true,
  };
}
