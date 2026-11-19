# Outdoor Hotspot Phone: Remote Monitoring and Control

Project plan, version 1.1 (19 November 2026)

Device-agnostic: no phone model is fixed yet. A used Samsung Galaxy S with a broken screen is the likely first device (see 4.6 and Appendix A).

---

## 1. Goal

An Android phone is mounted outdoors at the spot with the best SIM signal. A single USB cable powers the phone and carries its internet (USB tethering) to a router or PC indoors. After installation nobody touches the phone, so it must be:

- **Visible**: every important status (signal, speed, battery, heat, tethering, system health) can be seen from anywhere.
- **Controllable**: radios, tethering, hotspot, charging, apps and reboot can be switched remotely.
- **Self-healing**: it recovers from lost internet, dropped tethering and overheating with no human action.
- **Well placed**: a Signal Finder (the "range finder") helps choose the mounting spot before installation.

---

## 2. Key decisions

### 2.1 How many apps?

Build **one Android app and one web app**, plus a small server.

| # | Part | Runs on | What it is |
|---|------|---------|------------|
| 1 | **Agent** | The hotspot phone (rooted) | Android app in Kotlin. Collects status, executes commands, runs the watchdog, contains the Signal Finder. |
| 2 | **Server** | A small cloud machine | Relays between Agent and Dashboard, stores history, sends alerts. |
| 3 | **Dashboard** | Any browser, and the monitoring phone | Web app built as a PWA. It installs on the monitoring phone's home screen and behaves like an app (full screen, push notifications). |

Why this and not the alternatives:

- **Not a second native app for monitoring.** It would be the same dashboard built twice. The PWA covers laptop and phone with one codebase. If a native app is ever wanted, the PWA can be wrapped with Capacitor later.
- **Not a single Android app with two logins.** The hotspot side needs root, a 24/7 background service and many sensitive permissions. The monitoring side needs none of that. The "different logins" idea lives in the Dashboard instead, as two roles: **admin** (monitor and control) and **viewer** (monitor only).

### 2.2 Other decisions

| Topic | Decision | Reason |
|-------|----------|--------|
| Root | Yes (Magisk or KernelSU) | A normal app cannot switch tethering, airplane mode, charging or reboot from the background. With root each is one shell command. |
| Phone model | Not fixed. The Agent works on any rooted Android phone | Everything that differs per model sits in one small device profile (6.1). Changing phones means writing a new profile, not changing the app. |
| Remote reachability | The Agent opens an outbound secure WebSocket to the Server and keeps it open | Mobile carriers put SIMs behind carrier-grade NAT, so nothing on the internet can connect into the phone. Outbound connections always work. |
| Recovery logic | Runs on the phone, not on the server | When the internet is down, remote commands cannot arrive. The phone has to fix itself. |
| Signal Finder | Inside the Agent, mirrored live to the Dashboard | You carry the hotspot phone around. The mirror lets you read values on a second phone while the first is held up on a pole or roof edge. |
| Full screen view and touch control | Use scrcpy over a private VPN (Tailscale); do not build it | Mature free tool. It covers anything the Dashboard has no button for. |
| Alerts | Telegram bot and browser push | Free, instant, no extra app to build. |

**Lite variant (no server):** the Agent serves the Dashboard itself and you reach it through Tailscale. Less to build and host, but there is no alert when the phone goes offline and no history while it is down. Use it only as a stepping stone.

---

## 3. Architecture

```
            OUTDOORS                                        INDOORS

   SIM   +---------------------+    one USB cable     +------------------+
  4G/5G  |   Hotspot phone     |   (power + data)     |  Router or PC    |     Wi-Fi / LAN
 ~~~~~~~>|   Agent app (root)  |<====================>|  with USB port   |<--> your devices
         +----------+----------+                      +------------------+
                    |
                    |  outbound secure WebSocket (over the SIM connection)
                    v
         +---------------------+
         |   Server (cloud)    |   stores history, relays commands, sends alerts
         +----------+----------+
                    ^
                    |  HTTPS + WebSocket
                    |
         +---------------------+
         |   Dashboard (PWA)   |   laptop browser, or installed on the monitoring phone
         +---------------------+
```

### Three ways to reach the phone

