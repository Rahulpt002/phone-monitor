# Agent simulator

A Node CLI that pretends to be the outdoor hotspot phone. It pairs like the real Agent and opens
`WS /ws/agent`. It then speaks the full Agent protocol from [`docs/protocol.md`](../../docs/protocol.md)
section 3, so you can develop, demo and test the Server and the Dashboard without a rooted phone.

The simulated handset is a Galaxy S21 (`SM-G991B`, profile `samsung_galaxy_s`) on LTE band 40 with
an n78 NR leg. It sits on a roof with a USB-tethered router, a charge limit of 50 to 60 %, and diurnal
cell congestion and heat.

## Quick start (local server)

1. Start the Server (port 8080) and, if you want the UI, the Dashboard:

   ```sh
   npm run dev:server        # http://localhost:8080
   npm run dev:web           # http://localhost:5173 (proxies /api and /ws to 8080)
   ```

2. Create a pairing code. In the Dashboard, sign in, enroll TOTP (required for every account,
   plan 10) and open **Settings > Pair phone**. With the API, the admin must have enrolled TOTP
   already (a session without it gets `403 totp_enrollment_required` here). Log in, finish the
   TOTP step with a current code from the authenticator, and call `POST /api/pair/codes`:

   ```sh
   PENDING=$(curl -s localhost:8080/api/auth/login -H 'content-type: application/json' \
     -d '{"username":"admin","password":"<ADMIN_PASSWORD>"}' | jq -r .pending_token)
   TOKEN=$(curl -s localhost:8080/api/auth/totp -H 'content-type: application/json' \
     -d "{\"pending_token\":\"$PENDING\",\"code\":\"<6-digit code>\"}" | jq -r .token)
   curl -s -X POST localhost:8080/api/pair/codes -H "authorization: Bearer $TOKEN" \
     -H 'content-type: application/json' -d '{"name":"Simulator"}' | jq -r .code
   ```

3. Pair the simulator and run it (from the repo root):

   ```sh
   npm run sim -- --code ABCD2345
   ```

   The device id and token are saved to `tools/agent-sim/.sim-state.json`. That file is
   git-ignored because it holds the token. Later runs reuse the pairing:

   ```sh
   npm run sim
   npm run sim -- --scenario internet-loss --speed 20
   ```

From `tools/agent-sim` itself, `npx tsx src/index.ts --code ABCD2345` does the same.

Other servers:

```sh
npm run sim -- --server http://localhost:5173 --code ABCD2345       # through the Vite proxy
npm run sim -- --server https://hsm.onrender.com --code ABCD2345    # Render (free tier wakes in ~1 min)
npm run sim -- --server https://hsm.onrender.com --token <device token> [--device-id d_...]
```

