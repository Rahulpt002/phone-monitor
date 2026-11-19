/**
 * Command catalog (plan sections 6.3 and 6.4).
 *
 * The same names are used by: the Dashboard (buttons), the Server (validation, audit,
 * confirmation and re-auth rules), and the Agent (executor + capability map keys).
 */

export type CommandName =
  // radios and links
  | 'mobile_data' // { enabled: boolean }
  | 'data_cycle' // { off_seconds?: number } mobile data off, wait, on
  | 'airplane_mode' // { enabled: boolean }
  | 'airplane_cycle' // { off_seconds?: number } airplane on for N s, then off (plan example)
  | 'usb_tethering' // { enabled: boolean, function?: 'rndis' | 'ncm' }
  | 'hotspot' // { enabled: boolean, ssid?: string, password?: string }
  | 'wifi' // { enabled: boolean }
  | 'bluetooth' // { enabled: boolean }
  | 'network_mode' // { mode: NetworkMode }
  // power and screen
  | 'reboot' // {}
  | 'power_off' // {} blocked unless settings.commands.allow_power_off
  | 'screen' // { on: boolean }
  | 'brightness' // { level: 0..255 }
  | 'charging' // { enabled: boolean } manual override (watchdog heat rule still wins)
  | 'charge_limit' // { percent: 20..100 } also stored in settings.watchdog.charge_limit_pct
  // apps and processes
  | 'app_list' // {} -> output: JSON array of { pkg, enabled, system }
  | 'app_force_stop' // { package: string }
  | 'app_disable' // { package: string }
  | 'app_enable' // { package: string }
  | 'process_list' // {} -> output: `top -n 1` text
  // messaging
  | 'send_sms' // { to: string, body: string }
  | 'ussd' // { code: string } -> output: USSD reply text
  // admin
  | 'shell' // { cmd: string, timeout_s?: number } root shell, admin only
  | 'refresh' // {} send static + slow + fast telemetry now
  | 'capability_probe' // {} re-run the capability map and send static tier
  | 'watchdog_simulate' // { scenario: 'internet_down', duration_s: number } (Phase 4 test)
  // signal finder
  | 'speed_test' // { spot_id?: string } 3 runs of down/up/ping/jitter; attaches to the spot if given
  | 'finder_spot_test' // { name: string, orientation?: string, duration_s?: number }
  | 'finder_set_baseline' // { spot_id: string }
  | 'finder_delete_spot'; // { spot_id: string }

export type NetworkMode = 'auto' | 'nr_lte' | 'lte_only' | 'lte_wcdma' | 'wcdma_only';

/**
 * How an auto-revert is confirmed (plan 6.4 rule 1).
 * - 'agent_link': kept once the Agent completes a Server round-trip (probe -> probe_ack) that it
 *   started at least `REVERT_SETTLE_S` seconds after the change was applied.
 * - 'dashboard': kept only when a user presses "Keep" (POST /commands/:id/keep -> cmd_keep),
 *   because the change can cut the home network (and so the Dashboard) while the phone stays online.
 * Otherwise the Agent undoes the change when the revert deadline passes.
 */
export type RevertConfirm = 'agent_link' | 'dashboard';

export interface CommandSpec {
  name: CommandName;
  label: string;
  group: 'radio' | 'power' | 'apps' | 'messaging' | 'admin' | 'finder';
  /** Requires a second confirmation click in the Dashboard (plan 6.4 rule 4). */
  confirm: boolean;
  /** Requires the user's password again (plan section 10). */
  reauth: boolean;
  /** Only admins may send it. Viewers can never send any command; this marks admin-only even among admins' tools. */
  admin_only: boolean;
  /**
   * Returns the revert confirmation mode when these args can cut a link and must arm auto-revert,
   * or null when no revert is needed.
   */
  revertFor: (args: Record<string, unknown>) => RevertConfirm | null;
  /** Counts toward the reboot limit (plan 6.4 rule 3). */
  is_reboot?: boolean;
  /**
   * Argument keys holding secrets (for example the hotspot password). The Server never returns
   * them over REST or WebSocket (to any role) and blanks them in storage once the command has
   * reached the Agent.
   */
  secret_args?: readonly string[];
}

export const REVERT_SETTLE_S = 10;

const never = () => null;