| Path | Used when | How |
|------|-----------|-----|
| Cloud (main) | Normal operation | Dashboard <-> Server <-> Agent |
| LAN (Phase 6) | SIM internet is down but the USB link is up | The Agent also serves a small token-protected API on the tether interface. Open the phone's tether gateway IP from any device on the home network. |
| SMS (Phase 6) | No data at all, but the phone is still registered on the network | Text a short command (STATUS, DATA, REBOOT) from a whitelisted number with a PIN. |

---

## 4. Hardware and installation (settle this before writing code)

These six points decide whether the project works. Software cannot fix any of them afterwards.

### 4.1 Power budget

A standard USB 2.0 port supplies at most 0.5 A (2.5 W). A USB 3.0 port supplies 0.9 A (4.5 W). A charging-capable data port can give up to 1.5 A. A phone serving tethered traffic uses very roughly 1 to 4 W, and more when the signal is weak or on 5G.

If the port gives less than the phone uses, the battery drains slowly and the phone dies after hours or days even though it is plugged in.

- Phase 0 test: 24 hours under real load on the real port and cable, logging battery percent and current.
- The Dashboard always shows net battery current and alerts on "plugged in but discharging".
- Fixes if power is short: use a USB 3.0 or charging-capable port, keep the screen off, switch off Wi-Fi and Bluetooth, use a shorter or thicker cable, or add a powered USB hub that passes data.

### 4.2 Battery safety

A lithium battery held at 100% in a hot box for months swells and can catch fire. This is the biggest safety risk in the project.

- Limit charge to about 50 to 60% using root. The control file differs per phone; the open-source ACC (Advanced Charging Controller) project documents them for many models.
- If the phone supports an idle or bypass mode (phone runs from USB while the battery rests), use it.
- Stop charging automatically above 45 °C.
- Shade the box. Never direct sun. Light-coloured enclosure.
- Look at the phone every few months for swelling.

### 4.3 Automatic power-on

After a long power cut the battery empties. When power returns, most phones show a charging animation and stay off. The phone must boot by itself when it receives power.

- On phones with fastboot (Pixel, Motorola, OnePlus and others): `fastboot oem off-mode-charge 0`.
- On phones without fastboot, including Samsung: a root change that makes the charging-only mode reboot into Android. Ready-made Magisk modules exist for this (for example magisk-autoboot), but support varies by model.
- Prove it in Phase 0. If the phone cannot do it, use a different phone. This is a must-have.

### 4.4 Cable length

- Passive USB 2.0 is reliable up to about 5 m.
- For longer distances, keep the USB cable short and move the router close to the phone, then run Ethernet indoors (good for 100 m). An active USB extension cable is the second choice.
- Use a cable with thick power wires. Thin cables lose voltage and slow the charging.
- Outdoor-rated cable or conduit, with a drip loop before it enters the wall.

### 4.5 Enclosure and mounting

- Plastic IP65 box. Metal blocks the signal.
- Cable enters from below through a gland. Silica gel packs inside. A small roof or shade above.
- Mount the phone at the same height and in the same orientation as the winning survey spot (section 7).
- Fix it firmly. The Agent raises an alert if tilt or location changes.

### 4.6 Choosing the phone

The plan does not depend on a model. Any Android phone works if it passes this list. A phone with a broken screen or back glass is a good cheap choice, because the screen is never used after setup.

Must work:

- Mainboard, SIM slot and mobile data. Test with your own SIM.
- The USB port carries data, not only charging. Connect it to a PC and check that the phone is detected.
- The bootloader can be unlocked. Without this there is no root.
- Clean IMEI (not blacklisted), no Google account lock (FRP), no carrier lock, no finance lock.
- No water damage. Corrosion gets worse outdoors.
- Volume and power buttons. The unlock steps need them.
- Supports the bands your carrier uses, including 5G if you want it.

Strongly preferred:

- The display still shows a picture, even if cracked. Unlocking and rooting use boot screens that are very hard to do blind.
- Working speaker. The Signal Finder beeps and talks.
- If only touch is dead, a USB mouse through an OTG adapter works.

Always:

- Fit a new battery before deployment. A used battery of unknown age in a hot box is the main fire risk (4.2). Reject any phone whose back is lifting: that is a swollen battery.

---

## 5. Preparing the hotspot phone

