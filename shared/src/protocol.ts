/**
 * WebSocket protocol (plan section 8.2). Human-readable spec with sequence diagrams:
 * docs/protocol.md. Example JSON for every message: shared/fixtures/*.json.
 *
 * Two sockets:
 *  - WS /ws/agent      Agent <-> Server. Auth: `Authorization: Bearer <device_token>` on upgrade.
 *  - WS /ws/dashboard  Dashboard <-> Server. Auth: first message `{t:"auth", token}`.
 *
 * Every message is one JSON object with a `t` (type) field. Unknown `t` values and unknown
 * fields must be ignored by every receiver (forward compatibility).
 */
import type { CommandName, CommandRecord, RevertConfirm } from './commands.js';
import type { AgentEvent } from './events.js';
import type { FinderLive, FinderSpot } from './finder.js';
import type { Settings } from './settings.js';
import type { DeviceInfo, TelemetryGroups, TelemetryTier } from './telemetry.js';

// ---------------------------------------------------------------------------------------------
// Agent -> Server
// ---------------------------------------------------------------------------------------------

/**
 * Buffered messages carry `seq`: a positive, strictly increasing integer assigned by the
 * Agent's local outbox (Room autoincrement). The Agent writes them to the outbox first and a
 * single sender drains the outbox in seq order. The Server replies `ack {seq}` after storing,
 * and the Agent deletes everything <= seq. `seq` may restart from 1 after an app data wipe;
 * the Server does not rely on it across connections (dedup uses ids / unique keys instead).
 */
export interface TelemetryMsg extends TelemetryGroups {
  t: 'telemetry';
  seq: number;
  ts: string; // when the reading was taken (not when it was sent)
  tier: TelemetryTier;
}

export interface EventMsg {
  t: 'event';
  seq: number;
  event: AgentEvent;
}

export interface FinderSpotMsg {
  t: 'finder_spot'; // insert or replace by spot.id
  seq: number;
  spot: FinderSpot;
}

export interface FinderSpotDeletedMsg {
  t: 'finder_spot_deleted';
  seq: number;
  spot_id: string;
}

export interface SmsRecord {
  id: string; // "m_" + random (Agent)
  direction: 'in' | 'out';
  peer: string; // phone number or sender id
  body: string;
  at: string;
}

export interface SmsMsg {
  t: 'sms';
  seq: number;
  sms: SmsRecord;
}

/**
 * Command reports are buffered too, so a result produced while offline (for example an
 * auto-revert of "mobile data off") still reaches the Server after reconnecting.
 * The Server applies them idempotently (a repeated report does not change the outcome).
 */
export type BufferedMsg =
  | TelemetryMsg
  | EventMsg
  | FinderSpotMsg
  | FinderSpotDeletedMsg
  | SmsMsg
  | CmdAckMsg
  | CmdResultMsg
  | CmdRevertMsg;

/** Backlog upload: up to 200 buffered messages in seq order. */
export interface BatchMsg {
  t: 'batch';
  items: BufferedMsg[];
}

/** Every heartbeat_s (30 s). Not buffered. Also keeps a free Render instance awake. */
export interface HeartbeatMsg {
  t: 'heartbeat';
  ts: string;
  uptime_s: number;
  agent_uptime_s: number;
  outbox: number; // messages waiting in the local buffer
  settings_version: number; // last applied settings version (0 = defaults)
}

export type CmdRejectReason =
  | 'expired' // arrived more than commands.expiry_s after sent_at (plan 6.4 rule 6)
  | 'unsupported' // not in the capability map
  | 'blocked' // power_off while allow_power_off = false, or other policy
  | 'reboot_limit' // plan 6.4 rule 3
  | 'invalid_args'
  | 'busy'; // a conflicting command or watchdog step is running

export interface CmdAckMsg {
  seq: number;
  t: 'cmd_ack';
  id: string;
  accepted: boolean;
  reason?: CmdRejectReason | null;
  ts: string;
}

export interface CmdResultMsg {
  seq: number;
  t: 'cmd_result';
  id: string;
  ok: boolean;
  output: string; // stdout or human summary, may be ""
  error?: string | null;
  duration_ms: number;
  /** Set when auto-revert is armed: the Agent undoes the change at this time unless confirmed. */
  revert_deadline?: string | null;
  ts: string;
}

export interface CmdRevertMsg {
  seq: number;
  t: 'cmd_revert';
  id: string;
  state: 'kept' | 'reverted';
  detail?: string | null;
  ts: string;
}

/** Round-trip check used to confirm `agent_link` auto-reverts. */
export interface ProbeMsg {
  t: 'probe';
  id: string;
}

/** 1/s while the Server says `rates.mirror = true`. Not buffered, not stored, relayed to Dashboards. */
export interface FinderLiveMsg {
  t: 'finder_live';
  live: FinderLive;
}

export type AgentToServer =
  | BufferedMsg
  | BatchMsg
  | HeartbeatMsg
  | ProbeMsg
  | FinderLiveMsg;

// ---------------------------------------------------------------------------------------------
// Server -> Agent
// ---------------------------------------------------------------------------------------------

export interface Rates {
  fast_s: number; // current fast-tier interval chosen by the Server (fast_active_s or fast_idle_s)
  mirror: boolean; // stream finder_live 1/s
}

/** First message after a successful upgrade. */
export interface HelloMsg {
  t: 'hello';
  server_time: string; // the Agent computes clock offset = server_time - local now
  device_id: string;
  settings: Settings;
  settings_version: number;
  rates: Rates;
}

export interface SettingsMsg {
  t: 'settings';
  settings: Settings;
  version: number;
}