To pair again (for example after the Server's database was reset), run with a new `--code`, or
delete `.sim-state.json`. Use `--state <file>` to run several simulated phones side by side, one
state file each.

## Options

| Option | Meaning |
|--------|---------|
| `--server <url>` | Server base URL. Default `http://localhost:8080`, or the URL saved at pairing. |
| `--code <code>` | Pair with a one-time code (`POST /api/pair`), then run. |
| `--token <t>`, `--device-id <id>` | Skip pairing and use an existing device token. |
| `--state <file>` | State file. Default `tools/agent-sim/.sim-state.json`. |
| `--scenario <name>` | Scenario to run. Repeatable or comma-separated (see below). |
| `--speed <x>` | Fast-forwards the simulated world x times: battery and heat physics, scenario timelines, the watchdog ladder, Finder tests and reboot downtime. Protocol timing stays real: heartbeat, telemetry intervals, command expiry and auto-revert deadlines. |
| `--offline-for <s>` | Once, `--offline-after` seconds (default 20) after the first `hello`, drops the link for `s` seconds. Telemetry keeps buffering, then the simulator reconnects and back-fills in `batch` frames. |
| `--keep-link` | Commands that would cut the phone's own link (mobile data off, airplane on, network mode) leave the socket up. The auto-revert probe then succeeds and the change is **kept**. |
| `--persist-outbox` | Keep unacked messages in the state file across restarts, like the real Agent's Room outbox. |
| `--clock-skew <s>` | Phone clock offset. Like the real Agent (`Clock.isoNow`), the simulator learns the Server's offset from `hello` and stamps every `ts`, `at` and `revert_deadline` it sends with the corrected clock, and uses it for command expiry. The phone's own clock (shell `date`, local-time decisions) stays skewed. |
| `--exit-on-replaced` | Exit with code 4 on close code 4002 instead of waiting 30 s and reconnecting like the real Agent. |
| `--seed <n>` | Reproducible random values. Ids stay unique. |
| `--verbose`, `-v` | Also log every frame, ack, probe and heartbeat. |
| `--no-color` | Plain output. Colour is also off when stdout is not a terminal or `NO_COLOR` is set. |

Exit codes: 0 normal or `power_off`, 1 error, 2 bad arguments or not paired, 3 token rejected (4001),
4 replaced by another connection (4002, only with `--exit-on-replaced`).

## Scenarios

Scenario effects start 10 simulated seconds after the first `hello`. All times below are
simulated, so divide them by `--speed`.

| Scenario | What happens | What the Server should see |
|----------|--------------|----------------------------|
| `normal` (default) | A steady signal random-walks around -91 dBm. SINR and RSRQ sag in the evening peak. Tether traffic follows household use. The battery cycles between 50 and 60 % (charge limit hysteresis). | fast/slow telemetry, occasional `charging_stopped` / `charging_resumed` |
| `internet-loss` | The upstream internet dies, so the link drops too. The watchdog cycles mobile data at 90 s and airplane mode at 180 s, then reboots at 600 s, which fixes it. | Agent offline. Afterwards a back-filled `batch` with `connectivity` and three `watchdog_action` events (data_cycle, airplane_cycle, reboot), then `boot` and `agent_start` |
| `overheat` | Sun on the box: the battery heads for 48 °C, the shade returns after 10 min, and it cools. | `temp_warning` (40 °C), then `temp_critical` and `charging_stopped {reason: heat}` (45 °C), then `temp_normal` and `charging_resumed` |
| `weak-power` | The USB port gives only 320 mA, less than the phone uses. | `current_ma` negative while plugged in, then `weak_power` after `watchdog.weak_power_min` (30 min) |
| `cell-flap` | Two cells of similar strength: the serving PCI flips between 211 and 97 every 15 to 60 s. | `cell_change` events, `cell_switches_5min` in `finder_live` |
| `power-cut` | Mains power goes, so USB power and the router are gone. The battery runs down (compressed to about 5 min), the phone dies, power returns 3 min later, and the phone boots by itself. After the cold boot USB tethering is off, so the watchdog's tether fix turns it back on. | `power_lost`, `usb_disconnected`, `tether_down`, offline, then `boot {boot_reason: cold,charger}`, `agent_start`, `watchdog_action {rule: tether}`, `tether_up` |

Useful combinations:

```sh
npm run sim -- --scenario internet-loss --speed 20        # full ladder in about 30 s
npm run sim -- --scenario overheat --speed 10
npm run sim -- --scenario weak-power --speed 60           # weak_power after about 30 s
npm run sim -- --scenario power-cut,cell-flap --speed 30
npm run sim -- --offline-for 3600                          # the plan's "one-hour cut is back-filled" test
```

If the battery reaches 0 % in any scenario, the phone shuts down. With a charger attached it powers on
again at 3 % (automatic power-on, plan 4.3). Combining `power-cut` with `weak-power` therefore shows the
boot loop a too-weak port would cause.

## Commands

Every command in `shared/src/commands.ts` is handled. The checks run in protocol order:

1. Expiry. The command's age is measured with the clock offset from `hello`.
2. `unsupported`. The capability map marks `network_mode` unsupported, as in the fixture. A
   `settings.profile_override` such as `{"network_mode": {"lte_only": "11"}}` makes it supported.
3. `blocked`. `power_off` is refused unless `commands.allow_power_off` is on.
4. `reboot_limit`. At most `max_reboots` per `reboot_window_h`, counting reboots from any source.
   The simulator also emits `reboot_blocked`.
5. `invalid_args`.
6. `busy`. A conflicting command or a watchdog step holds the radio, system or Finder.

`cmd_ack`, `cmd_result`, `cmd_revert` and events all go through the outbox, so they survive a
dropped link. A duplicate `cmd` id is never run twice. The Server re-sends a command it has no
`cmd_ack` for after a reconnect, so the simulator queues that command's latest report (`cmd_ack`
or `cmd_result`, same content and `ts`) again, unless that report is still in the outbox anyway.

