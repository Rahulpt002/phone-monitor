# Installing the hotspot phone

From a phone that passed Phase 0 ([`phase0.md`](phase0.md)) to a phone mounted outdoors, online,
tethering and reporting to the Dashboard. Server setup is in [`deploy.md`](deploy.md); the Agent's
build is in [`agent/README.md`](../agent/README.md).

You need: a PC with Android platform-tools (`adb`, `fastboot`) and this repository, the Agent APK,
the running Server and an admin login for the Dashboard, and ideally a visible display on the phone
for the unlock steps (plan 4.6). All `adb` commands run on the PC with the phone connected by USB,
"USB debugging" on and the debugging prompt accepted.

Sections 2.1 to 2.12 follow the checklist of plan section 5, in order.

## 1. Quick reference

```sh
adb install -r -g agent/app/build/outputs/apk/debug/app-debug.apk     # Agent (section 3)
tools/phase0/build-magisk-module.sh                                   # keep-alive module (section 4)
adb push tools/phase0/dist/hsm-keepalive.zip /sdcard/Download/
adb shell su -c 'magisk --install-module /sdcard/Download/hsm-keepalive.zip' && adb reboot
adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d '<deep link>' --ez auto true"   # pairing (dead screen)
tools/phase0/phase0.sh all                                            # re-check everything
```

## 2. Prepare the phone (plan section 5)

### 2.1 Unlock the bootloader and root

A clean ROM such as LineageOS is preferred where available (fewer background apps, less heat):
install it following the LineageOS wiki page for the exact model, then root it as below.

**Phones with fastboot (Pixel, Motorola, OnePlus, Xiaomi and others).** Enable Developer options
(Settings, About phone, tap "Build number" 7 times), switch on "OEM unlocking" and
"USB debugging", then:

```sh
adb reboot bootloader
fastboot flashing unlock        # older bootloaders: fastboot oem unlock (Xiaomi uses its own unlock tool)
# confirm on the phone with the volume keys and power. This wipes the phone.
fastboot reboot
```

Root with Magisk: download the factory image of exactly the installed build, take `boot.img`
(`init_boot.img` on phones launched with Android 13 or later), then:

```sh
adb install Magisk-v<version>.apk
adb push boot.img /sdcard/Download/
# Magisk app: Install, "Select and Patch a File", choose boot.img
adb pull /sdcard/Download/magisk_patched-<id>.img
adb reboot bootloader
fastboot flash boot magisk_patched-<id>.img      # or: fastboot flash init_boot magisk_patched-<id>.img
fastboot reboot
```

Open the Magisk app once to finish its setup. KernelSU is an alternative on GKI kernels (see its
documentation).

**Samsung (no fastboot: Download mode and Odin, plan Appendix A.2).** Check first: the model does
not end in U, U1 or W, and the software is One UI 7 or older (`phase0.sh info`).

1. Developer options: "OEM unlocking" on (the phone needs internet; the switch can take days to
   appear on a freshly reset phone).
2. Power off. Galaxy S20 / S21: hold Volume Up + Volume Down and plug in the USB cable.
   Galaxy S10: hold Volume Down + Bixby and plug in. On the warning screen, long-press Volume Up
   to unlock the bootloader and confirm. The phone wipes itself and trips Knox permanently.
3. Set the phone up again (skip accounts), connect to the internet, enable Developer options and
   check that "OEM unlocking" is still shown.
4. Download the firmware for exactly this model, region (CSC) and build, and unzip the AP, BL, CP
   and CSC files. Copy the AP file to the phone and patch it in the Magisk app (Install,
   "Select and Patch a File"). Pull `magisk_patched-<id>.tar` back to the PC.
5. Download mode again. In Odin: BL = BL file, AP = the patched tar, CP = CP file,
   CSC = CSC file (not HOME_CSC: a wipe is needed on first root). Start.
6. After the flash, follow the Magisk documentation for Samsung (factory reset from stock
   recovery on first root, then boot and open the Magisk app).
7. Open the Magisk app. If it says "Requires additional setup", tap OK: the phone reboots once.

