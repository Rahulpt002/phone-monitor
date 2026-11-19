# Phase 0: feasibility on the real phone

Plan section 12, Phase 0: before any app code is trusted, prove on the actual phone, cable and port
that the hardware can do the job. It takes 2 to 3 days, most of it waiting for the 24-hour power
log and a hot afternoon. Record the outcome in [`phase0-results.md`](phase0-results.md).

**Exit:** every must-have row passes, or a different phone is chosen. Software cannot fix a failed
must-have row later (plan section 4).

Everything below uses [`tools/phase0/phase0.sh`](../tools/phase0/phase0.sh). It only reads, unless
an option says it changes something (`--live`, `--test`, `--reboot`, `--reboot-test`,
`--fastboot`, `power-log start`); those ask for confirmation first, and any value they change is
restored on exit, Ctrl-C included.

## 0. Before buying or rooting: a quick survey

Check that some spot around the building is good enough to justify the project. Install any
cell-info app on a phone with the same SIM (for example Network Cell Info Lite or LTE Discovery;
on Samsung dial `*#0011#`), walk the roof edges, walls, balcony and window ledges, and note RSRP,
SINR and RSRQ against plan 7.1:

| Metric | Good enough to continue |
|--------|-------------------------|
| RSRP | -100 dBm or better somewhere (fair), ideally -90 or better |
| SINR | 0 dB or better (fair), ideally 13 or better |
| RSRQ | -15 dB or better |

