# Hotspot Monitor app (Android, native)

The phone app for watching and controlling the hotspot phone: a native Kotlin + Jetpack Compose
(Material 3) app that talks to the same Server as the web Dashboard (REST under `/api`, the live
`/ws/dashboard` socket, the remote-screen relay). Every Dashboard function is in the app:
Overview, Signal, Network, Power and heat, Controls, remote Screen, System (events, commands, root
console), Signal Finder, Messages, Alerts, Settings (device settings, users, account, pairing).

- Package `com.hotspotmonitor.dashboard` (debug builds: `.debug`), Android 8.0 (API 26) or newer.
- Default Server: `https://phone-monitor-vwpz.onrender.com` (`-PserverUrl=...`; editable on the
  sign-in screen). Debug builds also allow plain `http://` for a development Server.
- Sign-in needs the password and the authenticator code; accounts without two-factor are taken
  through enrollment (QR code, or "Add to authenticator app").
- The session token is stored encrypted with an Android Keystore key.
- Alerts arrive as push notifications through Firebase Cloud Messaging when configured (below).
- The remote screen decodes the phone's H.264 stream with the hardware decoder (MediaCodec).

## Code layout

| Path | What |
|------|------|
| `data/` | `ApiClient` (REST), `DashboardSocket` (live updates), `Store` (state, port of `web/src/store/reducer.ts`), `Repo` (loading, selected phone, commands, sign-in/out), `SessionStore`, `Models`, `Format` |
| `ui/theme/` | Colours (same palette as the web Dashboard), typography |
| `ui/components/` | Cards, stats, pills, gauges, charts, dialogs (confirm, password + code), events, auto-revert countdown, QR |
| `ui/<screen>/` | One package per screen |
| `push/` | Firebase setup and the messaging service |

The Agent's protocol models (`agent/app/src/main/java/com/hotspotmonitor/agent/protocol/`) are
compiled into the app unchanged, so telemetry, commands, settings and Finder types have one
definition for both Android apps.

## Build

```sh
cd mobile
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (signed, minified)
./gradlew assembleDebug -PminifyDebug   # debug app shrunk and optimized like a release, to test the
                                        # R8 rules against a plain-http development Server
```

Needs `mobile/local.properties` (`sdk.dir=...`) and, for a signed release, `mobile/keystore.properties`
(both git-ignored):

```
storeFile=/Users/<you>/.hsm-mobile/hotspot-monitor-release.jks
storePassword=...
keyAlias=hotspot-monitor
keyPassword=...
```

**Keep the keystore safe and backed up** (it lives outside the repo in `~/.hsm-mobile/`): Android
only installs updates signed with the same key. `web/public/.well-known/assetlinks.json` lists the
certificate fingerprint so Dashboard links open in the app.

## Push notifications (Firebase Cloud Messaging)

1. In the [Firebase console](https://console.firebase.google.com) create a project (Analytics not
   needed), then **Add app → Android** with package `com.hotspotmonitor.dashboard`. Download
   `google-services.json`.
2. Create `mobile/firebase.properties` (git-ignored) from that file:
   ```
   projectId=<project_info.project_id>
   appId=<client[0].client_info.mobilesdk_app_id>
   apiKey=<client[0].api_key[0].current_key>
   senderId=<project_info.project_number>
   ```
   and rebuild the app. Without the file the app works but shows "Push: Not set up".
3. **Project settings → Service accounts → Generate new private key.** Put the whole JSON (or its
   base64) in the Server's `FIREBASE_SERVICE_ACCOUNT` environment variable (Render: Environment),
   then redeploy. `GET /api/config` then reports `fcm_configured: true`.
4. After signing in, the app registers its token (`POST /api/push/fcm/register`) for that session;
   signing out removes it. Alerts follow the "Push" alert channel switch in Settings.

## Install

Copy the APK to the phone and open it (allow "Install unknown apps" for the app you open it
with), or with USB debugging: `adb install -r app/build/outputs/apk/release/app-release.apk`.
Version 2 replaces the earlier web-wrapper app of the same name (same package and key).
