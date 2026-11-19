/**
 * Which controls a user sees (plan 6.1 capability map, plan 9 roles, plan 6.4 power-off rule).
 */
import { COMMANDS, type CapabilityMap, type CommandName, type CommandSpec, type Role, type Settings } from '@hsm/shared';

export interface ControlContext {
  role: Role | null | undefined;
  capabilities: CapabilityMap | null | undefined;
  settings: Settings | null | undefined;
  totpEnrollmentRequired?: boolean;
}

export interface ControlState {
  visible: boolean;
  disabled: boolean;
  /** Why it is disabled, shown next to the control. */
  reason?: string;
}

export const GROUP_ORDER: CommandSpec['group'][] = ['radio', 'power', 'apps', 'messaging', 'admin', 'finder'];

export const GROUP_LABEL: Record<CommandSpec['group'], string> = {
  radio: 'Radios and links',
  power: 'Power and screen',
  apps: 'Apps and processes',
  messaging: 'Messaging',
  admin: 'Admin',
  finder: 'Signal Finder',
};

/** Unsupported per the phone's capability map -> hidden. Unknown (not probed yet) -> shown. */
export function isSupported(name: CommandName, caps: CapabilityMap | null | undefined): boolean {
  return caps?.[name]?.supported !== false;
}

/** Viewers never send commands; admin_only ones additionally need an admin. */
export function canSend(spec: CommandSpec, role: Role | null | undefined): boolean {
  if (role !== 'admin') return false;
  return !spec.admin_only || role === 'admin';
}

export function controlState(name: CommandName, ctx: ControlContext): ControlState {
  const spec = COMMANDS[name];
  if (!spec) return { visible: false, disabled: true };
  if (!canSend(spec, ctx.role)) return { visible: false, disabled: true };
  if (!isSupported(name, ctx.capabilities)) return { visible: false, disabled: true };
  if (name === 'power_off' && !ctx.settings?.commands.allow_power_off) {
    return {
      visible: true,
      disabled: true,
      reason: 'Blocked by default: a phone that is switched off needs a hand to switch it on. Enable "Allow remote power off" in Settings > Command safety.',
    };
  }
  if (ctx.totpEnrollmentRequired) {
    return { visible: true, disabled: true, reason: 'Set up two-factor authentication to use controls.' };
  }
  return { visible: true, disabled: false };
}

/** Visible commands per group, in catalog order. */
export function visibleControls(ctx: ControlContext): Record<CommandSpec['group'], CommandName[]> {
  const out = Object.fromEntries(GROUP_ORDER.map((g) => [g, [] as CommandName[]])) as Record<CommandSpec['group'], CommandName[]>;
  for (const spec of Object.values(COMMANDS)) {
    if (controlState(spec.name, ctx).visible) out[spec.group].push(spec.name);
  }
  return out;
}