If nothing reaches "fair", an outdoor phone will not help much: talk to the carrier or consider a
different network first. The proper survey with the Agent's Signal Finder comes after Phase 1
(see [`install.md`](install.md#9-survey-and-choose-the-spot)).

## 1. Set up

1. Phone checks from plan 4.6 (and Appendix A.1 for a Samsung): working mainboard, SIM and mobile
   data; the USB port carries data; clean IMEI; no FRP, carrier or finance lock; no water damage;
   working buttons; a visible display if at all possible. Fit a new battery before deployment.
2. Install Android platform-tools on the PC (`adb`, `fastboot`) and put them on `PATH`.
3. On the phone: Settings, About phone, tap "Build number" 7 times; then Developer options,
   "USB debugging" on. Connect the cable, accept "Allow USB debugging" (tick "Always allow").
4. Check: `adb devices` lists the phone as `device`.

Run `tools/phase0/phase0.sh --help` for every option. Global options: `-s SERIAL` when several
phones are connected; `--new` to start a new results folder; `--results DIR` to pick one.

Results go to `tools/phase0/results/<timestamp>-<model>/` (git-ignored). Later runs for the same
phone append to its newest folder, so results gathered over several days are combined by
`report` and `profile`.

Quick start, after unlocking and rooting (docs/install.md section 2):

```sh
tools/phase0/phase0.sh all        # all the safe checks (about 2 minutes), then the table
```

`all` runs `info`, `root`, `commands`, `signal --seconds 30`, `charge-discover`, `thermal`, `usb`,
`autoboot --check` and `profile`. It leaves out everything that changes the phone or takes hours.

## 2. The tests

Recommended order: the cheapest ways to rule a phone out come first.

| # | Plan row | Must-have | Command | Pass condition |
|---|----------|-----------|---------|----------------|
| 1 | Bootloader | Yes | `info` | Unlock option exists and the bootloader unlocks |
| 2 | Root | Yes | `root`, then `root --reboot` | `su` works, and still works after a reboot |
| 3 | Automatic power-on | Yes | `autoboot`, then `autoboot --test` | Boots by itself from fully off when power is connected |
| 4 | Charge control | Yes | `charge-discover`, then `charge-discover --test PATH` | Charging stops and restarts from the root shell |
| 5 | Control commands | Most | `commands`, then `commands --live` | Each command in plan 6.3 tried; working ones noted |
| 6 | Signal readings | Yes | `signal` | RSRP, RSRQ, SINR, band and PCI readable about once per second |
| 7 | Tethering after reboot | Yes | `usb`, then `usb --reboot-test` | USB tethering comes up with no touch |
| 8 | Power budget | Yes | `power-log start` ... `power-log pull` | 24 h of tethering under load; battery does not trend down |
| 9 | Heat | Yes | `heat-log start` ... `heat-log pull` | In the enclosure on a hot afternoon, stays below 45 °C |

Also needed for the device profile (not rows of the plan's table): `current-sign` and `thermal`.

`report` prints the table at any time and writes `phase0-table.md` in the results folder.

### 2.1 Bootloader (`info`)

```sh
tools/phase0/phase0.sh info
```

Reads model, Android and One UI version, build, bootloader state (`ro.boot.flash.locked`,
`ro.boot.verifiedbootstate`, `ro.boot.vbmeta.device_state`), the OEM-unlocking switch
(`sys.oem_unlock_allowed`), the Samsung Knox warranty bit, carrier and the last 4 digits of the
IMEI (never the full number).

- **PASS** when the bootloader is unlocked. **PARTIAL** when OEM unlocking is on but not done yet:
  unlock it (docs/install.md section 2), then run `info` again.
- **FAIL** for a Samsung model ending in U, U1 or W (USA/Canada: cannot be unlocked) or on One UI 8
  or later (unlocking removed). **MANUAL** when the OEM-unlocking switch is off or hidden.

If it fails: on Samsung, connect to the internet and wait (the switch can take up to 7 days to
appear after a reset); a missing switch on any brand usually means a carrier-locked model. A
phone that cannot be unlocked cannot be rooted: choose a different phone. (Plan A.3 describes a
reduced no-root fallback; it is not the target.)

### 2.2 Root (`root`)

```sh
tools/phase0/phase0.sh root
tools/phase0/phase0.sh root --reboot     # reboots once to prove root survives
```

Checks `su -c id` returns uid 0 and names the root manager (Magisk / KernelSU / APatch) and
whether the `hsm-keepalive` module is installed. The first time, Magisk asks whether "Shell" may
have root: tap Grant (Forever). Root that worked in two different boots gives **PASS**.

If it fails: re-patch the exact firmware's boot image (`init_boot` on phones launched with
Android 13+), check the root manager app, and grant Shell in its superuser list.

Samsung phones launched with Android 9 (the Galaxy S10 family) have no ramdisk in the boot image:
Magisk is installed into the recovery partition and root is active only when the phone boots
through recovery (key combination, or `adb reboot recovery`). A plain reboot, and a cold power-on
after a power cut, start **without root**, so the Agent would lose charge control and recovery
commands exactly when it matters. `root` warns about such phones and `root --reboot` (a normal
reboot) shows the effect. Unless an S10 keeps root through a normal reboot and through the
automatic power-on, prefer an S20 or S21 (launched with Android 10 / 11: Magisk patches the boot
image).

### 2.3 Automatic power-on (`autoboot`) - Samsung: do this first

```sh
tools/phase0/phase0.sh autoboot              # explains the method for this phone and checks it
tools/phase0/phase0.sh autoboot --fastboot   # non-Samsung: runs fastboot oem off-mode-charge 0
tools/phase0/phase0.sh autoboot --test       # powers off (cable stays in) and waits for a boot
tools/phase0/phase0.sh autoboot --confirm pass|fail   # record a test you did by hand
```

After a long power cut the battery is empty; when power returns most phones show a charging
animation and stay off. The phone must boot by itself (plan 4.3):

- **Phones with fastboot** (Pixel, Motorola, many others): `adb reboot bootloader`,
  `fastboot oem off-mode-charge 0`, `fastboot reboot`. If fastboot rejects the command, this
  bootloader lacks it: use a root method instead.
- **Samsung (no fastboot)**: a root change that makes the charging-only mode (LPM) reboot into
  Android, such as an auto-boot Magisk module or boot-image patch, or on older models replacing
  the charging-animation program. Support varies by model, which is why this is the first Samsung
  test (Appendix A.2). Samsung's "power on when plugged in" is a Knox enterprise policy, not a
  user setting.

`--test` shuts the phone down while the cable stays connected, which puts it into charging-only
mode (the state after power returns), and waits up to 4 minutes for Android to come back. A new
boot gives **PASS**. Then do the real thing by hand once: unplug, power off, wait 30 s, plug into
the real port, and record it with `--confirm`. Phase 5 repeats it with a completely empty battery.

The `hsm-keepalive` Magisk module does **not** do this; it only keeps the Agent running.

If it fails: try another auto-boot method for this model. If none works, choose a different
phone: this is a must-have.

### 2.4 Charge control (`charge-discover`)

```sh
tools/phase0/phase0.sh charge-discover                       # list candidates
tools/phase0/phase0.sh charge-discover --test /sys/class/power_supply/battery/batt_slate_mode
tools/phase0/phase0.sh charge-discover --test PATH --on 1 --off 0 --seconds 30   # unknown file
```

Lists every `/sys/class/power_supply/*` (type, online, status, capacity, current, voltage) and
every known charge-control file from the ACC project and common kernels (`batt_slate_mode`,
`input_suspend`, `charging_enabled`, `battery_charging_enabled`, `store_mode`,
`batt_full_capacity`, `siop_level`, `charge_control_limit`, `charge_stop_level`,
`op_disable_charge`, `mmi_charging_enable`, `bypass_charger`, MediaTek `current_cmd`, ...), with
its value and whether it is writable.

`--test` needs the phone plugged in and charging (below 100%). It writes the "off" value, watches
status and `current_now` for 20 s, writes the original value back and watches 15 s more. It reports:

- **stopped** and **restored**: both yes gives **PASS**;
- the **mode**: *idle* (status "not charging": the battery rests and USB powers the phone; best)
  or *input suspended* (status "discharging": the phone runs from the battery until charging
  resumes; fine, the Agent's limit has 10% hysteresis).

Files marked `limit` only act above a level (`store_mode` around 70%) or take a percentage
(`batt_full_capacity`, `charge_stop_level`: writing a value below the current charge stops it).
`info` files are not switches. A reboot also restores any sysfs value.

If nothing works: try every `rw` file of the `battery`, `charger` and `usb` supplies in
`charge-discover.txt`, look up the model in the ACC project's documentation, and as a minimum use
the built-in limit (Samsung "Protect battery" 85%, Pixel 80%). No working stop at all is a
must-have failure: the battery-safety rules (plan 4.2, 45 °C stop) depend on it.

### 2.5 current_now unit and sign (`current-sign`)

```sh
tools/phase0/phase0.sh current-sign
```

The phone logs `current_now` once per second for 75 s on its own while you keep the cable in,
unplug it and plug it back in when told. The script then decides the unit (µA or mA) and the sign
that makes charging positive. Both go into the device profile's `current_now`, which the
Dashboard's net-current display and the "plugged in but discharging" alert depend on.

### 2.6 Control commands (`commands`)

```sh
tools/phase0/phase0.sh commands          # help text only: does each command exist here?
tools/phase0/phase0.sh commands --live   # really toggles each one, then restores it
```

Covers plan 6.3: mobile data (`svc data`), airplane mode (`cmd connectivity airplane-mode`, or a
root `settings` + broadcast fallback), USB tethering (`svc usb setFunctions`), hotspot
(`cmd wifi start-softap`), Wi-Fi, Bluetooth, network mode
(`cmd phone set-allowed-network-types-for-users`, its help text saved), reboot (`svc power`),
screen (`input keyevent 223/224`), brightness, app force-stop / disable, process list, root shell.
SMS and USSD use Android APIs and are tested from the Dashboard in Phase 3.

`--live` toggles each control and restores it: data off and on (waits for data to come back),
airplane on and off, Wi-Fi and Bluetooth toggled and restored, a hotspot started and stopped, the
current network types written back unchanged, screen slept and woken, brightness set and
restored, a calculator app force-stopped and disabled then re-enabled, USB tethering switched on,
and finally a reboot. It asks before anything that drops the adb link (USB re-enumeration,
reboot; over network adb also data, airplane and Wi-Fi).

**PASS** when mobile data, airplane mode, USB tethering and reboot work and at least 70% of all
controls do. Unsupported ones are listed in `commands.tsv` and in the draft profile's notes: the
Agent hides them via its capability map, or a device profile can supply a working command
(`commands` in [`device-profiles.md`](device-profiles.md)).

### 2.7 Signal readings (`signal`)

```sh
tools/phase0/phase0.sh signal                  # 60 s
tools/phase0/phase0.sh signal --seconds 120 --slot 1   # second SIM slot
```

Samples `dumpsys telephony.registry` on the phone about once per second, parses RSRP, RSRQ, SINR
(RSSNR), RSSI, band (reported, or derived from the EARFCN), PCI, cell ID, TAC, timing advance and
the 5G NR leg, computes the plan 7.2 score, and writes `signal-<time>.csv`. Location must be on
(Android hides cell details otherwise); keep the screen on for the most frequent updates.

**PASS** when each of RSRP, RSRQ, SINR, band and PCI is present in at least 80% of samples at
0.8 samples/s or more. "New readings per second" shows how often the values actually changed:
`dumpsys` reads what Android has cached, while the Agent asks the modem for fresh cell info, so
the Finder updates at least this often.

If a field is missing: check location is on, a SIM is registered, and try `--slot 1`. A phone that
never reports SINR or RSRQ makes the score and the Finder much weaker: prefer another phone.

### 2.8 Tethering after reboot (`usb`)

```sh
tools/phase0/phase0.sh usb                 # USB functions, tether interface and IP
tools/phase0/phase0.sh usb --live          # switch USB tethering on (rndis), check the IP
tools/phase0/phase0.sh usb --live --function ncm
tools/phase0/phase0.sh usb --reboot-test   # reboot and wait for tethering with no touch
```

First set Developer options, "Default USB configuration" = "USB tethering" (and no screen lock:
the default applies once the phone is unlocked). `--reboot-test` reboots and waits up to 2 minutes
for a `rndis0` / `ncm0` / `usb0` interface with an IPv4 address: **PASS**. The interface name
tells you the profile's `usb_function`. The indoor router or PC must also get an address and
internet through it: check that on the real device.

If it fails: re-check the developer option; once the Agent is installed its tether fix
(plan 6.5) also turns tethering back on, so repeat the test with the Agent running. If the
router does not support RNDIS, try `ncm` (or a PC in between).

### 2.9 Power budget (`power-log`)

```sh
tools/phase0/phase0.sh power-log start           # every 60 s until stopped
tools/phase0/phase0.sh power-log status
tools/phase0/phase0.sh power-log pull            # any time; verdict needs 24 h
tools/phase0/phase0.sh power-log stop
```

Starts a logger on the phone (as root, `nohup`, survives adb disconnects) that appends battery
%, status, plug type, raw `current_now`, voltage and temperature to
`/data/local/tmp/hsm-phase0/power-log.csv`. Run the real setup for at least 24 hours: the real
cable and indoor port, USB tethering on, real traffic through it (streaming or a download loop),
screen off. Then `pull` copies the CSV and prints duration, start/end/min/max %, the trend in %
per hour, average net current (in mA once `current-sign` has run), how often it was plugged in but
discharging, and power cuts.

- **PASS**: 24 h or more and the battery does not trend down (trend no worse than -0.2 %/h, end
  no more than 5% below the start).
- **FAIL**: it trends down. **PARTIAL**: not 24 h yet.

The logger stops if the phone reboots: run `power-log start` again (it appends).
If it fails (plan 4.1): a USB 3.0 or charging-capable port, screen off, Wi-Fi and Bluetooth off,
a shorter or thicker cable, or a powered USB hub that passes data, then repeat.

### 2.10 Heat (`heat-log`, `thermal`)

```sh
tools/phase0/phase0.sh thermal                    # zones now, profile keyword suggestions
tools/phase0/phase0.sh heat-log start --interval 60
tools/phase0/phase0.sh heat-log pull
```

`thermal` lists every `/sys/class/thermal/thermal_zone*` type and temperature, sorts them into
cpu / modem / skin / battery, and suggests the profile's `thermal` keywords. Qualcomm
battery-current-limit zones (`soc`, `vbat`, `ibat`, `*-lvl*`) report charge level, voltage or
current, not heat; they are marked `bcl` and never suggested.

`heat-log` logs the battery temperature and every zone. Put the phone in the closed enclosure at
the real spot, tethering under load, through a hot afternoon. `pull` reports the maximum battery
temperature and when it happened, time above 40 °C and 45 °C, and the hottest skin / cpu / modem
zone.

- **PASS**: battery stays below 45 °C, with at least 2 hours logged between 12:00 and 18:00.
- **FAIL**: 45 °C or more. **PARTIAL**: no full afternoon yet.

If it fails (plan 4.2, 4.5): more shade, a light-coloured box, a small roof, vents with a drip
edge, screen off, a lower charge limit, then repeat on another hot day.

## 3. Recording the results

```sh
tools/phase0/phase0.sh report    # table; writes results/<...>/phase0-table.md
tools/phase0/phase0.sh profile   # draft device profile: results/<...>/profile-<id>.json
```

1. Copy `phase0-table.md` and the values into [`phase0-results.md`](phase0-results.md).
2. Review the draft profile ([`device-profiles.md`](device-profiles.md)): keep the verified
   charge-control path first, delete untested candidates you do not trust, check `current_now`,
   `usb_function`, `autoboot` and `thermal`. Then add it as
   `agent/app/src/main/assets/profiles/<id>.json` (or send it from the Dashboard as
   `settings.profile_override`).

## 4. Exit decision

All must-have rows PASS: Phase 0 is done; go on to Phase 1 and install the Agent
([`install.md`](install.md)).

Otherwise decide per row:

| Failed row | Fixable on this phone? |
|------------|------------------------|
| Bootloader, Root | No: choose a different phone |
| Automatic power-on | Only if another auto-boot method works for this model; otherwise a different phone |
| Charge control | Only if another file works; the built-in limit alone is not enough for months outdoors |
| Power budget | Usually: better port, cable or powered hub (then repeat) |
| Heat | Usually: shade and enclosure changes (then repeat) |
| Signal readings | Rarely: if SINR/RSRQ never appear, prefer a different phone |
| Tethering after reboot | Usually: developer option, NCM, or the Agent's tether fix |
| Control commands ("most") | Yes: profile command overrides; unsupported controls are hidden |

## 5. Troubleshooting

| Symptom | Fix |
|---------|-----|
| `No Android phone found` | Cable carries data? USB debugging on? `adb kill-server`, re-plug |
| `unauthorized` | Unlock the phone and accept the debugging prompt (Always allow) |
| `su did not return uid=0` | Grant "Shell" root in Magisk / KernelSU, then re-run |
| Logger "not running" after a while | The phone rebooted: `power-log start` again (appends) |
| Network adb (`-s 100.x.y.z:5555`) | Works for everything; commands that cut the network ask first |
| Several phones | `-s SERIAL` (see `adb devices`) |
| Output looks wrong | Each run is logged in `results/<...>/log-<command>.txt`; raw data sits next to it |