- [ ] Unlock the bootloader and root (Magisk or KernelSU). A clean ROM such as LineageOS is preferred: fewer background apps, less heat.
- [ ] Remove the screen lock and the SIM PIN, so the phone starts fully after an unattended reboot.
- [ ] No personal accounts or data on this phone. It is unlocked and outdoors.
- [ ] Turn off automatic system updates. An update can remove root or wait for a tap.
- [ ] Developer options: Default USB configuration = USB tethering.
- [ ] Location ON permanently. Android only gives cell and signal details to apps while location is on.
- [ ] Agent permissions: root granted permanently, battery optimization off, location "Allow all the time", phone, notifications, usage access, SMS (optional).
- [ ] Automatic power-on enabled and tested (4.3).
- [ ] Charge limit set and tested (4.2).
- [ ] Screen timeout 15 s, brightness at minimum, Wi-Fi and Bluetooth off unless used.
- [ ] Disable or remove apps that are not needed.
- [ ] Optional: Tailscale and ADB over network for scrcpy, firewalled so the ADB port is reachable only through the VPN interface.

---

## 6. Agent app (hotspot phone)

### 6.1 Structure

| Module | Job |
|--------|-----|
| Service core | Foreground service started at boot (type `location`, so cell data keeps flowing with the screen off). A root keep-alive script in Magisk's `service.d` restarts it if it ever dies. |
| Root shell | One wrapper (libsu) through which every privileged command passes. Logs each call. |
| Collectors | Read radio, network, tethering, power, heat and system status. |
| Command executor | Receives commands, checks them against the capability map, runs them, reports the result, handles auto-revert. |
| Watchdog | Local recovery rules (6.5). |
| Signal Finder | Section 7. |
| Sync | WebSocket client with automatic reconnect. Stores telemetry in a local database while offline and uploads the backlog later. |
| Local UI | Status, Finder, Pairing (QR code), Settings. |

**Capability map:** at first start the Agent tests which commands this phone supports and reports the list. The Dashboard shows only the controls that work. This handles the differences between Android versions and brands.

**Device profile:** everything that differs per phone model sits in one small file: the charge-control path, the tethering function name, the automatic power-on method, and any command that needs a different form. Supporting a new phone means adding a profile.

**Headless operation:** the Agent must be fully usable with a dead screen. Pairing works by ADB command as well as by QR code, every setting is reachable from the Dashboard, and the Signal Finder can be driven with the volume buttons and spoken feedback.

### 6.2 What it monitors

| Group | Fields | Source |
|-------|--------|--------|
| Radio | Operator, network type (4G, 5G NSA, 5G SA), band, channel number, PCI, cell ID, TAC, RSRP, RSRQ, SINR, RSSI, CQI, timing advance, neighbour cells, SIM state, roaming | `TelephonyManager.getAllCellInfo()`, `requestCellInfoUpdate()`, `TelephonyCallback` |
| Internet | Online check (HTTP 204), ping latency and loss, DNS check, public IP, IPv4 and IPv6 | Agent's own probes |
| Traffic | Download and upload speed now, totals per interface, usage today and this billing cycle | `TrafficStats`, `/sys/class/net/<if>/statistics`, `NetworkStatsManager` |
| Tethering | USB connected, USB function (rndis or ncm), tether interface and IP, hotspot on or off, connected clients | USB state broadcast, `ip addr`, `ip neigh` (root) |
| Power | Battery percent, status, charge current in mA, voltage, health, plug type, charge-limit state | `BatteryManager`, `/sys/class/power_supply/*` |
| Heat | Battery temperature, CPU, modem and skin temperatures, thermal status | `BatteryManager`, `/sys/class/thermal/thermal_zone*`, `PowerManager` thermal status |
| System | Uptime, boot count and reason, CPU load, RAM, storage, top processes, screen state, Agent and Android version | `/proc`, `ActivityManager`, `StorageStatsManager` |
| Position | GPS location, tilt and motion | Location API, accelerometer |
| Events | Connectivity changes, cell changes, tether up or down, power connected or lost, reboots, watchdog actions, commands | Broadcast receivers and callbacks |
| Messages | Incoming SMS, USSD replies (balance and plan checks) | SMS receiver, `sendUssdRequest()` |

### 6.3 What it controls

The commands below are starting points. They vary between Android versions and manufacturers, so each one is verified on the real phone in Phase 0.