| Command | Simulated effect / output |
|---------|---------------------------|
| `mobile_data`, `airplane_mode`, `network_mode` | Change the radio. Turning data off, turning airplane on, or changing the network mode **cuts the Agent's own link** for as long as the change lasts. The probe round-trip therefore cannot succeed, and the change is undone at `revert_deadline`. The report (`cmd_revert reverted` + `revert` event) arrives after the reconnect. With `--keep-link`, the probe after `REVERT_SETTLE_S` (10 s) succeeds and the change is kept. |
| `usb_tethering` | Off arms a `dashboard` revert: it is kept only when `cmd_keep` arrives. While switched off on purpose, the watchdog's tether fix leaves it alone. |
| `data_cycle`, `airplane_cycle` | Off for `off_seconds` (real seconds), then on again. The link drops during the cycle. |
| `hotspot`, `wifi`, `bluetooth`, `screen`, `brightness` | Flip the corresponding state. A hotspot adds a Wi-Fi client to `tether.clients`. |
| `charging`, `charge_limit` | Drive the power model. A manual stop is reported as `stop_reason: manual`; the heat stop still wins. |
| `reboot` | Result first, then about 20 s offline, then a `boot` event (`reboot,userrequested`) and `agent_start {reason: boot}`. |
| `power_off` | When allowed, the phone switches off and the simulator exits. A real phone would need a hand. |
| `app_list` | JSON array of `{pkg, enabled, system}`. |
| `app_force_stop`, `app_disable`, `app_enable` | Change the package list. Force-stopping `com.hotspotmonitor.agent` restarts the Agent (`agent_start {reason: restart}`). |
| `process_list` | `top -n 1` text. |
| `shell` | A fake root shell: `id`, `uname -a`, `uptime`, `getprop`, `cat` of the battery sysfs files, `ip addr`, `df`, `free`, `dumpsys battery`, `ping`, `pm list packages`, `settings get`, `echo`, `&&` and `;`. Unknown commands exit 127. The shell never changes the simulated state. |
| `ussd` | Carrier reply after 2 to 4 s. Try `*121#` (balance), `*333#` (plan) or `*1#` (own number). |
| `send_sms` | An outgoing `sms` record. An SMS to a short code (3 to 6 digits, for example `121` with body `BAL`) gets a carrier reply: an incoming `sms` plus an `sms_received` event. |
| `refresh`, `capability_probe` | Send the static, slow and fast tiers now / re-run the capability map and send the static tier. |
| `watchdog_simulate` | The internet check fails for `duration_s` (simulated) while the link stays up, as on the real Agent. The watchdog ladder then acts live. |
| `finder_spot_test` | Takes `duration_s` readings (default `finder.spot_test_s`, scaled by `--speed`). The orientation shifts the signal by a few dB. Then it sends a `finder_spot`. |
| `speed_test` | `finder.speed_runs` runs of about 12 s each. The result is a `SpeedSession` with medians, attached to `spot_id` if given (a new `finder_spot`). |
| `finder_set_baseline`, `finder_delete_spot` | Send `finder_spot` (old and new baseline) or `finder_spot_deleted`. |

## Telemetry and timing

- **Tiers.** The simulator sends a `heartbeat` every `telemetry.heartbeat_s` and fast telemetry
  (radio, net) every `rates.fast_s`; offline it falls back to `fast_idle_s`. It sends slow telemetry
  (power, heat, system, tether, traffic, position) every `slow_s`, and static telemetry (device,
  capabilities) after every `hello`, `refresh` and `capability_probe`, and after a profile override
  changes. While `rates.mirror` is true it sends `finder_live` every `mirror_s`, in `walk` mode.
- **Score.** `radio.score` is `signalScore` with the `finder` weights, averaged over `finder.smooth_s`.
- **Outbox.** Every buffered message gets the next `seq`. The outbox drains in order, and a backlog
  goes out in `batch` frames of at most 200 items. A row is deleted on `ack`, and unacked rows are re-sent
  after a reconnect, so the Server sees duplicates by design. When the outbox reaches 50,000 rows, the
  oldest fast telemetry is dropped first.
- **Connection.** The Agent sends `Authorization: Bearer <token>`. Reconnects back off at 2, 4, 8 s up
  to 60 s with jitter, and the backoff resets after 60 s connected. When the link comes back, the
  Agent reconnects at once, like Android's network-available callback. A WebSocket ping goes out
  every 20 s. On close code 4001 (or HTTP 401/403 on the upgrade) the simulator stops, tells you to
  pair again and removes the dead token. On 4002 (replaced by another connection with the same token)
  it waits 30 s before reconnecting, like the Kotlin Agent, and a returning link does not cut that
  wait short. `--exit-on-replaced` makes it exit instead.
- **Clock.** Every `ts` / `at` / `revert_deadline` is phone clock + the offset from `hello`
  (`last_boot_at`, `fix_at` and `internet_down_since` are converted the same way), so a phone with a
  wrong clock (`--clock-skew`) still looks live to the Server.
- **Settings.** `hello` and `settings` are applied when the version changes. The simulator persists
  them, emits `settings_applied`, and reports the version in `heartbeat.settings_version`.

## Console output

There is one line per notable event: connection changes, `hello`, rates, back-filled batches,
commands and their results, events, auto-reverts, watchdog steps and scenario steps.
`--verbose` adds every frame, ack and heartbeat.

```
14:26:22 scen   internet-loss: upstream internet lost until the watchdog reboots the phone
14:26:22 sim    link down: internet down; buffering (1 in outbox)
14:26:23 event  watchdog_action: Internet down 2 min: cycled mobile data, still down
14:26:31 sim    phone booted (reboot,watchdog), boot #43
14:26:31 tx     batch of 12 buffered messages (seq 6..17)
```

## Tests

```sh
cd tools/agent-sim && npx tsc --noEmit && npx vitest run
```

The tests cover these areas:

- The generators produce the same keys as `shared/fixtures/agent-to-server.json`.
- Outbox seq, ack, batch and cap logic.
- Every command check (expiry with clock offset, unsupported, blocked, reboot limit, invalid args, busy).
- Command outputs.
- The auto-revert state machine, with fake timers.
- Watchdog rules and score smoothing.
- The whole client against an in-process `ws` server: hello, drain, heartbeat, settings push,
  mirror, `--offline-for` back-fill, link-cutting revert, `--keep-link`, 4001, 4002 (30 s wait, or
  exit with `--exit-on-replaced`), reconnect with re-send, simulated reboot, and offset-corrected
  timestamps with `--clock-skew`.
- Duplicate `cmd` ids: never run twice, latest report re-sent.
