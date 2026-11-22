package com.hotspotmonitor.dashboard.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The four parts of Settings; [id] matches the web Dashboard's ?tab= value. */
enum class SettingsTab(val id: String, val label: String, val icon: ImageVector, val adminOnly: Boolean) {
    DEVICE("device", "Device", Icons.Outlined.Tune, false),
    USERS("users", "Users", Icons.Outlined.Group, true),
    ACCOUNT("account", "Account", Icons.Outlined.AccountCircle, false),
    PAIRING("pairing", "Phones", Icons.Outlined.PhonelinkSetup, true),
}

/**
 * Settings (port of web/src/screens/Settings.tsx): device settings editor, users (admin), my
 * account, and pairing and phones (admin). [initialTab] is the web's ?tab= value.
 */
@Composable
fun SettingsScreen(initialTab: String? = null) {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val repoLoading by g.repo.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val tabs = SettingsTab.entries.filter { session.isAdmin || !it.adminOnly }
    var tabId by rememberSaveable(initialTab) { mutableStateOf(initialTab ?: SettingsTab.DEVICE.id) }
    val active = tabs.firstOrNull { it.id == tabId } ?: SettingsTab.DEVICE

    val server = deviceId?.let { data.settings[it] }
    val editor = remember { EditorState().also { it.bind(deviceId, server) } }
    val users = remember { UsersState() }
    val account = remember { AccountState() }
    val pairing = remember { PairingState() }
    val listState = rememberLazyListState()

    // Follow the Server's copy: device switch, or a newer version while there are no local edits.
    LaunchedEffect(deviceId, server?.version) { editor.bind(deviceId, server) }

    LaunchedEffect(active) {
        when (active) {
            SettingsTab.USERS -> if (users.users == null) users.load(g, scope)
            SettingsTab.ACCOUNT -> if (account.config == null) account.loadConfig(g, scope)
            else -> Unit
        }
        if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1)
    }

    PairingPolling(pairing)

    val refreshing = when (active) {
        SettingsTab.DEVICE, SettingsTab.PAIRING -> repoLoading
        SettingsTab.USERS -> users.loading
        SettingsTab.ACCOUNT -> account.loading
    }
    val onRefresh: () -> Unit = {
        when (active) {
            SettingsTab.DEVICE -> g.repo.reloadDevice()
            SettingsTab.USERS -> users.load(g, scope)
            SettingsTab.ACCOUNT -> {
                account.loadConfig(g, scope, manual = true)
                scope.launch { runCatching { g.repo.refreshMe() } }
            }
            SettingsTab.PAIRING -> g.repo.reload()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            SettingsPage(
                listState = listState,
                refreshing = refreshing,
                onRefresh = onRefresh,
                tabs = tabs,
                active = active,
                onTab = { tabId = it.id },
            ) {
                when (active) {
                    SettingsTab.DEVICE -> deviceItems(editor, session, data, deviceId)
                    SettingsTab.USERS -> usersItems(users, session, onAccount = { tabId = SettingsTab.ACCOUNT.id })
                    SettingsTab.ACCOUNT -> accountItems(account, session)
                    SettingsTab.PAIRING -> pairingItems(pairing, session, data, deviceId)
                }
            }
        }
        if (active == SettingsTab.DEVICE) SaveBar(editor, session.canControl && deviceId != null, scope)
    }

    EditorDialogs(editor)
    UsersDialogs(users)
    AccountDialogs(account)
    PairingDialogs(pairing)
}

/** Big title, sticky section switcher, pull-to-refresh; the same look as the shared Page. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsPage(
    listState: androidx.compose.foundation.lazy.LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    tabs: List<SettingsTab>,
    active: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "__header") {
                Column(Modifier.fillMaxWidth()) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = hsm.fg)
                    Text(
                        "Thresholds, watchdog rules, alerts, users and pairing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = hsm.muted,
                    )
                }
            }
            if (tabs.size > 1) {
                stickyHeader(key = "__tabs") { TabSwitcher(tabs, active, onTab) }
            }
            content()
        }
    }
}

/** Segmented switcher with an icon over each label (big touch targets, fits four on a phone). */
@Composable
private fun TabSwitcher(tabs: List<SettingsTab>, active: SettingsTab, onTab: (SettingsTab) -> Unit) {
    Box(Modifier.fillMaxWidth().background(hsm.bg).padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(hsm.surface2).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (t in tabs) {
                val selected = t == active
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) hsm.infoSoft else hsm.surface2)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onTab(t) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, tint = if (selected) hsm.accent else hsm.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) hsm.accent else hsm.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}


/** Round badge with the first letter of a name. */
@Composable
internal fun Avatar(name: String, admin: Boolean, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (admin) hsm.infoSoft else hsm.surface3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            color = if (admin) hsm.accent else hsm.muted,
        )
    }
}

/** Small uppercase heading inside a card. */
@Composable
internal fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = hsm.faint,
        modifier = modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** Clickable row used for menu-like actions inside cards. */
@Composable
internal fun ActionRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) hsm.accent else hsm.faint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) hsm.fg else hsm.faint)
    }
}
