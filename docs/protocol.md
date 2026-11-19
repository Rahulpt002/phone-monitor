# Protocol: Agent ↔ Server ↔ Dashboard

The TypeScript types in [`shared/src/`](../shared/src) are the source of truth. This page explains the rules that types cannot express. Every message type has an example in [`shared/fixtures/`](../shared/fixtures). The Kotlin Agent's unit tests parse those files, so the two languages cannot drift apart silently.

| File | What it defines |
|------|-----------------|
| `telemetry.ts` | Telemetry groups (plan 6.2), tiers, capability map |
| `commands.ts` | Command names, arguments, confirm/re-auth/revert rules (plan 6.3, 6.4), command record and status |
| `settings.ts` | Every editable threshold with its default (plan 6.4, 6.5, 7.2, 8.2, 8.5) |
| `events.ts` | Event kinds, and which events raise alerts |
| `finder.ts` | Signal score formula, grades, Finder live/spot types, ranking (plan 7) |
| `protocol.ts` | WebSocket messages for both sockets |
| `api.ts` | REST endpoints, request and response bodies, error codes (plan 8.3) |
| `metrics.ts` | Chart metrics and their JSON paths |

## 1. General rules

- One JSON object per WebSocket text frame, with a `t` field naming the type.
- Receivers **ignore unknown `t` values and unknown fields**.
- Keys are `snake_case`. Timestamps are ISO-8601 UTC with milliseconds (`2026-11-19T10:15:00.000Z`).
- Values the phone cannot read are omitted or `null`, never invented.
- Kotlin: `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; classDiscriminator = "t" }`, one `@Serializable @SerialName("<t>")` class per message. Telemetry groups are flattened at the top level of the `telemetry` message (`{"t":"telemetry","seq":1,"ts":"...","tier":"fast","radio":{...},"net":{...}}`).
- Max frame size 512 KiB. Command output is truncated to 64 KiB.

## 2. Pairing (plan section 10)

1. An admin presses **Pair phone** in the Dashboard → `POST /pair/codes` → an 8-character one-time code (valid 15 min) plus:
   - `deep_link`: `hsm://pair?server=<url-encoded https base URL>&code=<code>`, shown as a QR code. Any camera app that reads QR codes opens it in the Agent.
   - `adb_command`: `adb shell am start -a android.intent.action.VIEW -d '<deep_link>'` for a phone with a dead screen.
   - The Agent's local UI also has a manual form (server URL + code).
2. The Agent calls `POST <server>/pair {code, device: DeviceInfo}` → `{device_id, device_token, ws_url}`.
3. The Agent stores `device_token` encrypted with an Android Keystore key and never shows or logs it. The Server keeps only its SHA-256.
4. Unpairing (`DELETE /devices/:id`) revokes the token. The Agent then receives close code 4001 and shows "Not paired".

## 3. Agent socket: `WS /ws/agent`

### 3.1 Connect

- The Agent opens `wss://<host>/ws/agent` with `Authorization: Bearer <device_token>`. A bad token gets `{"t":"error","code":"unauthorized"}`, then close code **4001**. On 4001 the Agent stops reconnecting until it is paired again.
- If the same device connects twice, the Server closes the older socket with `{"t":"error","code":"replaced"}`, then code **4002**.
- The Server's first message is **`hello`**: `server_time`, `device_id`, `settings`, `settings_version`, `rates`.
  - **Clock offset** = `server_time − local now` at receipt. The Agent uses it for command expiry (3.5). It does not change the phone's clock.
  - If `settings_version` differs from the version the Agent last applied, the Agent applies and persists the new settings and emits `settings_applied` event.
- Right after `hello`, the Agent sends a `static` telemetry message (device + capabilities), then fresh `slow` and `fast` readings, and starts draining its outbox.
- Reconnect uses exponential backoff with jitter: 2 s, 4 s, 8 s … up to 60 s. Backoff resets after a connection has stayed up for 60 s. Reconnect immediately on an Android network-available callback.
- OkHttp `pingInterval(20 s)` detects dead sockets. The app-level `heartbeat` message is separate (3.3).

### 3.2 Outbox, `seq` and `ack` (plan 6.1 "Sync")

These message types are **buffered**: `telemetry`, `event`, `finder_spot`, `finder_spot_deleted`, `sms`, `cmd_ack`, `cmd_result`, `cmd_revert`.

