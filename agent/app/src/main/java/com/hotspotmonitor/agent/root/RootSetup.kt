package com.hotspotmonitor.agent.root

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.core.KeyValueStore
import kotlinx.coroutines.delay

data class RootSetupResult(
    /** False when nothing had to be done (already set up for this version). */
    val ran: Boolean,
    val rooted: Boolean,
    /** Commands that failed, for the log and the Status screen. */
    val failed: List<String> = emptyList(),
)

/**
 * One-time self-grant via root so the phone needs no taps (plan 5 "Agent permissions", 6.1
 * "Headless operation"): runtime permissions including background location, usage access,
 * unrestricted background running, the battery-optimisation whitelist, and location services on
 * (Android 10+ returns no cell info while location is off).
 *
 * Idempotent: re-runs after an app update, when a permission is missing again, or on [ensure]
 * with force = true. Failures are logged and reported, never thrown.
 */
class RootSetup(
    private val context: Context,
    private val shell: RootShell,
    private val store: KeyValueStore,
    private val log: AgentLog,
    private val versionCode: Int,
) {
    private val pkg: String = context.packageName

    /** Runtime permissions the Agent needs on this Android version. */
    val runtimePermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missingPermissions(): List<String> =
        runtimePermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(pkg)
    }

    /** The system-wide location switch (getAllCellInfo and requestCellInfoUpdate need it on). */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.isLocationEnabled
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensure(force: Boolean = false): RootSetupResult {
        val missing = missingPermissions()
        val doneForVersion = store.getLong(KEY_DONE_VERSION, -1) == versionCode.toLong()
        if (!force && doneForVersion && missing.isEmpty() && isIgnoringBatteryOptimizations() && isLocationEnabled()) {
            return RootSetupResult(ran = false, rooted = shell.rooted.value == true)
        }
        if (!shell.isRooted()) {
            log.w(TAG, "No root: grant permissions by hand (missing: ${missing.joinToString { it.substringAfterLast('.') }})")
            return RootSetupResult(ran = false, rooted = false, failed = missing)
        }
        val commands = buildList {
            runtimePermissions.forEach { add("pm grant $pkg $it") }
            add("appops set $pkg GET_USAGE_STATS allow")
            add("appops set $pkg RUN_IN_BACKGROUND allow")
            add("appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            add("dumpsys deviceidle whitelist +$pkg")
        }
        val failed = commands.filter { !shell.run(it, timeoutMs = 10_000).ok }.toMutableList()
        if (!isLocationEnabled() && !enableLocationServices()) failed += "location services on"
        val stillMissing = missingPermissions()
        if (stillMissing.isEmpty()) store.putLong(KEY_DONE_VERSION, versionCode.toLong())
        if (failed.isEmpty() && stillMissing.isEmpty()) {
            log.i(TAG, "Root setup complete: permissions granted, background limits lifted")
        } else {
            log.w(TAG, "Root setup incomplete. Failed: ${failed.joinToString("; ")}. " +
                "Still missing: ${stillMissing.joinToString { it.substringAfterLast('.') }}")
        }
        return RootSetupResult(ran = true, rooted = true, failed = failed)
    }

    /** Switches location services on; true when they are on afterwards. */
    private suspend fun enableLocationServices(): Boolean {
        val attempts = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add("cmd location set-location-enabled true")
            add("settings put secure location_mode 3")
            // Android 10 builds that ignore location_mode honour the per-provider list.
            add("settings put secure location_providers_allowed +gps")
            add("settings put secure location_providers_allowed +network")
        }
        for (cmd in attempts) {
            shell.run(cmd, timeoutMs = 10_000)
            delay(LOCATION_SETTLE_MS)
            if (isLocationEnabled()) {
                log.i(TAG, "Location services were off; switched on so cell info and position work")
                return true
            }
        }
        log.w(TAG, "Could not switch location services on: cell info and position stay empty until it is on")
        return false
    }

    private companion object {
        const val TAG = "root-setup"
        const val LOCATION_SETTLE_MS = 500L
        const val KEY_DONE_VERSION = "root_setup.done_version"
    }
}
