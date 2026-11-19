import type { CommandRecord } from '@hsm/shared';

export interface AppRow {
  pkg: string;
  enabled: boolean;
  system: boolean;
  label?: string;
}

/** Parses the app_list command output: a JSON array of { pkg, enabled, system }. */
export function parseAppList(output: string | null | undefined): AppRow[] | null {
  if (!output) return null;
  try {
    const v: unknown = JSON.parse(output);
    if (!Array.isArray(v)) return null;
    return v
      .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null && typeof (x as { pkg?: unknown }).pkg === 'string')
      .map((x) => ({
        pkg: x.pkg as string,
        enabled: x.enabled !== false,
        system: Boolean(x.system),
        label: typeof x.label === 'string' ? x.label : undefined,
      }))
      .sort((a, b) => a.pkg.localeCompare(b.pkg));
  } catch {
    return null;
  }
}

/**
 * Applies app_enable / app_disable commands that finished after the list was taken, so the table
 * reflects them without re-listing.
 */
export function applyAppChanges(rows: AppRow[], listAt: string, commands: CommandRecord[]): AppRow[] {
  const since = Date.parse(listAt);
  const latest = new Map<string, boolean>();
  for (const c of [...commands].reverse()) {
    if (c.name !== 'app_enable' && c.name !== 'app_disable') continue;
    if (c.status !== 'done' || Date.parse(c.created_at) < since) continue;
    const pkg = c.args.package;
    if (typeof pkg === 'string') latest.set(pkg, c.name === 'app_enable');
  }
  if (!latest.size) return rows;
  return rows.map((r) => (latest.has(r.pkg) ? { ...r, enabled: latest.get(r.pkg)! } : r));
}