| Control | Root shell command |
|---------|--------------------|
| Mobile data on / off | `svc data enable` / `svc data disable` |
| Airplane mode on / off | `cmd connectivity airplane-mode enable` / `disable` |
| USB tethering on / off | `svc usb setFunctions rndis` (or `ncm`) / `svc usb setFunctions` |
| Wi-Fi hotspot on / off, name and password | `cmd wifi start-softap <name> wpa2 <password>` / `cmd wifi stop-softap` |
| Wi-Fi on / off | `svc wifi enable` / `svc wifi disable` |
| Bluetooth on / off | `svc bluetooth enable` / `svc bluetooth disable` |
| Network mode (4G only, 5G + 4G, auto) | `cmd phone set-allowed-network-types-for-users ...` (device-dependent) |
| Reboot | `svc power reboot` |
| Screen sleep / wake, brightness | `input keyevent 223` / `224`, `settings put system screen_brightness <0-255>` |
| Charging on / off, charge limit | Write to the phone's charge-control file (found in Phase 0) |
| Apps and processes | `am force-stop <pkg>`, `pm disable-user --user 0 <pkg>`, `top -n 1` |
| Send SMS, run USSD code | Android telephony APIs |
| Root shell | Any command (admin only) |
| Full screen view and touch | scrcpy over ADB through Tailscale |

Not available on most phones: locking to a specific band or tower. Standard Android has no interface for it. Some models have a hidden service menu; treat it as a per-device extra.

### 6.4 Command safety rules

1. **Auto-revert.** Any command that can cut the phone's own connection (mobile data off, airplane mode on, USB tethering off, network mode change) starts a timer. If the Agent cannot reach the Server again within 120 seconds, it undoes the change by itself.
2. **Power off is blocked by default.** A phone that is switched off needs a hand to switch it on.
3. **Reboot limit.** At most 3 reboots in 6 hours, from any source.
4. **Confirmation.** Reboot, root shell and anything destructive need a second confirmation in the Dashboard.
5. **Audit.** Every command is stored with who sent it, when, the result and the output.
6. **Expiry.** A command that reaches the phone more than 60 seconds after it was sent is discarded.

### 6.5 Watchdog (runs on the phone, needs no internet)

| Trigger | Action |
|---------|--------|
| Internet check fails for 90 seconds | Mobile data off, then on |
| Still down after 3 minutes | Airplane mode on for 10 seconds, then off |
| Still down after 10 minutes | Reboot (within the reboot limit, then retry hourly) |
| USB connected but tethering is off | Turn tethering back on |
| Battery above the charge limit | Stop charging; resume 10% below the limit |
| Battery at 40 °C | Warning alert |
| Battery at 45 °C | Stop charging until below 40 °C; critical alert |
| Battery at 50 °C | Also rest the radio for 10 minutes |
| Plugged in but battery falling for 30 minutes | "Power supply too weak" alert |
| USB power lost | Log the event, alert as soon as possible |
| Agent service stopped | Root keep-alive script restarts it |
| Phone tilted or moved | Alert with location |
| Optional schedule | Reboot once a week at 04:00 |

Every threshold is editable from the Dashboard. Every watchdog action is logged and reported.

---

## 7. Signal Finder

Purpose: find the mounting spot that gives the best real internet speed, then keep a baseline to compare against later.

### 7.1 What it measures

| Metric | What it tells you | Excellent | Good | Fair | Poor |
|--------|-------------------|-----------|------|------|------|
| RSRP (dBm) | Signal strength | above -80 | -80 to -90 | -90 to -100 | below -100 |
| SINR (dB) | How clean the signal is. Best predictor of speed. | above 20 | 13 to 20 | 0 to 13 | below 0 |
| RSRQ (dB) | Quality including load and interference | above -10 | -10 to -15 | -15 to -20 | below -20 |

It also shows the band, the serving cell (PCI) and timing advance (a rough distance to the tower). For 5G the same logic uses SS-RSRP, SS-SINR and SS-RSRQ. Strong bars alone do not mean fast internet: a clean signal from a less crowded cell often beats a strong signal from a busy one.

### 7.2 Score (0 to 100)

```
n_rsrp = clamp((RSRP + 120) / 40, 0, 1)      // -120 dBm = 0,  -80 dBm = 1
n_sinr = clamp((SINR + 5) / 30, 0, 1)        //  -5 dB   = 0,  25 dB   = 1
n_rsrq = clamp((RSRQ + 20) / 12, 0, 1)       //  -20 dB  = 0,  -8 dB   = 1

score = 100 * (0.45 * n_sinr + 0.35 * n_rsrp + 0.20 * n_rsrq)
```

