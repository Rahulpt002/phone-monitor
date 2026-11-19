# Phase 0 results

Fill this in while running [`phase0.md`](phase0.md). `tools/phase0/phase0.sh report` writes the
table below as `phase0-table.md` in the results folder; paste it here and add the evidence.
One copy of this file per phone tried (rename it, for example `phase0-results-sm-g973f.md`).

Do not paste the full IMEI, serial number or cell IDs into this file: it is committed.

## Phone

| Item | Value |
|------|-------|
| Date(s) tested | |
| Tested by | |
| Manufacturer / model (`ro.product.model`) | |
| Device codename (`ro.product.device`) | |
| Android version / SDK | |
| One UI version (Samsung) | |
| Build (`ro.build.display.id`) | |
| Security patch | |
| Bootloader version (`ro.bootloader`) | |
| Bootloader state before / after unlock | |
| Root manager and version | |
| Carrier, SIM (4G / 5G, one or two SIMs) | |
| IMEI (last 4 digits only) | |
| New battery fitted? (plan 4.6) | |
| Indoor end: router or PC model, USB 2.0 or 3.0 port | |
| Cable: type and length | |

## Phase 0 table (plan section 12)

| Test | Pass condition | Must-have? | Result | Evidence / notes |
|------|----------------|------------|--------|------------------|
| Bootloader | The unlock option exists and the bootloader unlocks | Yes | | `info`: |
| Root | Works and survives a reboot | Yes | | `root --reboot`: |
| Automatic power-on | Phone boots by itself from fully off when power is connected | Yes | | method: ; `autoboot --test`: ; by hand: |
| Tethering after reboot | USB tethering comes up with no touch | Yes | | `usb --reboot-test`: interface , IP |
| Power budget | 24 hours of tethering under load on the real port and cable; battery does not trend down | Yes | | hours: ; start % → end %: ; trend %/h: ; avg net mA: |
| Charge control | Charging can be stopped and started from the root shell | Yes | | path: ; off/on: ; mode (idle / input suspended): |
| Control commands | Each command in 6.3 tried; working ones noted | Most | | N of M (table below) |
| Signal readings | RSRP, RSRQ, SINR, band and PCI readable about once per second | Yes | | samples/s: ; field coverage: |
| Heat | Phone in the enclosure on a hot afternoon stays below 45 °C | Yes | | max battery °C at time: ; max skin °C: |

Quick survey with a cell-info app (plan 12, before buying): best spot , RSRP , SINR , RSRQ .

**Exit decision:** all must-have rows pass / different phone needed because:

## Control commands (plan 6.3)

From `commands.tsv` (`commands --live` for the "Live" column).

| Control | Command used | Help text | Live test | Notes |
|---------|--------------|-----------|-----------|-------|
| Mobile data | `svc data enable/disable` | | | |
| Airplane mode | `cmd connectivity airplane-mode enable/disable` | | | |
| USB tethering | `svc usb setFunctions rndis` (or `ncm`) | | | |
| Wi-Fi hotspot | `cmd wifi start-softap <ssid> wpa2 <pass>` / `stop-softap` | | | |
| Wi-Fi | `svc wifi enable/disable` | | | |
| Bluetooth | `svc bluetooth enable/disable` | | | |
| Network mode | `cmd phone set-allowed-network-types-for-users` | | | bitmask format: |
| Reboot | `svc power reboot` | | | |
| Screen sleep / wake | `input keyevent 223 / 224` | | | |
| Brightness | `settings put system screen_brightness <0-255>` | | | |
| Charging on / off | charge-control file | | | |
| Apps: force stop | `am force-stop <pkg>` | | | |
| Apps: disable / enable | `pm disable-user --user 0 <pkg>` / `pm enable` | | | |
| Process list | `top -b -n 1` | | | |
| Send SMS / USSD | Android API (Agent, Phase 3) | n/a | | |
| Root shell | `su -c` | | | |

## Device profile values found

These become `agent/app/src/main/assets/profiles/<id>.json` (see
[`device-profiles.md`](device-profiles.md); `phase0.sh profile` drafts it).

| Field | Value | How it was found |
|-------|-------|------------------|
| `id` / `name` | | |
| `match.manufacturer` / `match.model_regex` | | `info` |
| `charge_control[0]` path, on, off | | `charge-discover --test` |
| Other working charge paths | | |
| Charge mode (idle / input suspended) | | `charge-discover --test` |
| `current_now.unit` / `sign` | | `current-sign` |
| `current_now` path | | `current-sign` |
| `usb_function` (rndis / ncm), tether interface, gateway IP | | `usb --live` |
| `autoboot.method` and how it was installed | | `autoboot` |
| `thermal.cpu` | | `thermal` |
| `thermal.modem` | | `thermal` |
| `thermal.skin` | | `thermal` |
| `thermal.battery` | | `thermal` |
| `commands` overrides needed | | `commands --live` |
| `network_mode` values (if overridden) | | `commands-network-mode-help.txt` |
| Built-in charge limit used as base layer (Samsung Protect battery, Pixel 80%) | | |

## Power budget details

| Item | Value |
|------|-------|
| Port (USB 2.0 / 3.0 / charging port) and indoor device | |
| Load during the test (what traffic, how much) | |
| Screen, Wi-Fi, Bluetooth state | |
| Duration | |
| Battery start → end, min, max | |
| Trend (%/h) | |
| Average net current (mA, + = charging) | |
| Time plugged in but discharging | |
| Verdict and changes made | |

## Heat details

| Item | Value |
|------|-------|
| Date, weather, outside temperature | |
| Enclosure, shade, position | |
| Hours logged (and hours between 12:00 and 18:00) | |
| Max battery °C (time) | |
| Time ≥ 40 °C / ≥ 45 °C | |
| Max skin / cpu / modem °C | |
| Verdict and changes made | |

## Signal details

| Item | Value |
|------|-------|
| Spot and orientation | |
| Network type(s), band(s), PCI(s) | |
| Samples per second / new readings per second | |
| RSRP / RSRQ / SINR averages | |
| Score (plan 7.2) | |

## Problems and workarounds

- 