1. The Agent first inserts each one into its local outbox (Room). The table's autoincrement id becomes `seq`.
2. A single sender coroutine drains the outbox **in `seq` order**. A message produced while connected leaves right away. A backlog leaves in `batch` messages of at most 200 items.
3. After storing, the Server replies `{"t":"ack","seq":N}`, meaning every message with seq ≤ N is stored. The Agent then deletes those rows.
4. Unacked rows are re-sent after a reconnect, so the Server must handle duplicates:
   - telemetry: unique `(device_id, tier, ts)` → INSERT OR IGNORE
   - events: unique `event.id`
   - spots: upsert by `spot.id`
   - sms: unique `sms.id`
   - command reports: applied idempotently. A report never moves a command backwards, so a late `cmd_ack` after `cmd_result` is ignored.
5. Outbox cap is 50,000 rows. When full, the oldest `fast` telemetry is dropped first. Events and command reports are never dropped.
6. These are **not** buffered: `heartbeat`, `probe`, `finder_live`.

A `telemetry` message older than `PROTOCOL.live_window_s` (120 s) by Server time is backlog: it is stored and rolled up but not relayed live to Dashboards. The Server updates its "current state" only from newer timestamps, so backlog never overwrites fresh values.

### 3.3 Sending tiers (plan 8.2)

| Message | When |
|---------|------|
| `heartbeat` | every `telemetry.heartbeat_s` (30 s). The Server uses it for offline detection. On Render's free tier it also keeps the instance awake. |
| `telemetry` tier `fast` (radio, net) | every `rates.fast_s`: `fast_active_s` (5 s) while a Dashboard watches this device, otherwise `fast_idle_s` (60 s) |
| `finder_live` | every `mirror_s` (1 s) while `rates.mirror = true` |
| `telemetry` tier `slow` (power, heat, system, tether, traffic, position) | every `slow_s` (60 s) |
| `telemetry` tier `static` (device, capabilities) | on connect, after `capability_probe`, after a profile change |
| `event` | immediately |

The Server sends `rates` whenever the answer changes, based on which Dashboards are subscribed (5).

### 3.4 Settings

- The Server owns the settings. `PUT /settings` deep-merges, validates, increments `version`, stores, and pushes `{"t":"settings"}` to the Agent if it is connected. Otherwise the Agent gets them in the next `hello`.
- The Agent persists the last applied settings and version, and ships `DEFAULT_SETTINGS` (identical to `shared/fixtures/settings-default.json`, which a unit test checks). The watchdog works offline with the last applied copy.

### 3.5 Commands (plan 6.3 and 6.4)

```
Dashboard           Server                                   Agent
   | POST /commands   |                                        |
   |----------------->| validate role, confirm, re-auth        |
   |                  | store status=queued, created_at=now    |
   |                  |--- cmd {id,name,args,sent_at,...} ---->| (status=sent)
   |                  |                                        | expiry check: (now+offset) - sent_at > expiry_s -> reject 'expired'
   |                  |                                        | capability, policy, reboot-limit checks
   |                  |<-- cmd_ack {accepted} -----------------| (status=acked | rejected)
   |                  |                                        | execute
   |                  |<-- cmd_result {ok,output,revert_deadline}| (status=done | failed | revert_armed)
   |                  |<-- cmd_revert {kept|reverted} ---------| (status=kept | reverted)
```

- **Queueing:** if the Agent is offline, the Server holds the command and sends it on reconnect. After `commands.expiry_s` (60 s) from `created_at` the Server marks it `expired`. The Agent also rejects a command whose `sent_at` is too old by its offset-corrected clock (rule 6).
- **Checks on the Agent, in order:** expiry → name known and supported in the capability map (`unsupported`) → `power_off` blocked unless `allow_power_off` (`blocked`) → reboot limit: at most `max_reboots` per `reboot_window_h` from any source (`reboot_limit`) → argument validation (`invalid_args`) → conflicting command or watchdog step running (`busy`).
- **Role checks on the Server:** viewers cannot send commands (403). `admin_only` commands need an admin. `confirm` commands need `confirmed: true`, else 400 `confirmation_required`. `reauth` commands need the user's `password`, else 401 `reauth_required`. An admin without TOTP gets 403 `totp_enrollment_required` for any command or settings write.
- **Audit:** every state change is stored in `commands` and broadcast to Dashboards as `{"t":"command"}`. Agent-local actions (watchdog, local UI, schedule) are reported as `event` kind `command` or `watchdog_action` and stored in the timeline.

#### Auto-revert (rule 1)