Readings are refreshed about once per second and smoothed over 5 seconds. The weights are a starting point, to be tuned against measured speeds.

### 7.3 Modes

| Mode | What it does |
|------|--------------|
| Walk | Large live score, colour bar, and sound: the tone rises as the score rises (like a metal detector), with the score spoken every few seconds. You can hold the phone up and listen without seeing the screen. |
| Spot test | Hold still for 30 seconds. Saves average, minimum, maximum and stability of each metric, serving cell, GPS position, compass direction, phone orientation, a name and an optional photo. |
| Speed test | Download, upload, ping and jitter at a saved spot. Three runs. Each run uses roughly 10 to 30 MB of data. |
| Headless | For a phone with a broken screen: volume up starts a spot test, volume down runs a speed test, and results are spoken aloud. |
| Mirror | Streams the live score to the Dashboard on the monitoring phone once per second. |
| Ranking | Lists saved spots, sorted by measured speed first and score second. Exports CSV. |
| Baseline | After installation, stores the winning spot's numbers. The Dashboard later compares live values against them. |

### 7.4 Survey procedure

1. **Walk** slowly past every candidate place: roof corners, each outside wall, balcony, window ledges. Mark where the tone is highest.
2. **Spot test** each marked place at mounting height. Try two or three orientations (upright, flat, turned 90 degrees). Orientation changes the result more than expected.
3. **Speed test** the best three spots.
4. **Repeat the speed tests at peak time** (around 8 to 10 pm). Tower congestion changes the winner.
5. **Soak** the winner for 24 hours with logging, in the enclosure and on the real cable, before mounting permanently.

### 7.5 Placement tips

- Height usually helps most.
- Prefer a clear view in the direction of the tower.
- Keep away from metal sheets, water tanks and reinforced walls.
- Prefer a spot that stays on one cell. A spot that keeps switching between two cells gives unstable speed even when the bars look good. The Finder flags this.

---

## 8. Server

### 8.1 Responsibilities

- Hold the WebSocket connection from the Agent and from each open Dashboard.
- Pass commands down and results up.
- Store telemetry, events and the command audit.
- Detect that the phone is offline (the phone cannot report its own death).
- Send alerts.
- Handle login, roles and device pairing.

### 8.2 Messages

Telemetry (Agent to Server):

```json
{
  "t": "telemetry",
  "ts": "2026-11-19T10:15:00Z",
  "tier": "fast",
  "radio": { "type": "LTE", "band": 40, "pci": 211, "rsrp": -94, "rsrq": -11, "sinr": 12.5, "score": 61 },
  "net":   { "online": true, "rx_bps": 5400000, "tx_bps": 820000, "ping_ms": 48 }
}
```

Command (Server to Agent) and result (Agent to Server):

```json
{ "t": "cmd", "id": "c_8f2a", "name": "airplane_cycle", "args": { "off_seconds": 10 },
  "sent_at": "2026-11-19T10:16:00Z", "revert_after_s": 120 }

{ "t": "cmd_result", "id": "c_8f2a", "ok": true, "output": "", "duration_ms": 14200 }
```

Sending tiers:

| Tier | Content | Interval |
|------|---------|----------|
| Heartbeat | "I am alive" | 30 s |
| Fast | Signal, speed, ping | 5 s while a Dashboard is open (1 s in Finder mirror), otherwise 60 s |
| Slow | Battery, heat, system, tethering | 60 s |
| Static | Device info, capability map | On connect and on change |
| Event | Anything from the Events row in 6.2 | Immediately |

Background data use is about 1 MB per day.

### 8.3 API

| Endpoint | Purpose |
|----------|---------|
| `WS /ws/agent` | Agent connection (device token) |
| `WS /ws/dashboard` | Live updates to the Dashboard (user session) |
| `POST /auth/login`, `POST /auth/totp` | Login and second factor |
| `POST /pair` | Pair a new phone with a one-time code |
| `GET /device` | Current state snapshot |
| `GET /telemetry?metric=&from=&to=&res=` | History for charts |
| `POST /commands`, `GET /commands` | Send a command, read the audit log |
| `GET /events` | Event timeline |
| `GET /settings`, `PUT /settings` | Thresholds, watchdog rules, alert rules |
| `GET /finder/spots` | Survey results and baseline |