export interface RatesMsg extends Rates {
  t: 'rates';
}

export interface CmdMsg {
  t: 'cmd';
  id: string;
  name: CommandName;
  args: Record<string, unknown>;
  sent_at: string; // when the user issued it (Server clock)
  user?: string | null;
  revert_after_s?: number | null; // set when auto-revert must be armed
  revert_confirm?: RevertConfirm | null;
}

/** A user pressed "Keep" for a revert_armed command with revert_confirm = 'dashboard'. */
export interface CmdKeepMsg {
  t: 'cmd_keep';
  id: string;
}

export interface AckMsg {
  t: 'ack';
  seq: number; // all buffered messages with seq <= this are stored
}

export interface ProbeAckMsg {
  t: 'probe_ack';
  id: string;
  server_time: string;
}

export interface ErrorMsg {
  t: 'error';
  code: 'unauthorized' | 'bad_message' | 'replaced' | 'internal';
  message: string;
}

export type ServerToAgent =
  | HelloMsg
  | SettingsMsg
  | RatesMsg
  | CmdMsg
  | CmdKeepMsg
  | AckMsg
  | ProbeAckMsg
  | ErrorMsg;

// ---------------------------------------------------------------------------------------------
// Dashboard <-> Server
// ---------------------------------------------------------------------------------------------

export type Role = 'admin' | 'viewer';

export interface UserInfo {
  id: number;
  username: string;
  role: Role;
  totp_enabled: boolean;
  created_at: string;
}

export interface DeviceSummary {
  device_id: string;
  name: string;
  online: boolean;
  last_seen: string | null;
  paired_at: string;
  info: DeviceInfo | null;
}

export interface DeviceSnapshot extends DeviceSummary {
  connected_since: string | null;
  /** Latest value of every group, merged from the newest telemetry of each tier. */
  state: TelemetryGroups;
  /** When each group was last updated. */
  state_ts: Partial<Record<keyof TelemetryGroups, string>>;
  settings_version: number;
  baseline: FinderSpot | null;
  /** Agent-reported heartbeat details. */
  outbox: number | null;
  /** Commands currently in revert_armed state, for the countdown. */
  pending_reverts: CommandRecord[];
}

export type AlertSeverity = 'info' | 'warning' | 'critical';

export interface AlertRecord {
  id: number;
  device_id: string | null;
  kind: import('./settings.js').AlertKind;
  severity: AlertSeverity;
  title: string;
  message: string;
  ts: string;
  channels: ('telegram' | 'push')[]; // where it was delivered
  data?: Record<string, unknown> | null;
}

// Dashboard -> Server
export interface DashAuthMsg {
  t: 'auth';
  token: string;
}
/** Sent after auth and whenever the visible device or the Finder screen changes. */
export interface DashSubscribeMsg {
  t: 'subscribe';
  device_id: string | null; // null = all devices (overview)
  finder: boolean; // Finder screen open -> mirror on (rates.mirror)
}
export interface DashPingMsg {
  t: 'ping';
}
export type DashboardToServer = DashAuthMsg | DashSubscribeMsg | DashPingMsg;

// Server -> Dashboard
export interface DashAuthOkMsg {
  t: 'auth_ok';
  user: UserInfo;
}
export interface DashAuthFailedMsg {
  t: 'auth_failed';
  message: string;
}
export interface DashSnapshotMsg {
  t: 'snapshot';
  device: DeviceSnapshot;
}
/** Live telemetry relay (backlog uploads are not relayed; the Dashboard refetches history). */
export interface DashTelemetryMsg extends TelemetryGroups {
  t: 'telemetry';
  device_id: string;
  ts: string;
  tier: TelemetryTier;
}
export interface DashEventMsg {
  t: 'event';
  device_id: string;
  event: AgentEvent;
}
export interface DashStatusMsg {
  t: 'status';
  device_id: string;
  online: boolean;
  last_seen: string | null;
  connected_since: string | null;
}
export interface DashCommandMsg {
  t: 'command';
  command: CommandRecord;
}
export interface DashFinderLiveMsg {
  t: 'finder_live';
  device_id: string;
  live: FinderLive;
}
export interface DashFinderSpotMsg {
  t: 'finder_spot';
  device_id: string;
  spot: FinderSpot;
}
export interface DashFinderSpotDeletedMsg {
  t: 'finder_spot_deleted';
  device_id: string;
  spot_id: string;
}
export interface DashAlertMsg {
  t: 'alert';
  alert: AlertRecord;
}
export interface DashSmsMsg {
  t: 'sms';
  device_id: string;
  sms: SmsRecord;
}
export interface DashSettingsMsg {
  t: 'settings';
  device_id: string;
  settings: Settings;
  version: number;
}
export interface DashPongMsg {
  t: 'pong';
}

export type ServerToDashboard =
  | DashAuthOkMsg
  | DashAuthFailedMsg
  | DashSnapshotMsg
  | DashTelemetryMsg
  | DashEventMsg
  | DashStatusMsg
  | DashCommandMsg
  | DashFinderLiveMsg
  | DashFinderSpotMsg
  | DashFinderSpotDeletedMsg
  | DashAlertMsg
  | DashSmsMsg
  | DashSettingsMsg
  | DashPongMsg;

/** Limits shared by both ends. */
export const PROTOCOL = {
  version: 1,
  max_batch_items: 200,
  max_message_bytes: 512 * 1024,
  max_output_bytes: 64 * 1024,
  /** Live telemetry older than this is treated as backlog (stored, not relayed live). */
  live_window_s: 120,
} as const;
