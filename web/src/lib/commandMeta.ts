/** Dashboard wording for commands: what each does, how its arguments read, and status labels. */
import { COMMANDS, type CommandName, type CommandRecord, type CommandStatus, type NetworkMode, type RevertConfirm } from '@hsm/shared';
import type { Tone } from '../components/ui/primitives';

export const COMMAND_HELP: Partial<Record<CommandName, string>> = {
  mobile_data: 'Turns the SIM data connection on or off. Turning it off arms auto-revert.',
  data_cycle: 'Mobile data off, wait, then on again. The first watchdog recovery step.',
  airplane_mode: 'All radios off. Turning it on arms auto-revert.',
  airplane_cycle: 'Airplane mode on for a few seconds, then off. Forces a fresh network attach.',
  usb_tethering: 'Shares the internet over the USB cable. Turning it off cuts the home network, so you must press Keep.',
  hotspot: 'Wi-Fi hotspot with a name and password.',
  wifi: 'Wi-Fi client radio. Keep off unless used (heat and power).',
  bluetooth: 'Keep off unless used (heat and power).',
  network_mode: 'Allowed network types. A mode the cell does not offer can drop the connection, so auto-revert is armed.',
  reboot: 'Restarts the phone. Counts toward the reboot limit.',
  power_off: 'Switches the phone off. Someone must press the power button to turn it back on.',
  screen: 'Wakes the screen or puts it to sleep.',
  brightness: 'Screen brightness, 0 to 255. Keep it low to save power and heat.',
  charging: 'Manual charge switch. The watchdog heat rule still wins.',
  charge_limit: 'Stop charging at this level; resumes a few percent lower. Saved in Settings once the phone reports it applied.',
  app_list: 'Lists installed apps (shown under System > Apps).',
  app_force_stop: 'Stops an app until it is started again.',
  app_disable: 'Disables an app for user 0 until it is enabled again.',
  app_enable: 'Re-enables a disabled app.',
  process_list: 'Runs top once and shows the output.',
  send_sms: 'Sends a text message from the phone’s SIM.',
  ussd: 'Runs a USSD code (balance, plan) and shows the reply.',
  shell: 'Runs a command as root on the phone.',
  refresh: 'Asks the phone for fresh readings of every group now.',
  capability_probe: 'Re-tests which commands this phone supports.',
  watchdog_simulate: 'Pretends the internet is down so the recovery ladder runs (testing only).',
  speed_test: 'Download, upload, ping and jitter. Each run uses about 10 to 30 MB of data.',
  finder_spot_test: 'Hold still while the phone records average, min, max and stability of each metric.',
  finder_set_baseline: 'Marks this spot as the installed baseline for later comparison.',
  finder_delete_spot: 'Deletes the spot on the phone and the Server.',
};

export const NETWORK_MODES: { value: NetworkMode; label: string }[] = [
  { value: 'auto', label: 'Automatic (all)' },
  { value: 'nr_lte', label: '5G + 4G' },
  { value: 'lte_only', label: '4G only' },
  { value: 'lte_wcdma', label: '4G + 3G' },
  { value: 'wcdma_only', label: '3G only' },
];

export const STATUS_META: Record<CommandStatus, { label: string; tone: Tone }> = {
  queued: { label: 'Queued', tone: 'neutral' },
  sent: { label: 'Sent', tone: 'info' },
  acked: { label: 'Running', tone: 'info' },
  done: { label: 'Done', tone: 'ok' },
  failed: { label: 'Failed', tone: 'danger' },
  rejected: { label: 'Rejected', tone: 'danger' },
  expired: { label: 'Expired', tone: 'warn' },
  revert_armed: { label: 'Auto-revert armed', tone: 'warn' },
  kept: { label: 'Kept', tone: 'ok' },
  reverted: { label: 'Reverted', tone: 'warn' },
};

export const FINAL_STATUSES: CommandStatus[] = ['done', 'failed', 'rejected', 'expired', 'kept', 'reverted'];

export function isFinished(c: CommandRecord | undefined): boolean {
  return Boolean(c && FINAL_STATUSES.includes(c.status));
}

/** Finished with a result the UI can use (revert_armed already carries the output). */
export function hasResult(c: CommandRecord | undefined): boolean {
  return Boolean(c && (c.status === 'done' || c.status === 'revert_armed' || c.status === 'kept' || c.status === 'reverted' || c.status === 'failed'));
}