### 8.4 Data stored

Tables: `users`, `devices`, `telemetry_raw`, `telemetry_1m`, `events`, `commands`, `alert_rules`, `alerts_sent`, `finder_spots`, `sms`.

Retention: raw readings for 7 days, 1-minute averages for 1 year, events and commands for 1 year. SQLite is enough for one phone. The schema keeps a `device_id` column everywhere so more phones can be added later.

### 8.5 Alerts

| Alert | Default trigger |
|-------|-----------------|
| Phone offline | No heartbeat for 3 minutes (detected by the Server) |
| Back online | First heartbeat after an offline alert, with the reason if known |
| Weak power | Plugged in but battery falling for 30 minutes |
| Low battery | 20% or less |
| Hot | 40 °C warning, 45 °C critical |
| Signal drop | Score below 60% of the installed baseline for 15 minutes |
| Cell changed | Serving cell differs from the usual one for 30 minutes |
| Tethering down | USB tethering off for 2 minutes while USB is connected |
| USB power lost | Charger disconnected |
| Data quota | 80%, 90% and 100% of the plan |
| Phone moved | Tilt or location changed |
| Watchdog acted | Any recovery step, with the outcome |
| Carrier SMS | New SMS received (plan expiry and quota warnings arrive this way) |

---

## 9. Dashboard (web app and monitoring-phone app)

| Screen | Content |
|--------|---------|
| Overview | Online or offline, signal score, speed now, battery, temperature, data used, uptime, latest events |
| Signal | Live RSRP, SINR, RSRQ, band, cell; history charts; comparison with the baseline; cell-change timeline |
| Network | USB tethering state, hotspot state and clients, speed chart, ping chart, IP addresses, usage against the plan |
| Power and heat | Battery percent, charge current, voltage and temperature charts; charge-limit setting |
| Controls | All switches and actions from 6.3, each showing its last result; confirmations and auto-revert countdown |
| System | Processes, apps, storage, logs, root shell (admin) |
| Finder | Live mirror, saved spots, ranking |
| Messages | SMS inbox, send SMS, run USSD code |
| Alerts and settings | Alert history, thresholds, watchdog rules, users and roles, pairing |

Controls the phone does not support (per the capability map) are hidden. Viewer accounts see everything except Controls, root shell and Messages.

---

## 10. Security

This system gives root control of a device over the internet, so the control channel must be closed tightly.

- TLS on every connection. No plain HTTP anywhere.
- The phone pairs once with a one-time code and receives a long random device token, kept in Android Keystore-backed storage.
- Dashboard login: strong password plus TOTP second factor. Login rate limiting and lockout.
- Roles: admin and viewer. Root shell and destructive actions ask for the password again.
- The Agent accepts commands only from its paired Server, and only if they are fresh (6.4, rule 6).
- Full audit log of commands, visible in the Dashboard.
- ADB over network is reachable only through the VPN interface.
- SMS commands: whitelisted numbers only, PIN required, small fixed command set.
- The LAN API (Phase 6) requires the same token as the cloud path.
- The hotspot phone holds no personal accounts or data.

---

## 11. Tech stack and repository layout

| Part | Stack |
|------|-------|
| Agent | Kotlin, Jetpack Compose, foreground service, libsu (root shell), OkHttp WebSocket, kotlinx.serialization, Room |
| Server | Node.js, TypeScript, Fastify, ws, SQLite; Caddy for HTTPS; Docker Compose; a small VPS and a domain name |
| Dashboard | React, TypeScript, Vite with the PWA plugin, a chart library (uPlot or Recharts), Tailwind CSS |
| Alerts | Telegram Bot API, Web Push |
| Shared | Message types defined once and used by both Server and Dashboard |

```
hotspot-monitor/
├── agent/                 Android app
│   └── app/src/main/java/.../
│       ├── service/       foreground service, boot receiver, keep-alive
│       ├── root/          root shell wrapper, capability map
│       ├── telemetry/     radio, network, tether, power, thermal, system collectors
│       ├── control/       command executor, auto-revert
│       ├── watchdog/      recovery rules
│       ├── finder/        score, audio, spot tests, speed test, ranking
│       ├── sync/          WebSocket client, offline buffer
│       └── ui/            status, finder, pairing, settings
├── server/                API, WebSocket hub, storage, alerts
├── web/                   Dashboard PWA
├── shared/                message and API types
├── deploy/                docker-compose.yml, Caddyfile
└── docs/                  this plan, Phase 0 results, command profile for the phone
```

