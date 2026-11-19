package com.hotspotmonitor.agent.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.hotspotmonitor.agent.core.AgentGraph

/** What the Agent still lacks to run headless (plan 5 "Agent permissions"). */
data class PermissionReport(
    /** Runtime permissions other than background location that are not granted. */
    val missingForeground: List<String>,
    val foregroundLocation: Boolean,
    val backgroundLocation: Boolean,
    val batteryExempt: Boolean,
    val usageAccess: Boolean,
    /** The system location switch; Android 10+ gives no cell info while it is off. */
    val locationServices: Boolean,
) {
    val allGood: Boolean
        get() = missingForeground.isEmpty() && backgroundLocation && batteryExempt && usageAccess && locationServices

    /** Number of open setup items, for the Status screen. */
    val openItems: Int
        get() = (if (missingForeground.isEmpty()) 0 else 1) +
            listOf(backgroundLocation, batteryExempt, usageAccess, locationServices).count { !it }

    companion object {
        fun read(context: Context, graph: AgentGraph): PermissionReport {
            val missing = graph.rootSetup.runtimePermissions
                .filter { it != Manifest.permission.ACCESS_BACKGROUND_LOCATION }
                .filter { !granted(context, it) }
            return PermissionReport(
                missingForeground = missing,
                foregroundLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
                backgroundLocation = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                batteryExempt = runCatching { graph.rootSetup.isIgnoringBatteryOptimizations() }.getOrDefault(false),
                usageAccess = hasUsageAccess(context),
                locationServices = runCatching { graph.rootSetup.isLocationEnabled() }.getOrDefault(false),
            )
        }

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = runCatching {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
            return when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> granted(context, Manifest.permission.PACKAGE_USAGE_STATS)
                else -> false
            }
        }

        /** "ACCESS_FINE_LOCATION" -> "Precise location". */
        fun label(permission: String): String = when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Precise location"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Location all the time"
            Manifest.permission.READ_PHONE_STATE -> "Phone state"
            Manifest.permission.CALL_PHONE -> "Phone calls (USSD)"
            Manifest.permission.RECEIVE_SMS -> "Receive SMS"
            Manifest.permission.READ_SMS -> "Read SMS"
            Manifest.permission.SEND_SMS -> "Send SMS"
            "android.permission.BLUETOOTH_CONNECT" -> "Bluetooth"
            "android.permission.POST_NOTIFICATIONS" -> "Notifications"
            else -> permission.substringAfterLast('.')
        }
    }
}