**Done on a Galaxy M52 5G (SM-M526B, One UI 5.1) from macOS, without Odin:** only `boot` and
`vbmeta` need flashing. Extract `boot.img.lz4` and `vbmeta.img.lz4` from the AP file, put just
those two in a tar and patch that tar in Magisk. On macOS build it with
`tar --no-mac-metadata --no-xattrs --format ustar -cf AP_boot_vbmeta.tar boot.img.lz4 vbmeta.img.lz4`:
macOS `._` metadata files in the tar make Magisk fail with "Process error". Unpack the patched tar,
then flash in Download mode with Heimdall 2.1 (grimler's fork):
`heimdall flash --BOOT boot.img --VBMETA vbmeta.img` (`lz4 -d` the files first). Keep the stock
`boot.img` and `vbmeta.img` as a way back. The first boot lands in recovery: factory reset there.

**Warning, Galaxy S10 family (launched with Android 9):** there is no ramdisk in the boot image,
so Magisk is installed into the recovery partition and root is only active when the phone boots
through recovery. A cold power-on after a power cut then starts **without root** (no charge
control, no recovery actions). The root-after-cold-boot test below is required for every phone
and decides it for an S10; prefer a Galaxy S20 or S21 (docs/phase0.md section 2.2).

**Verify:**

```sh
adb shell su -c id                       # uid=0(root); see "Granting root" below if it is refused
tools/phase0/phase0.sh root --reboot     # root must survive a reboot
tools/phase0/phase0.sh root --cold-boot  # and a start from fully off (you press power)
```

**Granting root (Magisk 29 and newer, tested with 31.1):** Magisk no longer shows a pop-up when
an app or the shell asks for root, so `su` is simply refused at first. Grant it in the Magisk app:
Superuser tab, **+**, tick the app (and "Shell" for adb). On a phone with a dead screen, do it
through scrcpy, or write the policy directly (as root; `2` = allow, `0` = forever):

```sh
UID_=$(adb shell pm list packages -U com.hotspotmonitor.agent | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
printf 'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(%s,2,0,1,1)"\n' "$UID_" | adb shell su
```

(Feeding the command to `su` on stdin avoids the nested quoting that `adb shell su -c "..."` gets
wrong.) Shell itself is uid 2000. Magisk 31 also dropped app hiding (repackaging).

### 2.2 Remove the screen lock and the SIM PIN

The phone must start fully after an unattended reboot.

```sh
adb shell locksettings clear --old <current PIN or password>   # only if a lock is set
adb shell locksettings set-disabled true                        # no lock screen at all
```

SIM PIN: Settings, Security (Samsung: Security and privacy, Other security settings), SIM card
lock, off. There is no adb command for it.

### 2.3 No personal accounts or data

Remove every Google / Samsung account (Settings, Accounts) and personal data. The phone sits
unlocked outdoors (plan 10). The unlock in 2.1 already wiped it.

### 2.4 Automatic system updates off

An update can remove root, re-lock a Samsung (One UI 8) or wait for a tap.

```sh
adb shell settings put global ota_disable_automatic_update 1     # Developer options, Automatic system updates
# Samsung: also Settings, Software update, Auto download over Wi-Fi off, and:
adb shell pm disable-user --user 0 com.wssyncmldm
adb shell pm disable-user --user 0 com.sec.android.soagent
```

LineageOS: Settings, System, Updater, menu, Preferences, auto update check "Never".
Update by hand only, on site, then re-run `phase0.sh all`.

### 2.5 Default USB configuration = USB tethering

Developer options, "Default USB configuration", "USB tethering". There is no shell setting for it;
with a dead screen use scrcpy (section 7). It applies once the phone is unlocked, which with no
lock screen (2.2) is right after boot. Prove it: `tools/phase0/phase0.sh usb --reboot-test`.
The Agent also turns tethering back on when USB is connected but tethering is off (plan 6.5).

### 2.6 Location on permanently

Android only reports cell details to apps while location is on.

```sh
adb shell cmd location set-location-enabled true      # Android 11+
adb shell settings put secure location_mode 3         # older Android
adb shell settings get secure location_mode           # expect 3
```

### 2.7 Agent permissions

Root granted permanently, battery optimisation off, location "Allow all the time", phone,
notifications, usage access, SMS (optional): sections 3 and 5. The `hsm-keepalive` module
re-applies them at every boot.

### 2.8 Automatic power-on

Set up and proved in Phase 0 ([`phase0.md` 2.3](phase0.md#23-automatic-power-on-autoboot---samsung-do-this-first)):

```sh
tools/phase0/phase0.sh autoboot              # method for this phone
tools/autoboot/install-samsung.sh            # Samsung: auto-boot rule in /system/etc/init
tools/phase0/phase0.sh autoboot --fastboot   # phones with fastboot: off-mode-charge 0
tools/phase0/phase0.sh autoboot --test       # must PASS
```

The keep-alive module does not do this, and no Magisk module can on Samsung: Magisk does not run
in charging-only mode. Re-run `install-samsung.sh` after any firmware flash.

### 2.9 Charge limit

1. Base layer: the built-in limit (Samsung: Settings, Battery, "Protect battery", 85% or 80 to
   95% on One UI 7; Pixel: "Charging optimization" 80% where offered).
2. The Agent enforces the real limit through the profile's charge-control file (proved with
   `phase0.sh charge-discover --test`): Dashboard settings, watchdog, `charge_limit_pct`
   (default 60, resume 10% lower), with the 45 °C charging stop (plan 4.2, 6.5).
3. After installing the Agent, check the Dashboard's Power screen shows the charge-control path
   and that charging stops at the limit.
4. A manual **Charging off** from the Dashboard never holds the phone at or below
   max(`alerts.low_battery_pct`, 20) %: it is refused there, and lifted (with a `charging_resumed`
   event) when the battery falls to that floor, so a forgotten "off" cannot drain an unattended
   phone. Only the heat stop can keep charging off at a low battery. Heat stop, limit band and the
   manual override all survive an Agent restart.

### 2.10 Screen, brightness, radios

```sh
adb shell settings put system screen_off_timeout 15000
adb shell settings put system screen_brightness_mode 0
adb shell settings put system screen_brightness 1
adb shell settings put global stay_on_while_plugged_in 0       # Developer option "Stay awake" off
adb shell svc wifi disable          # Android 13+: cmd wifi set-wifi-enabled disabled
adb shell svc bluetooth disable     # or: cmd bluetooth_manager disable
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
```

Keep Wi-Fi on only if the Wi-Fi hotspot is used.

### 2.11 Disable apps that are not needed

Fewer background apps means less heat, power and data.

```sh
adb shell pm list packages -3                           # apps you installed
adb shell pm list packages -s                           # system apps
adb shell pm disable-user --user 0 <package>            # disable one
adb shell pm enable <package>                           # undo
```

Disable only packages you recognise (social media, games, assistants such as
`com.samsung.android.bixby.agent`, store and cloud apps). Never disable the phone / telephony,
System UI, network stack, settings, providers, Magisk, Tailscale or the Agent.

### 2.12 Optional: Tailscale and ADB over the network

For scrcpy from anywhere, firewalled so the ADB port is reachable only through the VPN: section 8.

## 3. Install the Agent

Build it (see [`agent/README.md`](../agent/README.md)), then:

```sh
adb install -r -g agent/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hotspotmonitor.agent/.ui.MainActivity
```

`-g` grants every runtime permission the Agent requests. Installing with `adb install` also
whitelists the restricted SMS permissions; an APK installed from a file manager cannot get them.

On first start the Agent asks for root: grant it **permanently** in Magisk or KernelSU (Magisk 29
and newer show no pop-up: add the Agent in the Superuser tab, see "Granting root" in 2.1). With root
the Agent grants itself background location, usage access, unrestricted background use and the
battery-optimisation exemption, so later boots need no taps.

Phone with a dead screen and Magisk: grant root without tapping (Magisk 24 and newer, tested
with 31.1; if the command fails, use the Superuser tab through scrcpy instead):

```sh
AGENT_UID=$(adb shell dumpsys package com.hotspotmonitor.agent | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -n 1)
echo "$AGENT_UID"     # a number such as 10234
adb shell "su -c \"magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($AGENT_UID,2,0,1,1)'\""
```

KernelSU: allow the Agent in the KernelSU app's Superuser list (through scrcpy).

Start the service without the UI at any time:

```sh
adb shell am broadcast -a com.hotspotmonitor.agent.action.START -n com.hotspotmonitor.agent/.service.ControlReceiver
```

## 4. Install the keep-alive module

The `hsm-keepalive` module (plan 6.1, source in [`agent/magisk/`](../agent/magisk/README.md))
starts the Agent after every boot and restarts it within a minute if it ever stops.

```sh
tools/phase0/build-magisk-module.sh        # -> tools/phase0/dist/hsm-keepalive.zip
adb push tools/phase0/dist/hsm-keepalive.zip /sdcard/Download/
adb shell su -c 'magisk --install-module /sdcard/Download/hsm-keepalive.zip'          # Magisk
adb shell su -c '/data/adb/ksud module install /sdcard/Download/hsm-keepalive.zip'    # KernelSU
adb reboot
```

(Or install the zip from the Magisk / KernelSU app: Modules, Install from storage.)

Check after the reboot:

```sh
adb shell cat /data/local/tmp/hsm-keepalive.log       # boot completed, permissions, "Agent started"
adb shell pidof com.hotspotmonitor.agent
adb shell su -c 'am force-stop com.hotspotmonitor.agent'; sleep 70; adb shell pidof com.hotspotmonitor.agent   # restarted
```

Optional settings go in `/data/adb/hsm-keepalive.conf` (template:
`agent/magisk/hsm-keepalive.conf.example`): check interval, SMS permissions, extra permissions or
app-ops, "Stay awake" handling, log size. Pause the restarts while debugging with
`adb shell su -c touch /data/adb/hsm-keepalive.pause` (remove the file to resume).

## 5. Permissions: automatic and manual

| Permission or setting | Agent (with root) | hsm-keepalive at every boot | By hand (no root, or to check) |
|-----------------------|-------------------|-----------------------------|--------------------------------|
| Root, permanently | asks once (no pop-up on Magisk 29+) | | Magisk Superuser tab (+) or Grant, Forever; KernelSU Superuser list (or section 3 command) |
| Location, "Allow all the time" | yes | `pm grant` fine, coarse, background | App info, Permissions, Location, Allow all the time |
| Phone (state, USSD) | yes | `pm grant` READ_PHONE_STATE, CALL_PHONE | App info, Permissions, Phone |
| SMS (optional) | yes | `pm grant` SEND/RECEIVE/READ_SMS (`GRANT_SMS=0` to skip) | App info, Permissions, SMS |
| Notifications | yes | `pm grant` POST_NOTIFICATIONS | App info, Notifications |
| Nearby devices (Bluetooth) | yes | `pm grant` BLUETOOTH_CONNECT | App info, Permissions |
| Usage access | yes | `appops set ... GET_USAGE_STATS allow` | Settings, Apps, Special access, Usage access |
| Battery optimisation off | yes | `dumpsys deviceidle whitelist +com.hotspotmonitor.agent` | App info, Battery, Unrestricted |
| Background running | yes | `appops RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND` allow; standby bucket `active` | |
| "Stay awake" off | | `settings put global stay_on_while_plugged_in 0` | Developer options |
| Samsung "Never sleeping apps" | | | Settings, Battery (and device care), Background usage limits, Never sleeping apps: add the Agent |

Check from the PC:

```sh
adb shell dumpsys package com.hotspotmonitor.agent | grep -E 'granted=true' | head -20
adb shell dumpsys deviceidle whitelist | grep hotspotmonitor
adb shell appops get com.hotspotmonitor.agent
```

## 6. Pair with the Server

1. In the Dashboard (admin): **Pair phone**. It shows a QR code, the deep link and an adb command,
   valid for 15 minutes.
2. Screen works: scan the QR code with the phone's camera app; it opens the Agent's pairing
   screen, where you tap **Pair**. If the phone is already paired, a second confirmation
   (**Replace pairing**) is needed. Or type the server URL and code in the Agent's Pairing screen.
3. Dead screen: run the adb command from the Dashboard, for example:

   ```sh
   adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d 'hsm://pair?server=https%3A%2F%2Fhsm.onrender.com&code=ABCD2345' --ez auto true"
   ```

   Copy it as shown: the outer double quotes keep the single quotes for the phone's shell, so the
   `&` reaches the phone. `.ui.AdbPair` is an entry of the pairing screen that only adb (or root)
   can start (it is guarded by `android.permission.DUMP`); only there does `--ez auto true` pair
   without a tap. To replace an existing pairing that way, add `--ez replace true` before the
   closing double quote. The QR code / browsable link ignores both extras and always waits for a
   tap, so no other app or web page can re-pair the phone.
4. Within seconds the Dashboard shows the phone online with live values. If not:
   `adb logcat | grep -i hotspotmonitor`, check the phone has internet over the SIM and that the
   Server URL is reachable from it.

## 7. Headless operation (broken screen)

- **scrcpy over USB** (full screen view and touch, plan 2.2): install scrcpy on the PC and run
  `scrcpy --turn-screen-off --stay-awake --no-audio`. The phone's own display stays dark.
- **scrcpy over Tailscale** (section 8): `adb connect <tailscale-ip>:5555`, then
  `scrcpy -s <tailscale-ip>:5555 --turn-screen-off --no-audio --video-bit-rate=2M --max-size=1024`
  to keep mobile data use low.
- **USB-C HDMI hub and a mouse (Samsung S10/S20/S21)**: once Android is running, a hub with HDMI
  out (Samsung DeX) and a USB mouse stand in for the screen. Download mode shows no video, so the
  unlock steps would be blind: a visible display is strongly preferred (plan 4.6).
- **Touch dead, display fine**: a USB mouse through an OTG adapter.
- **Signal Finder by ear**: the Finder's headless mode works with no screen: Volume Up starts a
  spot test, Volume Down a speed test, and results are spoken (plan 7.3). Spot and speed tests
  can also be started from the Dashboard's Finder screen, which mirrors the live score.
- **Pairing, settings, commands** all work without the screen: pairing by adb (section 6), every
  setting from the Dashboard.

## 8. Tailscale and ADB over the network

Optional (plan 5): reach the phone with scrcpy from anywhere, with the ADB port reachable **only**
through the VPN (plan 10).

1. Install Tailscale on the phone (Play Store, F-Droid, or `adb install`) and sign in once
   (through scrcpy over USB). In the Tailscale admin console, disable key expiry for this device.
2. Android Settings, Network, VPN, Tailscale: "Always-on VPN" on, so it reconnects after reboots.
3. Install the boot script that opens ADB on TCP 5555 behind an iptables rule allowing only
   Tailscale (`tun+` interface, source 100.64.0.0/10 or fd7a:115c:a1e0::/48):

   ```sh
   adb push agent/magisk/extras/hsm-adb-vpn.sh /data/local/tmp/
   adb shell su -c 'cp /data/local/tmp/hsm-adb-vpn.sh /data/adb/service.d/ && chmod 0755 /data/adb/service.d/hsm-adb-vpn.sh'
   adb reboot
   ```

   It fails closed: adbd listens on TCP only after the rules are verified for both IPv4 and
   IPv6. If `iptables` or `ip6tables` fails (or is missing), TCP adb stays off (or is switched off
   again), the reason goes to logcat (`adb logcat -s hsm-adb-vpn`), and it retries every minute;
   the rules are re-checked every 10 minutes. USB adb is never affected.

   What it applies, if you prefer to do it by hand (as root; check that every command succeeds
   before the last line, which opens the port):

   ```sh
   iptables -N HSM_ADB
   iptables -A HSM_ADB -i lo -j RETURN
   iptables -A HSM_ADB -i tun+ -s 100.64.0.0/10 -j RETURN
   iptables -A HSM_ADB -j DROP
   iptables -I INPUT 1 -p tcp --dport 5555 -j HSM_ADB
   # same with ip6tables and fd7a:115c:a1e0::/48
   setprop service.adb.tcp.port 5555; stop adbd; start adbd
   ```

4. Connect from a computer on the tailnet: `adb connect <phone tailscale ip>:5555`. Use the same
   computer whose key was accepted over USB, or append the other computer's `~/.android/adbkey.pub`
   to `/data/misc/adb/adb_keys` on the phone (as root).
5. Check the firewall: from a device on the home network (behind the USB tether)
   `adb connect <tether gateway ip>:5555` must time out; on the phone
   `su -c 'iptables -L HSM_ADB -n -v'` shows the DROP counter rising.

To undo: delete `/data/adb/service.d/hsm-adb-vpn.sh` and reboot.

## 9. Survey and choose the spot

Plan 7.4, with the Agent's Signal Finder. Pair first, so the Dashboard's Finder screen on the
monitoring phone mirrors the live score while the hotspot phone is held up on a pole or a roof
edge.

1. **Walk** slowly past every candidate place: roof corners, each outside wall, balcony, window
   ledges. The tone rises with the score; mark where it is highest.
2. **Spot test** each marked place at mounting height (30 s, hold still). Try two or three
   orientations (upright, flat, turned 90 degrees) and name each spot clearly, for example
   `roof-NE-upright`. Watch the cell-switch count: a spot that keeps switching cells gives unstable
   speed (plan 7.5).
3. **Speed test** the best three spots (3 runs each, roughly 10 to 30 MB of data per run).
4. **Repeat the speed tests at peak time**, around 20:00 to 22:00; tower congestion changes the
   winner. Peak-hour sessions are flagged.
5. **Rank**: Dashboard Finder (or the Agent's Finder) ranks by measured speed first, score second;
   export the CSV for the record.
6. **Soak** the winner for 24 hours in the enclosure on the real cable before mounting
   permanently: `tools/phase0/phase0.sh power-log start` and `heat-log start` (pull both after
   24 hours), with the Agent reporting as usual.

## 10. Mount and set the baseline

1. Mount the phone at the same height and in the same orientation as the winning spot test, in
   the IP65 plastic box: cable entering from below through a gland, drip loop before the wall,
   silica gel, shade above, never direct sun (plan 4.4, 4.5). Fix it firmly.
2. Run a final spot test at the mounted position, then set it as the baseline: Dashboard, Finder,
   the spot, **Set baseline**. The Signal screen then compares live values with it, and the
   "Signal drop" alert (score below 60% of baseline for 15 minutes) uses it.
3. Check again after 24 hours: score close to the baseline, same serving cell, battery at the
   charge limit and not trending down, temperatures below the warning level.

## 11. Final checklist before leaving the phone outdoors

- [ ] `tools/phase0/phase0.sh report`: every must-have row PASS (and the results copied into
      `docs/phase0-results.md`).
- [ ] New battery fitted; no swelling (plan 4.6).
- [ ] No screen lock, no SIM PIN, no personal accounts, automatic updates off.
- [ ] Root survives a reboot **and a cold power-on** (`root --cold-boot`, and `autoboot --test`
      shows root after the automatic start); the Agent has root permanently.
- [ ] Automatic power-on proven (`autoboot --test`, and once by hand with the real cable and port).
- [ ] The Agent hardware checklist in `docs/phase0.md` section 6 done on this phone.
- [ ] `hsm-keepalive` installed; log shows "Agent started" after a reboot; a force-stopped Agent
      comes back within a minute.
- [ ] Device profile matches the phone (Dashboard shows its `profile_id`), charge-control path
      verified, `current_now` sign right (net current positive while charging).
- [ ] Charge limit set (watchdog `charge_limit_pct`) and seen working; 45 °C stop configured.
- [ ] USB tethering comes up by itself after a reboot; the router or PC gets internet through it.
- [ ] Dashboard: paired, live values within 5 seconds, PWA installed on the monitoring phone,
      Telegram and push alerts tested (Alerts, send test).
- [ ] Offline alert works: power the phone off and check the alert arrives within about
      4 minutes (an airplane-mode test from the Dashboard reverts after 2 minutes, too soon for
      the 3-minute offline rule); power it on again and check "back online".
- [ ] Watchdog enabled with the default thresholds (plan 6.5); reboot limit 3 per 6 hours.
- [ ] Baseline set at the mounted position.
- [ ] Screen timeout 15 s, brightness minimum, Wi-Fi and Bluetooth off unless used.
- [ ] Optional: Tailscale + ADB over network works, and the ADB port is closed from the home network.
- [ ] Enclosure closed, cable gland tight, drip loop, shade, fixed firmly; note the date to check
      for battery swelling every few months (plan 4.2).