---

## 12. Build phases

Build and test one phase at a time. Durations are rough, for one developer working full time.

### Phase 0: Feasibility on the real phone (2 to 3 days, no app code)

Use `adb shell` and `su` only. Record every result in `docs/`.

| Test | Pass condition | Must-have? |
|------|----------------|------------|
| Bootloader | The unlock option exists and the bootloader unlocks | Yes |
| Root | Works and survives a reboot | Yes |
| Automatic power-on | Phone boots by itself from fully off when power is connected | Yes |
| Tethering after reboot | USB tethering comes up with no touch | Yes |
| Power budget | 24 hours of tethering under load on the real port and cable; battery does not trend down | Yes |
| Charge control | Charging can be stopped and started from the root shell | Yes |
| Control commands | Each command in 6.3 tried; working ones noted | Most |
| Signal readings | RSRP, RSRQ, SINR, band and PCI readable about once per second | Yes |
| Heat | Phone in the enclosure on a hot afternoon stays below 45 °C | Yes |

A quick first survey with an existing cell-info app also shows whether any spot around the building is good enough to justify the project.

**Exit:** all must-have rows pass, or a different phone is chosen.

### Phase 1: Agent core and Signal Finder (about 1 week)

- Foreground service, start at boot, keep-alive script, root shell wrapper.
- Radio, power and heat collectors.
- Finder: walk mode with sound, spot test, speed test, ranking, CSV export.

**Done when:** the Finder works fully offline, the service survives a reboot and a 24-hour run, and a full survey produces a ranked list of spots.

**Then do the survey (7.4) and choose the mounting spot.**

### Phase 2: Server and read-only Dashboard (about 1 week)

- Pairing, WebSocket link, reconnect, offline buffer and back-fill.
- Remaining collectors, sending tiers, storage and downsampling.
- Login with roles; Overview, Signal, Network, Power screens; Finder mirror; PWA install.

**Done when:** live values reach the Dashboard within 5 seconds, a one-hour internet cut is back-filled afterwards, and the Dashboard is installed on the monitoring phone.

### Phase 3: Remote control (about 1 week)

- Command pipeline with acknowledgement, expiry and audit.
- Capability map; all controls from 6.3; root shell.
- Auto-revert, confirmations, reboot limit.

**Done when:** every supported control works from a different network, "mobile data off" restores itself after the timer, and the audit log shows every action.

### Phase 4: Watchdog and alerts (about 1 week)

- All rules in 6.5, editable from the Dashboard.
- Server-side offline detection; Telegram and push alerts; alert rules screen.

**Done when:** a simulated internet loss walks through the full recovery ladder, lowered test thresholds trigger the heat and power rules, and an offline alert arrives within 4 minutes of powering the phone off.

### Phase 5: Hardening and installation (about 1 week, including the soak)

- 7-day bench soak inside the enclosure on the real cable and port.
- Power-cut tests: 10 seconds, 1 hour, and until the battery is completely empty. Each time the phone must return to "online and tethering" with no touch.
- Mount at the chosen spot. Record the baseline. Check again after 24 hours.

**Done when:** seven days pass with no manual touch and all three power-cut tests pass.

### Phase 6: Extras (optional, any order)

- LAN direct mode.
- SMS command fallback.
- SMS inbox, USSD and plan-expiry reminders.
- Dual-SIM failover.
- Scheduled speed tests with history.
- Support for more than one hotspot phone.

---

## 13. Risks and limits