export const COMMANDS: Record<CommandName, CommandSpec> = {
  mobile_data: {
    name: 'mobile_data', label: 'Mobile data', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: (a) => (a.enabled === false ? 'agent_link' : null),
  },
  data_cycle: {
    name: 'data_cycle', label: 'Cycle mobile data', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  airplane_mode: {
    name: 'airplane_mode', label: 'Airplane mode', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: (a) => (a.enabled === true ? 'agent_link' : null),
  },
  airplane_cycle: {
    name: 'airplane_cycle', label: 'Cycle airplane mode', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  usb_tethering: {
    name: 'usb_tethering', label: 'USB tethering', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: (a) => (a.enabled === false ? 'dashboard' : null),
  },
  hotspot: {
    name: 'hotspot', label: 'Wi-Fi hotspot', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: never, secret_args: ['password'],
  },
  wifi: {
    name: 'wifi', label: 'Wi-Fi', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  bluetooth: {
    name: 'bluetooth', label: 'Bluetooth', group: 'radio', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  network_mode: {
    name: 'network_mode', label: 'Network mode', group: 'radio', confirm: true, reauth: false, admin_only: false,
    revertFor: () => 'agent_link',
  },
  reboot: {
    name: 'reboot', label: 'Reboot', group: 'power', confirm: true, reauth: true, admin_only: false,
    revertFor: never, is_reboot: true,
  },
  power_off: {
    name: 'power_off', label: 'Power off', group: 'power', confirm: true, reauth: true, admin_only: true,
    revertFor: never,
  },
  screen: {
    name: 'screen', label: 'Screen', group: 'power', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  brightness: {
    name: 'brightness', label: 'Brightness', group: 'power', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  charging: {
    name: 'charging', label: 'Charging', group: 'power', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  charge_limit: {
    name: 'charge_limit', label: 'Charge limit', group: 'power', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  app_list: {
    name: 'app_list', label: 'List apps', group: 'apps', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  app_force_stop: {
    name: 'app_force_stop', label: 'Force stop app', group: 'apps', confirm: true, reauth: false, admin_only: false,
    revertFor: never,
  },
  app_disable: {
    name: 'app_disable', label: 'Disable app', group: 'apps', confirm: true, reauth: true, admin_only: true,
    revertFor: never,
  },
  app_enable: {
    name: 'app_enable', label: 'Enable app', group: 'apps', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  process_list: {
    name: 'process_list', label: 'Processes', group: 'apps', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  send_sms: {
    name: 'send_sms', label: 'Send SMS', group: 'messaging', confirm: true, reauth: false, admin_only: true,
    revertFor: never,
  },
  ussd: {
    name: 'ussd', label: 'Run USSD code', group: 'messaging', confirm: false, reauth: false, admin_only: true,
    revertFor: never,
  },
  shell: {
    name: 'shell', label: 'Root shell', group: 'admin', confirm: true, reauth: true, admin_only: true,
    revertFor: never,
  },
  refresh: {
    name: 'refresh', label: 'Refresh all readings', group: 'admin', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  capability_probe: {
    name: 'capability_probe', label: 'Re-test capabilities', group: 'admin', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  watchdog_simulate: {
    name: 'watchdog_simulate', label: 'Simulate internet loss', group: 'admin', confirm: true, reauth: false, admin_only: true,
    revertFor: never,
  },
  speed_test: {
    name: 'speed_test', label: 'Speed test', group: 'finder', confirm: true, reauth: false, admin_only: false,
    revertFor: never,
  },
  finder_spot_test: {
    name: 'finder_spot_test', label: 'Spot test', group: 'finder', confirm: false, reauth: false, admin_only: false,
    revertFor: never,
  },
  finder_set_baseline: {
    name: 'finder_set_baseline', label: 'Set baseline', group: 'finder', confirm: true, reauth: false, admin_only: false,
    revertFor: never,
  },
  finder_delete_spot: {
    name: 'finder_delete_spot', label: 'Delete spot', group: 'finder', confirm: true, reauth: false, admin_only: false,
    revertFor: never,
  },
};

export const COMMAND_NAMES = Object.keys(COMMANDS) as CommandName[];

/**
 * Command lifecycle as stored by the Server and shown in the audit log.
 *
 * queued -> sent -> acked -> done | failed
 *        \-> expired (not delivered within settings.commands.expiry_s)
 *        sent -> rejected (Agent refused: expired on arrival, unsupported, blocked, reboot limit)
 * done (with revert armed) -> revert_armed -> kept | reverted
 * sent | acked | revert_armed -> expired (soft: no ack, no result, or no revert report in time;
 *        late reports still apply, see commandMayMove)
 */
export type CommandStatus =
  | 'queued'
  | 'sent'
  | 'acked'
  | 'done'
  | 'failed'
  | 'rejected'
  | 'expired'
  | 'revert_armed'
  | 'kept'
  | 'reverted';

export type CommandSource = 'dashboard' | 'watchdog' | 'schedule' | 'sms' | 'lan' | 'local_ui';

export interface CommandRecord {
  id: string; // "c_" + random
  device_id: string;
  name: CommandName;
  args: Record<string, unknown>;
  source: CommandSource;
  user: string | null; // username, or null for Agent-originated actions
  status: CommandStatus;
  created_at: string; // ISO; equals the `sent_at` given to the Agent (plan 6.4 rule 6)
  delivered_at?: string | null;
  acked_at?: string | null;
  finished_at?: string | null;
  ok?: boolean | null;
  output?: string | null; // truncated to 64 KiB by the Server
  error?: string | null;
  duration_ms?: number | null;
  revert_confirm?: RevertConfirm | null;
  revert_deadline?: string | null; // ISO, while status = revert_armed
}

/**
 * Which statuses may move to `next` (docs/protocol.md 3.5: a report never moves a command
 * backwards). `delivered`: the command had reached the Agent (delivered_at set), so a soft
 * 'expired' can still take its late reports. Used by the Server for its guarded UPDATEs and by
 * the Dashboard to drop stale copies, so both apply the same lifecycle.
 */
export function commandStatusAllowedFrom(next: CommandStatus, delivered: boolean): CommandStatus[] {
  const soft: CommandStatus[] = delivered ? ['expired'] : [];
  switch (next) {
    case 'sent':
      return ['queued'];
    case 'expired':
      return ['queued', 'sent', 'acked', 'revert_armed'];
    case 'acked':
    case 'rejected':
      return ['queued', 'sent', ...soft];
    case 'done':
    case 'failed':
    case 'revert_armed':
      return ['queued', 'sent', 'acked', ...soft];
    case 'kept':
    case 'reverted':
      return ['queued', 'sent', 'acked', 'done', 'failed', 'revert_armed', ...soft];
    default:
      return [];
  }
}

const LATE_ORDER: Partial<Record<CommandStatus, number>> = {
  sent: 1,
  acked: 2,
  rejected: 2,
  done: 3,
  failed: 3,
  revert_armed: 3,
  kept: 4,
  reverted: 4,
};

/** How far a command had got before it was marked expired (a soft expiry keeps late reports). */
function phaseBeforeExpiry(rec: Pick<CommandRecord, 'acked_at' | 'revert_deadline'>): number {
  if (rec.revert_deadline != null) return 3;
  if (rec.acked_at != null) return 2;
  return 1;
}

/**
 * Whether a command whose stored record is `cur` may move to status `next`: the transition is in
 * commandStatusAllowedFrom and, from a soft 'expired', the late report is ahead of what the
 * command had reached before it expired.
 */
export function commandMayMove(
  cur: Pick<CommandRecord, 'status' | 'delivered_at' | 'acked_at' | 'revert_deadline'>,
  next: CommandStatus,
): boolean {
  if (!commandStatusAllowedFrom(next, cur.delivered_at != null).includes(cur.status)) return false;
  if (cur.status === 'expired' && (LATE_ORDER[next] ?? 0) <= phaseBeforeExpiry(cur)) return false;
  return true;
}

/**
 * SMS recipient as the Agent checks it (ArgRules.phoneNumber): separators removed, then an optional
 * `+` and 2..20 digits. Returns the normalized number, or null when it is not one. Separators are
 * Java's `\s` (ASCII whitespace only, no NBSP) plus `( ) . -`, so a number the Dashboard and the
 * Server accept is never rejected by the Agent as invalid_args.
 */
export function normalizePhoneNumber(value: string): string | null {
  const v = value.replace(/[ \t\n\v\f\r().-]/g, '');
  return /^\+?[0-9]{2,20}$/.test(v) ? v : null;
}
