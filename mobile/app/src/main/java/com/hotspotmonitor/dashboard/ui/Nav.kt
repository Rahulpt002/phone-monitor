package com.hotspotmonitor.dashboard.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every screen of the app. [path] matches the web Dashboard route (deep links). */
enum class Dest(val route: String, val label: String, val icon: ImageVector, val adminOnly: Boolean = false, val path: String) {
    OVERVIEW("overview", "Overview", Icons.Outlined.Dashboard, path = "/"),
    SIGNAL("signal", "Signal", Icons.Outlined.SignalCellularAlt, path = "/signal"),
    NETWORK("network", "Network", Icons.Outlined.NetworkCheck, path = "/network"),
    POWER("power", "Power and heat", Icons.Outlined.BatteryChargingFull, path = "/power"),
    CONTROLS("controls", "Controls", Icons.Outlined.Tune, adminOnly = true, path = "/controls"),
    SCREEN("screen", "Screen", Icons.Outlined.ScreenShare, adminOnly = true, path = "/screen"),
    SYSTEM("system", "System", Icons.Outlined.Memory, path = "/system"),
    FINDER("finder", "Signal Finder", Icons.Outlined.Explore, path = "/finder"),
    MESSAGES("messages", "Messages", Icons.Outlined.Sms, adminOnly = true, path = "/messages"),
    ALERTS("alerts", "Alerts", Icons.Outlined.Notifications, path = "/alerts"),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, path = "/settings"),
    MORE("more", "More", Icons.Outlined.Apps, path = "/more");

    companion object {
        fun forPath(path: String?): Dest? {
            val p = path?.substringBefore('?')?.trimEnd('/')?.ifEmpty { "/" } ?: return null
            return entries.firstOrNull { it.path == p }
        }

        fun bottomBar(admin: Boolean): List<Dest> =
            if (admin) listOf(OVERVIEW, SIGNAL, SCREEN, CONTROLS, MORE) else listOf(OVERVIEW, SIGNAL, NETWORK, ALERTS, MORE)

        /** Destinations listed on the More screen. */
        fun more(admin: Boolean): List<Dest> {
            val bar = bottomBar(admin).toSet()
            return entries.filter { it != MORE && it !in bar && (admin || !it.adminOnly) }
        }
    }
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

/** Navigate to another screen (optionally with a sub-tab, e.g. "events" on System). */
val LocalNavigate = staticCompositionLocalOf<(Dest, String?) -> Unit> { { _, _ -> } }

@Composable
fun graph(): AppGraph = LocalGraph.current

/** Small persisted UI preferences (theme). */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hsm_ui", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(m: ThemeMode) {
        prefs.edit().putString("theme", m.name).apply()
        _theme.value = m
    }
}