| Risk | Effect | Mitigation |
|------|--------|------------|
| Phone cannot be unlocked or rooted | Most control features are lost | Buying checklist (4.6); Samsung rules in Appendix A; reduced fallback in A.3 |
| USB port too weak | Phone drains and dies | Phase 0 power test; net-current display and alert; stronger port or powered hub |
| Battery heat and swelling | Fire hazard, hardware failure | Charge limit, temperature cut-off, shade, regular inspection |
| Phone does not boot after a long power cut | System stays down until someone visits | Automatic power-on is a Phase 0 must-have |
| Commands differ per phone and Android version | Some controls fail | Phase 0 command list, capability map, per-device command profile |
| Android itself freezes | No app can recover it; cutting USB power does not restart a phone with a battery | Minimal software on the phone, optional weekly reboot, offline alert |
| A system update removes root | Agent loses control | Automatic updates off; update by hand when on site |
| A remote command cuts its own link | Phone unreachable | Auto-revert (6.4) |
| Server down | No remote view or alerts; internet keeps working, the watchdog keeps working | Simple single-service server, uptime check on the Server itself |
| Carrier congestion or outage | Slow or no internet regardless of placement | Peak-hour testing during the survey; dual-SIM failover in Phase 6 |
| SIM plan expires | Internet stops | Carrier SMS alerts and data-quota tracking |
| Theft or tampering | Device lost | Motion and location alert; no personal data on the phone |

---

## 14. Information needed to finalize

1. The exact phone model and software version, once bought. This fills in the device profile: root method, automatic power-on method and charge-control path.
2. What the indoor end of the USB cable plugs into (router model or PC), and whether the port is USB 2.0 or 3.0.
3. Cable distance from the outdoor spot to that device.
4. Carrier, 4G or 5G, one SIM or two.
5. Where the Server will run (a rented VPS, a free cloud tier, or the Lite variant with no server).

---

## Appendix A: Samsung Galaxy S series notes

A Galaxy S can do this job, but the model and its software version matter more than on most brands.

### A.1 What to buy

| Check | Why |
|-------|-----|
| Model number does not end in U, U1 or W | United States and Canada models cannot be bootloader-unlocked. |
| Software is One UI 7 (Android 15) or older | One UI 8 (Android 16) removed bootloader unlocking on Galaxy phones worldwide. A phone already on One UI 8 cannot be rooted. |
| Safest families: Galaxy S10, S20, S21 (not the S21 FE) | Their updates ended at One UI 7 or earlier, so they can never be on One UI 8. |
| Risky: S21 FE, S22 and newer | These received One UI 8. One is usable only if it was never updated, which is hard to confirm on a phone with a broken screen. |
| "OEM unlocking" switch is visible in Developer options | Direct proof that this phone can be unlocked. Check before paying if the screen works. |
| No finance lock (Knox Guard) | A financed phone can be locked remotely by the lender. |

Ask the seller for the full model number (for example SM-G991B) and the One UI version before buying.

### A.2 What is different on Samsung

- **No fastboot.** Unlocking and flashing use Download mode and the Odin tool on a PC. Root is a Magisk-patched firmware flashed with Odin.
- **Unlocking wipes the phone and permanently trips Knox.** Samsung Pay and Secure Folder stop working. That does not matter here.
- **Automatic power-on is the biggest Samsung risk.** The fastboot command in 4.3 does not exist. Use a root method that makes the charging-only mode reboot into Android (a boot-image patch such as the magisk-autoboot module, or on older models replacing the charging-animation program). Samsung's own "power on when plugged in" is an enterprise Knox policy, not a user setting. Make this the first Phase 0 test.
- **Charge limit is built in.** "Protect battery" caps at 85% on older One UI; One UI 7 lets you pick 80 to 95%. Use it as the base layer. With root, go lower (50 to 60%) through Samsung's battery control files, found in Phase 0.
- **Dead screen workaround.** S-series phones output video over USB-C, so a hub with HDMI and a mouse can stand in for the screen once Android has started. Download mode does not output video, so the unlock and flash steps would be done blind. This is why a visible display is strongly preferred (4.6).
- **Background limits.** One UI stops background apps aggressively. Set the Agent to unrestricted battery use and add it to "Never sleeping apps".
- **Updates.** Keep automatic updates off. On the risky families, an update to One UI 8 was reported to re-lock already unlocked phones.
- **Service menu.** Dialling `*#0011#` shows band and signal details, useful for cross-checking the Signal Finder.

### A.3 If the phone turns out not to be rootable

A reduced version is still possible. If the indoor device is a PC or a router that can run `adb`, it can send shell-level commands down the same USB cable: mobile data, airplane mode, tethering, hotspot and reboot work without root. What is lost: charging control below the built-in limit, automatic power-on, the root shell and the deeper system readings. Treat this as a fallback, not the target.