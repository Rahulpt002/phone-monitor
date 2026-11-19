/**
 * REST API (plan section 8.3). All endpoints are JSON over HTTPS. Except where noted, they need
 * `Authorization: Bearer <session token>` from /auth/login (+ /auth/totp).
 * Errors: HTTP status + `{ error: ApiErrorCode, message: string }`.
 *
 * Multi-device ready: every device-scoped endpoint takes `device_id`; when omitted the Server
 * uses the first paired device (plan 8.4: one phone today, more later).
 */
import type { CommandName, CommandRecord } from './commands.js';
import type { AgentEvent } from './events.js';
import type { FinderSpot } from './finder.js';
import type { AlertRecord, DeviceSnapshot, DeviceSummary, Role, SmsRecord, UserInfo } from './protocol.js';
import type { Settings } from './settings.js';
import type { DeviceInfo } from './telemetry.js';

export type ApiErrorCode =
  | 'bad_request'
  | 'unauthorized' // no / invalid / expired session
  | 'forbidden' // role does not allow it
  | 'totp_enrollment_required' // admin without TOTP tried a write action
  | 'reauth_required' // password missing or wrong for a reauth command
  | 'confirmation_required' // `confirmed: true` missing for a confirm command
  | 'locked' // too many failed logins (HTTP 429)
  | 'rate_limited' // HTTP 429
  | 'not_found'
  | 'conflict'
  | 'device_offline' // informational; commands are still queued until expiry
  | 'internal';

export interface ApiError {
  error: ApiErrorCode;
  message: string;
}

// --- auth -------------------------------------------------------------------------------------
// POST /auth/login (public, rate-limited, lockout after 5 failures for 15 min)
export interface LoginRequest {
  username: string;
  password: string;
}
export type LoginResponse =
  | { totp_required: false; token: string; user: UserInfo; expires_at: string }
  | { totp_required: true; pending_token: string }; // pending_token valid 5 min

// POST /auth/totp (public)
export interface TotpLoginRequest {
  pending_token: string;
  code: string;
}
export interface SessionResponse {
  token: string;
  user: UserInfo;
  expires_at: string;
}

// GET /auth/me
export interface MeResponse {
  user: UserInfo;
  /** True for admins without TOTP: the Dashboard must lead them to enroll before any write. */
  totp_enrollment_required: boolean;
}

// POST /auth/logout -> 204
// POST /auth/totp/setup -> { secret, otpauth_url } (secret is base32)
export interface TotpSetupResponse {
  secret: string;
  otpauth_url: string;
}
// POST /auth/totp/enable { code } -> { ok: true }
// POST /auth/totp/disable { password, code } -> { ok: true }
// POST /auth/password { current, next } -> { ok: true }  (min 10 chars)

// --- users (admin) ----------------------------------------------------------------------------
// GET /users -> UserInfo[]
// POST /users { username, password, role } -> UserInfo
// PATCH /users/:id { role?, password? } -> UserInfo
// DELETE /users/:id -> 204 (cannot delete the last admin or yourself)
export interface CreateUserRequest {
  username: string;
  password: string;
  role: Role;
}

// --- pairing ----------------------------------------------------------------------------------
// POST /pair/codes (admin) -> one-time pairing code valid 15 minutes
export interface PairCodeRequest {
  name?: string; // device display name
}
export interface PairCodeResponse {
  code: string; // 8 chars, A-Z2-9 without lookalikes
  expires_at: string;
  /** Opened by the phone's camera (QR) or by ADB; the Agent's PairActivity handles it. */
  deep_link: string; // hsm://pair?server=<urlencoded base url>&code=<code>
  adb_command: string; // adb shell am start -a android.intent.action.VIEW -d '<deep_link>'
}

// POST /pair (public, rate-limited) - called by the Agent
export interface PairRequest {
  code: string;
  device: DeviceInfo;
}
export interface PairResponse {
  device_id: string;
  device_token: string; // shown once; Server stores only its SHA-256
  ws_url: string; // wss://<host>/ws/agent
}

// --- devices ----------------------------------------------------------------------------------
// GET /devices -> DeviceSummary[]
// GET /device?device_id= -> DeviceSnapshot
// PATCH /devices/:id { name } (admin) -> DeviceSummary
// DELETE /devices/:id (admin, reauth: { password }) -> 204  revokes the token
export type DevicesResponse = DeviceSummary[];
export type DeviceResponse = DeviceSnapshot;

// --- telemetry history ------------------------------------------------------------------------
// GET /telemetry?device_id=&metric=<MetricKey>&from=<ISO>&to=<ISO>&res=auto|raw|1m
// res=auto: raw if the range <= 6 h, otherwise 1m averages. Max 5000 points (downsampled).
export interface TelemetryHistoryResponse {
  metric: string;
  res: 'raw' | '1m';
  points: [number, number | null][]; // [unix ms, value]
}

// --- commands ---------------------------------------------------------------------------------
// POST /commands -> 201 CommandRecord
export interface SendCommandRequest {
  device_id?: string;
  name: CommandName;
  args?: Record<string, unknown>;
  confirmed?: boolean; // required when COMMANDS[name].confirm
  password?: string; // required when COMMANDS[name].reauth
}
// POST /commands/:id/keep -> CommandRecord (only for revert_armed + revert_confirm 'dashboard')
// GET /commands?device_id=&limit=50&before=<ISO> -> CommandRecord[] (newest first)
export type CommandsResponse = CommandRecord[];

// --- events -----------------------------------------------------------------------------------
// GET /events?device_id=&from=&to=&kind=&limit=100&before=<ISO> -> StoredEvent[] (newest first)
export interface StoredEvent extends AgentEvent {
  device_id: string;
}

// --- settings ---------------------------------------------------------------------------------
// GET /settings?device_id= -> SettingsResponse
// PUT /settings (admin) { device_id?, settings: <partial Settings> } -> SettingsResponse
//   deep-merged over the current settings, validated, version += 1, pushed to the Agent.
export interface SettingsResponse {
  device_id: string;
  settings: Settings;
  version: number;
}

// --- finder -----------------------------------------------------------------------------------
// GET /finder/spots?device_id= -> FinderSpotsResponse (ranked: speed first, score second)
export interface FinderSpotsResponse {
  spots: FinderSpot[];
  baseline_id: string | null;
}

// --- alerts and push --------------------------------------------------------------------------
// GET /alerts?device_id=&limit=100&before= -> AlertRecord[] (newest first)
// POST /alerts/test (admin) -> AlertRecord   sends a 'server_test' alert on every channel
// GET /push/vapid -> { public_key } (public key for PushManager.subscribe)
// POST /push/subscribe { subscription: PushSubscriptionJSON } -> 204
// POST /push/unsubscribe { endpoint } -> 204
export type AlertsResponse = AlertRecord[];

// --- messages ---------------------------------------------------------------------------------
// GET /sms?device_id=&limit=100 -> SmsRecord[] (newest first, admin only)
export type SmsResponse = SmsRecord[];

// --- misc -------------------------------------------------------------------------------------
// GET /healthz (public) -> { ok: true, version, uptime_s, db: 'ok' | 'error' }
// GET /config  (public) -> PublicConfig
export interface PublicConfig {
  version: string;
  telegram_configured: boolean;
  push_configured: boolean;
}
