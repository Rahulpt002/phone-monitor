package com.hotspotmonitor.dashboard.ui.system

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.StoredEvent
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.controls.CommandDialogHost
import com.hotspotmonitor.dashboard.ui.controls.rememberCommandRunner
import com.hotspotmonitor.dashboard.ui.controls.rememberControlEnv
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm

private enum class SysTab(val id: String, val label: String) {
    OVERVIEW("overview", "Overview"),
    APPS("apps", "Apps"),
    EVENTS("events", "Events"),
    COMMANDS("commands", "Commands"),
    CONSOLE("console", "Root console"),
}

/** Accepts the native ids and the web Dashboard's (?tab=info|processes|audit ...). */
private fun tabFor(id: String?): SysTab = when (id?.lowercase()) {
    "apps" -> SysTab.APPS
    "events" -> SysTab.EVENTS
    "commands", "audit", "log" -> SysTab.COMMANDS
    "console", "shell" -> SysTab.CONSOLE
    else -> SysTab.OVERVIEW
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SystemScreen(initialTab: String? = null) {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val runner = rememberCommandRunner()
    val env = rememberControlEnv(runner)
    val scope = rememberCoroutineScope()

    val tabs = SysTab.entries.filter { it != SysTab.CONSOLE || session.isAdmin }
    var tabId by rememberSaveable(initialTab) { mutableStateOf(tabFor(initialTab).id) }
    val tab = tabs.firstOrNull { it.id == tabId } ?: SysTab.OVERVIEW

    // Events: kind is a server filter (refetch), severity a local one.
    var kind by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf("") }
    val events = remember { PagedList<StoredEvent>(scope, EVENTS_PAGE, { it.id }, { it.ts }) }
    fun fetchEvents(id: String, k: String): suspend (String?) -> List<StoredEvent> = { before ->
        g.api.events(id, k.ifEmpty { null }, EVENTS_PAGE, before)
            .map { if (it.deviceId.isEmpty()) it.copy(deviceId = id) else it }
            .also { g.store.mergeEvents(it) }
    }
    LaunchedEffect(tab, deviceId, kind) {
        val id = deviceId
        if (tab == SysTab.EVENTS && id != null) events.reset(fetchEvents(id, kind))
    }

    // Command log: filters are local, like the web.
    var logName by rememberSaveable { mutableStateOf("") }
    var logStatus by rememberSaveable { mutableStateOf("") }
    var logSource by rememberSaveable { mutableStateOf("") }
    val log = remember { PagedList<CommandRecord>(scope, LOG_PAGE, { it.id }, { it.createdAt }) }
    fun fetchLog(id: String): suspend (String?) -> List<CommandRecord> = { before ->
        // Only the newest page goes to the store; older pages stay here (the store keeps the newest 500).
        g.api.commands(id, LOG_PAGE, before).also { if (before == null) g.store.mergeCommands(it) }
    }
    LaunchedEffect(tab, deviceId) {
        val id = deviceId
        if (tab == SysTab.COMMANDS && id != null) log.reset(fetchLog(id))
    }

    // Root console: the remembered password lives only while its tab is open.
    val console = remember { ConsoleState() }
    LaunchedEffect(tab) { if (tab != SysTab.CONSOLE) console.password = null }
    LaunchedEffect(deviceId) {
        console.entries.clear()
        console.password = null
    }
    DisposableEffect(Unit) { onDispose { console.password = null } }

    val apps = remember { AppsState(scope, runner) }
    val appsData = if (tab == SysTab.APPS) {
        val last = env.lastByName["app_list"]
        remember(last, data.commands, deviceId) {
            val mine = data.commands.filter { it.deviceId == deviceId }
            val rows = if (last?.status == "done") parseAppList(last.output)?.let { applyAppChanges(it, last.createdAt, mine) } else null
            AppsData(last, rows, recentAppCommands(last?.createdAt, mine))
        }
    } else null

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    CommandDialogHost(runner)
    val snap = env.snapshot
    val info = snap?.deviceInfo
    Page(
        title = "System",
        subtitle = info?.let { "${it.manufacturer} ${it.model} · Android ${it.androidVersion} · Agent ${it.agentVersion}" }
            ?: "Health, apps, events, the command log and the root console.",
        refreshing = loading,
        onRefresh = {
            g.repo.reloadDevice()
            val id = deviceId
            if (id != null) when (tab) {
                SysTab.EVENTS -> events.reset(fetchEvents(id, kind))
                SysTab.COMMANDS -> log.reset(fetchLog(id))
                else -> Unit
            }
        },
    ) {
        if (loadError != null) item(key = "err") { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        stickyHeader(key = "tabs") {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().background(hsm.bg),
                containerColor = hsm.bg,
                contentColor = hsm.accent,
                edgePadding = 0.dp,
                divider = { HorizontalDivider(color = hsm.border) },
            ) {
                for (t in tabs) {
                    Tab(
                        selected = t == tab,
                        onClick = { tabId = t.id },
                        text = { Text(t.label) },
                        selectedContentColor = hsm.accent,
                        unselectedContentColor = hsm.muted,
                    )
                }
            }
        }
        when (tab) {
            SysTab.OVERVIEW -> overviewTab(snap, env.settings, env)
            SysTab.APPS -> if (appsData != null) appsTab(appsData, apps, env.canControl)
            SysTab.EVENTS -> eventsTab(
                list = events,
                live = data.events.filter { it.deviceId == deviceId && (kind.isEmpty() || it.kind == kind) },
                kind = kind,
                severity = severity,
                onKind = { kind = it },
                onSeverity = { severity = it },
                onMore = { before ->
                    val id = deviceId
                    if (id != null) events.loadMore(before, fetchEvents(id, kind))
                },
            )
            SysTab.COMMANDS -> commandsTab(
                list = log,
                live = data.commands.filter { it.deviceId == deviceId },
                filter = LogFilter(logName, logStatus, logSource),
                onName = { logName = it },
                onStatus = { logStatus = it },
                onSource = { logSource = it },
                onMore = { before ->
                    val id = deviceId
                    if (id != null) log.loadMore(before, fetchLog(id))
                },
            )
            SysTab.CONSOLE -> item(key = "console") { ConsoleCard(console, runner, env.canControl, data.commands) }
        }
    }
}
