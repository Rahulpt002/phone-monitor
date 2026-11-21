# Hotspot Monitor app (Android)

The phone app for the Dashboard. It is a [Trusted Web Activity](https://developer.chrome.com/docs/android/trusted-web-activity)
(Google's android-browser-helper): it opens the Dashboard full screen in the phone's Chrome engine,
so every Dashboard screen and admin function is in the app (Overview, Signal, Screen, Controls,
Messages, Settings, Finder, ...). It updates itself with every Dashboard deploy; rebuild it only to
change the icon, name, URL or shortcuts.

- Package `com.hotspotmonitor.dashboard`, Android 8.0 (API 26) or newer.
- Opens `https://gods-eye-tan.vercel.app` (override with `-PdashboardUrl=https://...`).
- Web push notifications from the Dashboard show as this app's notifications (notification
  delegation); the remote screen, two-factor sign-in and everything else behave as in Chrome.
- Long-press the icon for shortcuts: Screen, Controls, Alerts.
- Without Chrome (or another browser that supports TWAs) it falls back to a WebView with a title
  bar; push notifications need Chrome.

## Full screen (Digital Asset Links)

Android shows the app without any browser bar only when the website vouches for the app:
[`web/public/.well-known/assetlinks.json`](../web/public/.well-known/assetlinks.json) lists the
package name and the SHA-256 fingerprint of the signing certificate. It is deployed with the
Dashboard. Until it is live (or with an APK signed by another key) the app still works but shows a
small URL bar at the top.

## Build

```sh
cd mobile
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Needs `mobile/local.properties` (`sdk.dir=...`, git-ignored) and, for a signed release,
`mobile/keystore.properties` (git-ignored):

```
storeFile=/Users/<you>/.hsm-mobile/hotspot-monitor-release.jks
storePassword=...
keyAlias=hotspot-monitor
keyPassword=...
```

**Keep the keystore safe and backed up** (it lives outside the repo, in `~/.hsm-mobile/`).
Android only installs an update signed with the same key, and `assetlinks.json` names its
fingerprint. To use a new key: update the fingerprint in `assetlinks.json`, redeploy the Dashboard,
uninstall the old app, install the new one. Print a fingerprint with:

```sh
keytool -list -v -keystore ~/.hsm-mobile/hotspot-monitor-release.jks | grep SHA256
```

## Install

Copy the APK to the phone and open it (allow "Install unknown apps" for the app you open it
with), or with USB debugging: `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Another Dashboard address

Build with `-PdashboardUrl=https://your-dashboard.example`, change the shortcut URLs in
`app/src/main/res/xml/shortcuts.xml`, and serve the same `assetlinks.json` from that host at
`/.well-known/assetlinks.json`.