The Server sets `revert_after_s` (from `commands.revert_after_s`) and `revert_confirm` on `cmd` when `COMMANDS[name].revertFor(args)` is not null.

1. The Agent records the **undo action** before it applies the change, for example `mobile_data {enabled:true}`. It persists `{command_id, undo, deadline, confirm_mode, applied_at}` in Room, so a revert survives an Agent restart or reboot.
2. `cmd_result` carries `revert_deadline = applied_at + revert_after_s`.
3. Confirmation:
   - `agent_link`: from `applied_at + REVERT_SETTLE_S` (10 s), the Agent sends `{"t":"probe","id"}` every 10 s while connected, and once after every reconnect. The first `probe_ack` for a probe sent at or after the settle time confirms the change: the Agent sends `cmd_revert {state:"kept"}`.
   - `dashboard`: the change is kept only when `{"t":"cmd_keep","id"}` arrives. The Server sends it after `POST /commands/:id/keep`.
4. When the deadline passes without confirmation, the Agent runs the undo action, sends `cmd_revert {state:"reverted"}` (buffered), and emits event `revert`.
5. At Agent start, any persisted armed revert that is past its deadline is reverted immediately.

### 3.6 Probe

`{"t":"probe","id":"p_…"}` → `{"t":"probe_ack","id":"p_…","server_time":"…"}`. The Server answers at once without storing anything.

## 4. Signal score (plan 7.2)

```
n_rsrp = clamp((RSRP + 120) / 40, 0, 1)
n_sinr = clamp((SINR + 5) / 30, 0, 1)     // missing SINR -> 0
n_rsrq = clamp((RSRQ + 20) / 12, 0, 1)    // missing RSRQ -> 0
score  = round1(100 * (w_sinr*n_sinr + w_rsrp*n_rsrp + w_rsrq*n_rsrq))   // missing RSRP -> null
```

`round1(x) = Math.round(x * 10) / 10`, which is JavaScript `Math.round` (half rounds up). In Kotlin use `floor(x * 10 + 0.5) / 10`. Test vectors: `shared/fixtures/score-cases.json`. For NR_SA use the SS-RSRP/SS-SINR/SS-RSRQ values. For NR_NSA, `radio.score` uses the LTE anchor, and the Dashboard also shows the NR leg. `radio.score` in telemetry is smoothed over `finder.smooth_s` seconds (mean of the readings in the window).

## 5. Dashboard socket: `WS /ws/dashboard`

1. The client connects and must send `{"t":"auth","token":"<session token>"}` within 10 s. The Server replies `auth_ok {user}` or `auth_failed` and closes (4001).
2. The client sends `{"t":"subscribe","device_id":"d_…","finder":false}` and sends it again whenever the screen changes (`finder:true` only while the Finder screen is visible).
3. The Server sends `snapshot {device: DeviceSnapshot}` for the subscribed device (or for every device when `device_id` is null), then live `telemetry`, `event`, `status`, `command`, `finder_live`, `finder_spot`, `finder_spot_deleted`, `alert`, `sms` (admins only), and `settings`.
4. `rates` for an Agent: `fast_s = fast_active_s` if at least one authenticated Dashboard is subscribed to that device (or to all), else `fast_idle_s`. `mirror = true` if at least one of those has `finder:true`.
5. The client sends `{"t":"ping"}` every 25 s and the Server answers `pong`. The client reconnects with backoff and re-sends `auth` + `subscribe`.

## 6. Offline detection and alerts (plan 8.5)

- The Server tracks `last_seen` (any Agent message) per device and persists it.
- "Phone offline" fires when `now − last_seen > alerts.offline_after_s` (180 s). The check runs every 15 s, and once at Server start (after a free-tier spin-down the Server reloads `last_seen` and the open offline alert from the database).
- "Back online" fires on the first message after an offline alert. It includes the reason if known: the Agent's `boot` / `agent_start` / `watchdog_action` events from the backlog, or the time spent offline.
- Alerts from events: see `EVENT_ALERT` in `events.ts`. Server-evaluated alerts: offline, back online, low battery, signal drop vs baseline, cell changed, tethering down, data quota.
- The same alert kind for the same device is not repeated within `alerts.cooldown_min`. "Back online" and quota steps are not subject to the cooldown.

## 7. Close codes

| Code | Meaning |
|------|---------|
| 1000 | normal |
| 4001 | unauthorized (bad or revoked token / session) |
| 4002 | replaced by a newer connection of the same device |
| 4003 | protocol error (bad message) |
