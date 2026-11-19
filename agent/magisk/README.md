# hsm-keepalive (Magisk / KernelSU module)

The root keep-alive from plan 6.1: after every boot it

1. waits for `sys.boot_completed` (and the user's storage),
2. grants the Agent (`com.hotspotmonitor.agent`) its runtime permissions: location including
   background, phone, SMS (optional), notifications, Bluetooth; sets the app-ops
   `GET_USAGE_STATS`, `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`; adds it to the
   `deviceidle` whitelist (battery optimisation off); sets its standby bucket to `active`;
   optionally switches the Developer option "Stay awake" off,
3. starts the Agent with `am broadcast -a com.hotspotmonitor.agent.action.START -n
   com.hotspotmonitor.agent/.service.ControlReceiver`, falling back to
   `am start-foreground-service -n com.hotspotmonitor.agent/.service.AgentService`,
4. every 60 s checks `pidof com.hotspotmonitor.agent` (and every 5 minutes that `AgentService`
   itself runs) and restarts it when it is gone.

**It does not make the phone power on by itself after a power cut.** That is a separate,
per-model change (plan 4.3 and Appendix A): `fastboot oem off-mode-charge 0` on phones with
fastboot, or a charging-mode (LPM) auto-boot change on Samsung. See `docs/install.md` and prove it
with `tools/phase0/phase0.sh autoboot --test`.

The weekly reboot is not here either: the Agent does it (`settings.watchdog.weekly_reboot`).

## Build and install

```sh
tools/phase0/build-magisk-module.sh          # -> tools/phase0/dist/hsm-keepalive.zip
adb push tools/phase0/dist/hsm-keepalive.zip /sdcard/Download/
adb shell su -c 'magisk --install-module /sdcard/Download/hsm-keepalive.zip'   # Magisk
adb shell su -c '/data/adb/ksud module install /sdcard/Download/hsm-keepalive.zip'   # KernelSU
adb reboot
```

Or install the zip from the Magisk / KernelSU app (Modules, Install from storage).

## Files

| File | Purpose |
|------|---------|
| `module.prop` | module id `hsm-keepalive`, version |
| `customize.sh` | install-time messages and permissions |
| `common.sh` | shared functions (grant, start, checks, log) |
| `service.sh` | boot script and keep-alive loop |
| `action.sh` | "Action" button (Magisk 28+ / KernelSU) or by hand: re-grant and start now |
| `uninstall.sh` | removes the log and flag files |
| `hsm-keepalive.conf.example` | optional settings, copied to `/data/adb/hsm-keepalive.conf` |
| `META-INF/...` | standard Magisk installer stub |
| `extras/hsm-adb-vpn.sh` | optional, not in the zip: ADB over TCP reachable only through Tailscale |

## Operating it

```sh
adb shell cat /data/local/tmp/hsm-keepalive.log                    # what it did (rotated at 256 KiB)
adb logcat -s hsm-keepalive                                         # same lines in logcat
adb shell su -c touch /data/adb/hsm-keepalive.pause                 # pause restarts (debugging)
adb shell su -c rm /data/adb/hsm-keepalive.pause                    # resume
adb shell su -c sh /data/adb/modules/hsm-keepalive/action.sh        # grant + start now
```

Settings (`/data/adb/hsm-keepalive.conf`, plain shell assignments; only root can write there):
`CHECK_INTERVAL` (60), `STAY_AWAKE_OFF` (1), `GRANT_SMS` (1), `EXTRA_PERMISSIONS`,
`EXTRA_APPOPS`, `LOG_MAX_KB` (256).

SMS permissions are hard-restricted on Android 10+: they can only be granted when the APK was
installed with `adb install` (which whitelists them). The log lists any permission it could not grant.
