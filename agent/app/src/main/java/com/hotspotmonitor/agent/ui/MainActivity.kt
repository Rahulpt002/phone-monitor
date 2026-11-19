package com.hotspotmonitor.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.AgentStartReason
import com.hotspotmonitor.agent.service.AgentService

/**
 * Local UI entry point. Opening it starts the service (unless it was stopped with the Stop button)
 * and, until setup is done, opens the permission onboarding first.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AgentApp.graph(this)
        if (!graph.prefs.getBoolean(AgentService.KEY_USER_STOPPED, false)) {
            AgentService.start(this, AgentStartReason.MANUAL)
        }
        val startOnSetup = !graph.prefs.getBoolean(KEY_SETUP_DONE, false) && !PermissionReport.read(this, graph).allGood
        setContent {
            AgentTheme { AgentNavHost(graph, startOnSetup) }
        }
    }

    companion object {
        /** Set when the user leaves the onboarding screen; it then only opens on request. */
        const val KEY_SETUP_DONE = "ui.setup_done"

        /** Local-only option: keep the display on while the app is in front. */
        const val KEY_KEEP_SCREEN_ON = "ui.keep_screen_on"
    }
}

/** Top-level destinations of the local UI (bottom bar). */
enum class AgentRoute(val route: String, val label: String, val icon: ImageVector) {
    STATUS("status", "Status", Icons.Filled.Home),
    FINDER("finder", "Finder", Icons.Filled.LocationOn),
    PAIRING("pairing", "Pairing", Icons.Filled.Lock),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    LOG("log", "Log", Icons.AutoMirrored.Filled.List),
}

/** Permission onboarding; not in the bottom bar. */
private const val SETUP_ROUTE = "setup"

@Composable
fun AgentNavHost(graph: AgentGraph, startOnSetup: Boolean = false) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    var keepScreenOn by remember { mutableStateOf(graph.prefs.getBoolean(MainActivity.KEY_KEEP_SCREEN_ON, false)) }

    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AgentRoute.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = { nav.goTo(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = if (startOnSetup) SETUP_ROUTE else AgentRoute.STATUS.route,
            ) {
                composable(AgentRoute.STATUS.route) {
                    StatusScreen(graph, onOpenSetup = { nav.navigate(SETUP_ROUTE) { launchSingleTop = true } })
                }
                composable(AgentRoute.FINDER.route) { FinderScreen(graph) }
                composable(AgentRoute.PAIRING.route) { PairingScreen(graph) }
                composable(AgentRoute.SETTINGS.route) {
                    SettingsScreen(
                        graph,
                        onOpenFinder = { nav.goTo(AgentRoute.FINDER.route) },
                        onOpenSetup = { nav.navigate(SETUP_ROUTE) { launchSingleTop = true } },
                        keepScreenOn = keepScreenOn,
                        onKeepScreenOnChange = { on ->
                            keepScreenOn = on
                            graph.prefs.putBoolean(MainActivity.KEY_KEEP_SCREEN_ON, on)
                        },
                    )
                }
                composable(AgentRoute.LOG.route) { LogScreen(graph) }
                composable(SETUP_ROUTE) {
                    SetupScreen(graph, onDone = {
                        graph.prefs.putBoolean(MainActivity.KEY_SETUP_DONE, true)
                        // Opened from a screen: go back to it. Opened at launch: it is the start, so replace it.
                        if (nav.previousBackStackEntry != null) {
                            nav.popBackStack()
                        } else {
                            nav.goTo(AgentRoute.STATUS.route, clearStart = true)
                        }
                    })
                }
            }
        }
    }
}

/** Bottom-bar navigation: one copy of each destination, state kept per tab. */
private fun NavHostController.goTo(route: String, clearStart: Boolean = false) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = !clearStart
            inclusive = clearStart
        }
        launchSingleTop = true
        restoreState = !clearStart
    }
}
