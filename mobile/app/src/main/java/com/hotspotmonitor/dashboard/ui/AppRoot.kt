package com.hotspotmonitor.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hotspotmonitor.dashboard.data.LinkStatus
import com.hotspotmonitor.dashboard.data.Phase
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.ui.alerts.AlertsScreen
import com.hotspotmonitor.dashboard.ui.auth.EnrollScreen
import com.hotspotmonitor.dashboard.ui.auth.LoginScreen
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.controls.ControlsScreen
import com.hotspotmonitor.dashboard.ui.finder.FinderScreen
import com.hotspotmonitor.dashboard.ui.messages.MessagesScreen
import com.hotspotmonitor.dashboard.ui.more.MoreScreen
import com.hotspotmonitor.dashboard.ui.network.NetworkScreen
import com.hotspotmonitor.dashboard.ui.overview.OverviewScreen
import com.hotspotmonitor.dashboard.ui.power.PowerScreen
import com.hotspotmonitor.dashboard.ui.screen.RemoteScreen
import com.hotspotmonitor.dashboard.ui.settings.SettingsScreen
import com.hotspotmonitor.dashboard.ui.signal.SignalScreen
import com.hotspotmonitor.dashboard.ui.system.SystemScreen
import com.hotspotmonitor.dashboard.ui.theme.hsm

/** Sign-in, two-factor enrollment, or the app itself, depending on the session. */
@Composable
fun AppRoot(deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(hsm.bg)) {
        when (session.phase) {
            Phase.SIGNED_OUT -> LoginScreen()
            Phase.ENROLL -> EnrollScreen()
            Phase.READY -> {
                LaunchedEffect(session.token) {
                    g.repo.startSession()
                    g.push.register()
                }
                MainShell(deepLink, onDeepLinkHandled)
            }
        }
    }
}

@Composable
private fun MainShell(deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val admin = session.canControl
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?')

    val navigate: (Dest, String?) -> Unit = { d, tab ->
        val route = if (tab != null) "${d.route}?tab=$tab" else d.route
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = tab == null
        }
    }

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        val dest = Dest.forPath(link)
        if (dest != null && (admin || !dest.adminOnly)) {
            val tab = link.substringAfter("tab=", "").substringBefore('&').ifEmpty { null }
            navigate(dest, tab)
        }
        onDeepLinkHandled()
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalNavigate provides navigate) {
        Scaffold(
            containerColor = hsm.bg,
            contentWindowInsets = WindowInsets(0),
            topBar = { TopBar() },
            bottomBar = {
                NavigationBar(containerColor = hsm.surface, tonalElevation = 0.dp) {
                    for (d in Dest.bottomBar(admin)) {
                        val selected = currentRoute == d.route || (d == Dest.MORE && Dest.more(admin).any { it.route == currentRoute })
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigate(d, null) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label.substringBefore(" and"), maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = hsm.accent,
                                selectedTextColor = hsm.accent,
                                indicatorColor = hsm.infoSoft,
                                unselectedIconColor = hsm.muted,
                                unselectedTextColor = hsm.muted,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            // The app draws edge to edge, so the keyboard does not resize the window: pad the content
            // by whatever part of the keyboard the bottom bar does not already cover.
            Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                ConnectionBanner()
                NavHost(nav, startDestination = Dest.OVERVIEW.route, modifier = Modifier.fillMaxSize()) {
                    composable(Dest.OVERVIEW.route) { OverviewScreen() }
                    composable(Dest.SIGNAL.route) { SignalScreen() }
                    composable(Dest.NETWORK.route) { NetworkScreen() }
                    composable(Dest.POWER.route) { PowerScreen() }
                    composable(Dest.CONTROLS.route) { ControlsScreen() }
                    composable(Dest.SCREEN.route) { RemoteScreen() }
                    composable(
                        Dest.SYSTEM.route + "?tab={tab}",
                        arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { SystemScreen(initialTab = it.arguments?.getString("tab")) }
                    composable(Dest.FINDER.route) { FinderScreen() }
                    composable(Dest.MESSAGES.route) { MessagesScreen() }
                    composable(Dest.ALERTS.route) { AlertsScreen() }
                    composable(
                        Dest.SETTINGS.route + "?tab={tab}",
                        arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { SettingsScreen(initialTab = it.arguments?.getString("tab")) }
                    composable(Dest.MORE.route) { MoreScreen() }
                }
            }
        }
    }
}

/** Phone picker with its online state, and the signed-in user. */
@Composable
private fun TopBar() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val now = rememberNow(10_000)
    val snap = deviceId?.let { data.snapshots[it] }
    val summary = data.devices.firstOrNull { it.deviceId == deviceId }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(hsm.bg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = data.devices.size > 1) { menu = true }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PhoneAndroid, null, tint = hsm.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        summary?.name ?: snap?.name ?: if (data.devices.isEmpty()) "No phone paired" else "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = hsm.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snap != null) {
                        Text(
                            if (snap.online) "Online" + (snap.connectedSince?.let { " · since ${formatRelative(it, now).removeSuffix(" ago")}" } ?: "")
                            else "Offline · last seen ${formatRelative(snap.lastSeen, now)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (snap.online) hsm.ok else hsm.danger,
                        )
                    }
                }
                if (data.devices.size > 1) Icon(Icons.Outlined.ExpandMore, null, tint = hsm.muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (d in data.devices) {
                    DropdownMenuItem(
                        text = { Text(d.name.ifBlank { d.deviceId }) },
                        trailingIcon = { Pill(if (d.online) "Online" else "Offline", if (d.online) Tone.OK else Tone.DANGER, dot = true) },
                        onClick = {
                            menu = false
                            g.repo.selectDevice(d.deviceId)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (snap != null) Pill(if (snap.online) "Online" else "Offline", if (snap.online) Tone.OK else Tone.DANGER, dot = true)
    }
}

/** Shown while the live connection to the Server is down. */
@Composable
private fun ConnectionBanner() {
    val g = graph()
    val conn by g.repo.socket.state.collectAsStateWithLifecycle()
    val now = rememberNow(1000)
    val down = conn.status == LinkStatus.WAITING && conn.downSinceMs != null && now - conn.downSinceMs!! > 3000
    AnimatedVisibility(down, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.fillMaxWidth().background(hsm.warnSoft).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val secs = ((conn.nextRetryAtMs ?: now) - now).coerceAtLeast(0) / 1000
            Text(
                "Live connection lost. Retrying in ${secs}s…",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.fg,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { g.repo.socket.retryNow() }) { Text("Retry now", color = hsm.accent) }
        }
    }
}