const onOff = (v: unknown) => (v === true ? 'on' : v === false ? 'off' : String(v));

/**
 * `args` without the keys the command declares secret (CommandSpec.secret_args, e.g. the hotspot
 * password). The Server never sends them; this keeps them out of the store and the UI regardless.
 */
export function withoutSecretArgs(name: CommandName, args: Record<string, unknown>): Record<string, unknown> {
  const secret = COMMANDS[name]?.secret_args;
  if (!secret?.length || !args || !secret.some((k) => k in args)) return args;
  const out = { ...args };
  for (const k of secret) delete out[k];
  return out;
}

/** WPA2 passphrase as the Agent accepts it (ArgRules.kt): 8 to 63 printable ASCII characters. */
export function hotspotPasswordError(pw: string): string | null {
  if (pw.length < 8) return 'At least 8 characters (WPA2).';
  if (pw.length > 63) return 'At most 63 characters.';
  if (!/^[\x20-\x7E]+$/.test(pw)) return 'Use printable ASCII only: letters, digits, spaces and symbols, no accents or emoji.';
  return null;
}

/** Root shell timeout range, the same on the Server, the simulator and the Agent. */
export const SHELL_TIMEOUT_MAX_S = 600;

/** Whole seconds 1..SHELL_TIMEOUT_MAX_S, or null. */
export function parseShellTimeout(text: string): number | null {
  if (!/^\d+$/.test(text.trim())) return null;
  const n = Number(text);
  return n >= 1 && n <= SHELL_TIMEOUT_MAX_S ? n : null;
}

/** One-line human description of a command with its arguments. Secret arguments never appear. */
export function describeCommand(name: CommandName, rawArgs: Record<string, unknown> = {}): string {
  const label = COMMANDS[name]?.label ?? name;
  const args = withoutSecretArgs(name, rawArgs ?? {});
  switch (name) {
    case 'mobile_data':
    case 'airplane_mode':
    case 'wifi':
    case 'bluetooth':
    case 'charging':
      return `${label} ${onOff(args.enabled)}`;
    case 'usb_tethering':
      return `${label} ${onOff(args.enabled)}${args.function ? ` (${String(args.function).toUpperCase()})` : ''}`;
    case 'hotspot':
      return args.enabled === false ? `${label} off` : `${label} on${args.ssid ? ` as "${String(args.ssid)}"` : ''}`;
    case 'data_cycle':
    case 'airplane_cycle':
      return `${label}${args.off_seconds ? ` (${String(args.off_seconds)} s off)` : ''}`;
    case 'network_mode':
      return `${label}: ${NETWORK_MODES.find((m) => m.value === args.mode)?.label ?? String(args.mode)}`;
    case 'screen':
      return `${label} ${args.on ? 'on' : 'off'}`;
    case 'brightness':
      return `${label} ${String(args.level)}`;
    case 'charge_limit':
      return `${label} ${String(args.percent)}%`;
    case 'app_force_stop':
    case 'app_disable':
    case 'app_enable':
      return `${label}: ${String(args.package ?? '')}`;
    case 'send_sms':
      return `${label} to ${String(args.to ?? '')}`;
    case 'ussd':
      return `${label} ${String(args.code ?? '')}`;
    case 'shell':
      return `${label}: ${String(args.cmd ?? '')}`;
    case 'watchdog_simulate':
      return `${label} for ${String(args.duration_s ?? '')} s`;
    case 'finder_spot_test':
      return `${label}: ${String(args.name ?? '')}${args.orientation ? ` (${String(args.orientation)})` : ''}`;
    default:
      return label;
  }
}

export function revertExplanation(mode: RevertConfirm, seconds: number): string {
  return mode === 'dashboard'
    ? `This can cut the home network while the phone stays online, so the phone cannot judge it by itself. Press "Keep" within ${seconds} s or the phone undoes the change.`
    : `If the phone cannot reach the Server again within ${seconds} s, it undoes the change by itself. It confirms automatically once its link works.`;
}

/** Commands whose confirmation button should look dangerous. */
export const DESTRUCTIVE: CommandName[] = ['reboot', 'power_off', 'shell', 'app_disable', 'finder_delete_spot', 'watchdog_simulate'];
