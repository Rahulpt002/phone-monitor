# Hotspot Agent (Android)

The app that runs on the rooted outdoor hotspot phone: collectors, command executor, watchdog,
Signal Finder and the link to the Server (plan sections 6 and 7). Module layout, ownership and
interfaces: [ARCHITECTURE.md](ARCHITECTURE.md). Wire protocol: [docs/protocol.md](../docs/protocol.md).

- applicationId `com.hotspotmonitor.agent`, minSdk 29 (Android 10), target/compile SDK 35
- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1 (wrapper), Compose BOM 2024.12.01, Room 2.6.1,
  OkHttp 4.12.0, kotlinx.serialization 1.7.3, coroutines 1.9.0, libsu 6.0.0 (JitPack)

## Requirements

- JDK 17 (`export JAVA_HOME=/opt/homebrew/opt/openjdk@17` on macOS with Homebrew)
- Android SDK with platform 35 and build-tools 35.0.0
- `agent/local.properties` with the SDK path (git-ignored):

  ```
  sdk.dir=/Users/<you>/Library/Android/sdk
  ```

## Build and test

```sh
cd agent
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM unit tests (protocol fixtures, score, outbox, profiles, ...)
./gradlew lintDebug                # Android lint, about 1 min; report in app/build/reports/lint-results-debug.html
./gradlew assembleDebug testDebugUnitTest lintDebug --console=plain
./gradlew --stop                   # stop the Gradle daemon when done
```

The protocol tests read `../shared/fixtures/*.json`, so the Kotlin types cannot drift from the
TypeScript contract unnoticed.

`./gradlew assembleRelease` produces an unsigned release APK; sign it with your own key
(`apksigner`) before installing. Release builds forbid plain HTTP; debug builds allow it so you can
pair with a dev Server on your LAN (`http://<pc-lan-ip>:8080`).

## Install and first start

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hotspotmonitor.agent/.ui.MainActivity     # or tap the app icon
```

On first start the Agent asks for root (grant it permanently in Magisk / KernelSU). With root it
grants itself the runtime permissions (including "Allow all the time" location), usage access,
unrestricted background use and the battery-optimisation exemption, and switches location services
on (Android 10+ reports no cell data while location is off), so a phone with a dead screen needs no
taps. Without root, use the Setup screen to grant each item by hand.

Start the service without the UI (ADB or a script):

```sh
adb shell am broadcast -a com.hotspotmonitor.agent.action.START -n com.hotspotmonitor.agent/.service.ControlReceiver
# or, as root:
adb shell su -c 'am start-foreground-service -n com.hotspotmonitor.agent/.service.AgentService'
```

The service also starts on boot and after an app update.

## Pairing

In the Dashboard press **Pair phone**. Scan the QR code with the phone, or for a phone with a dead
screen run the `adb_command` the Dashboard shows:

```sh
adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d 'hsm://pair?server=https%3A%2F%2Fhsm.onrender.com&code=ABCD2345' --ez auto true"
```

Keep the outer double quotes: `adb shell` passes one string to the phone's shell, and without them
the `&` in the link ends the command there. `.ui.AdbPair` is an activity-alias of the pairing
screen guarded by `android.permission.DUMP`, which only the adb shell uid (and root) holds; only
this entry honours `--ez auto true` (pair without a tap) and `--ez replace true` (replace an
existing pairing). The browsable `hsm://pair` link (QR code, other apps, web pages) ignores both
extras: it always waits for a tap on **Pair**, plus **Replace pairing** when the phone is already
paired, so no app or web page can silently re-point a paired phone.

The Agent stores the device token encrypted with an Android Keystore key and connects to
`wss://<server>/ws/agent`.

## Device profiles

Per-model differences (charge-control path, USB function, automatic power-on method, command
overrides, thermal zones) live in `app/src/main/assets/profiles/<id>.json`. The schema is in
[ARCHITECTURE.md section 8](ARCHITECTURE.md#8-device-profile-schema). With no matching file the
Agent uses `generic.json`, or its built-in generic profile. The Dashboard can override profile
fields through `settings.profile_override`.

## Magisk keep-alive module

The root keep-alive script (plan 6.1: restarts the service if it ever dies) is a Magisk module in
`agent/magisk/`. Build the flashable zip with `tools/phase0/build-magisk-module.sh` and install it
from the Magisk app (Modules -> Install from storage).

## Logs

- Logcat: every line is tagged `HSM/<module>` (for example `HSM/root`, `HSM/event`). Logcat tag
  filters do not take wildcards, so filter with `adb logcat | grep ' HSM/'`.
- Files: `files/logs/agent.log` (+ `agent.log.1`), at most 2 x 1 MB. On a debug build:
  `adb shell run-as com.hotspotmonitor.agent cat files/logs/agent.log`.
- The app's Log screen shows the last 1000 lines.
