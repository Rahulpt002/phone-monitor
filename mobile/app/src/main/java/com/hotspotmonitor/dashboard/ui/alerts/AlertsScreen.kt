package com.hotspotmonitor.dashboard.ui.alerts

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.data.AlertRecord
import com.hotspotmonitor.dashboard.data.PublicConfig
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.formatTime
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.push.PushStatus
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.components.severityTone
import com.hotspotmonitor.dashboard.ui.components.toneColor
import com.hotspotmonitor.dashboard.ui.components.toneSoft
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// --- alert kinds (shared/src/settings.ts ALERT_KINDS, labels from web lib/settingsSchema.ts) -------

val ALERT_KINDS = listOf(
    "offline", "back_online", "weak_power", "low_battery", "hot", "signal_drop", "cell_changed",
    "tether_down", "usb_power_lost", "data_quota", "moved", "watchdog", "carrier_sms", "server_test",
)

private val ALERT_LABEL = mapOf(
    "offline" to "Phone offline",
    "back_online" to "Back online",
    "weak_power" to "Weak power (plugged in but draining)",
    "low_battery" to "Low battery",
    "hot" to "Hot (temperature warning / critical)",
    "signal_drop" to "Signal drop vs baseline",
    "cell_changed" to "Serving cell changed",
    "tether_down" to "USB tethering down",
    "usb_power_lost" to "USB power lost",
    "data_quota" to "Data quota steps",
    "moved" to "Phone moved or tilted",
    "watchdog" to "Watchdog acted",
    "carrier_sms" to "Carrier SMS received",
    "server_test" to "Test alerts",
)

fun alertKindLabel(kind: String): String = ALERT_LABEL[kind] ?: titleCase(kind)

/** The screen that explains an alert of [kind] (null when none does). */
private fun alertDest(kind: String, admin: Boolean): Pair<Dest, String?>? = when (kind) {
    "offline", "back_online" -> Dest.OVERVIEW to null
    "weak_power", "low_battery", "hot", "usb_power_lost" -> Dest.POWER to null
    "signal_drop", "cell_changed" -> Dest.SIGNAL to null
    "tether_down", "data_quota" -> Dest.NETWORK to null
    "moved" -> Dest.SYSTEM to null
    "watchdog" -> Dest.SYSTEM to "events"
    "carrier_sms" -> if (admin) Dest.MESSAGES to null else null
    else -> null
}

/** "Today", "Yesterday", "Monday, 16 Nov", "Mon, 2 Mar", or a full date for other years. */
fun dayLabel(ms: Long?, now: Long): String {
    if (ms == null) return "Unknown date"
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) && !d.isAfter(today) -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMM"))
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
        else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private const val PAGE = 100

private val SEVERITIES = listOf("critical" to "Critical", "warning" to "Warning", "info" to "Info")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlertsScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val navigate = LocalNavigate.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = rememberNow(15_000)

    var severity by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    // Pages loaded with "Load older" are also kept here: the store caps its alert list.
    var older by remember { mutableStateOf<List<AlertRecord>>(emptyList()) }
    var hasMore by remember { mutableStateOf<Boolean?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val merged = remember(data.alerts, older) {
        (data.alerts + older).distinctBy { it.id }.sortedByDescending { isoMs(it.ts) ?: Long.MIN_VALUE }
    }
    val bySeverity = merged.filter { kind.isEmpty() || it.kind == kind }
    val rows = bySeverity.filter { severity.isEmpty() || it.severity == severity }
    val multiDevice = data.devices.size > 1
    val names = data.devices.associate { it.deviceId to it.name.ifBlank { it.deviceId } }

    fun loadMore() {
        if (loadingMore) return
        val before = merged.lastOrNull()?.ts
        loadingMore = true
        error = null
        scope.launch {
            try {
                val page = g.api.alerts(null, PAGE, before)
                g.store.mergeAlerts(page)
                older = (older + page).distinctBy { it.id }
                hasMore = page.size >= PAGE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                loadingMore = false
            }
        }
    }

    fun refresh() {
        refreshing = true
        scope.launch {
            try {
                g.store.mergeAlerts(g.api.alerts(null, PAGE))
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                refreshing = false
            }
        }
    }

    fun sendTest() {
        testing = true
        scope.launch {
            try {
                g.store.mergeAlerts(listOf(g.api.testAlert()))
                Toast.makeText(ctx, "Test alert sent on every configured channel.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(ctx, "Test alert failed: ${e.userMessage()}", Toast.LENGTH_LONG).show()
            } finally {
                testing = false
            }
        }
    }

    fun open(a: AlertRecord) {
        val dest = alertDest(a.kind, session.isAdmin) ?: return
        val id = a.deviceId
        if (id != null && id != deviceId) {
            if (data.devices.none { it.deviceId == id }) return
            g.repo.selectDevice(id)
        }
        navigate(dest.first, dest.second)
    }

    Page(
        title = "Alerts",
        subtitle = "Everything the server told you about, newest first.",
        refreshing = refreshing,
        onRefresh = ::refresh,
        actions = {
            if (session.isAdmin) {
                OutlinedButton(onClick = ::sendTest, enabled = session.canControl && !testing) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = hsm.accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Send test")
                }
            }
        },
    ) {
        item(key = "push") { PushCard() }

        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All (${bySeverity.size})", severity.isEmpty()) { severity = "" }
                    for ((value, label) in SEVERITIES) {
                        val n = bySeverity.count { it.severity == value }
                        Chip("$label ($n)", severity == value, tone = severityTone(value)) { severity = if (severity == value) "" else value }
                    }
                }
                KindPicker(kind) { kind = it }
            }
        }

        if (error != null) item(key = "error") { Notice(Tone.DANGER, "Couldn't load alerts", error) }

        if (rows.isEmpty()) {
            item(key = "empty") {
                SectionCard {
                    EmptyState(if (merged.isEmpty()) "No alerts yet." else "No alerts match these filters.")
                    if (merged.isEmpty()) {
                        Text(
                            "Alerts appear here when the phone goes offline, runs hot, loses power, the signal drops and more. Choose which ones in Settings.",
                            style = MaterialTheme.typography.bodySmall, color = hsm.muted,
                        )
                    }
                }
            }
        } else {
            var lastDay: String? = null
            for (a in rows) {
                val day = dayLabel(isoMs(a.ts), now)
                if (day != lastDay) {
                    lastDay = day
                    stickyHeader(key = "day_${a.id}") {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = hsm.muted,
                            modifier = Modifier.fillMaxWidth().background(hsm.bg).padding(vertical = 6.dp),
                        )
                    }
                }
                item(key = "alert_${a.id}") {
                    AlertRow(
                        a, now,
                        device = if (multiDevice) a.deviceId?.let { names[it] ?: it } else null,
                        onClick = alertDest(a.kind, session.isAdmin)?.let { { open(a) } },
                    )
                }
            }
        }

        item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                if (hasMore == false) {
                    Text("No older alerts", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
                } else if (merged.isNotEmpty()) {
                    OutlinedButton(onClick = ::loadMore, enabled = !loadingMore, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        if (loadingMore) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = hsm.accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Loading…")
                        } else Text("Load older alerts")
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tone: Tone? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = tone?.let { t -> { Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(t))) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = tone?.let { toneSoft(it) } ?: hsm.infoSoft,
            selectedLabelColor = hsm.fg,
        ),
    )
}

@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = kind.isNotEmpty(),
            onClick = { open = true },
            label = { Text(if (kind.isEmpty()) "Kind: all alerts" else "Kind: ${alertKindLabel(kind)}") },
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = hsm.infoSoft, selectedLabelColor = hsm.fg),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All alerts") }, onClick = { onPick(""); open = false })
            for (k in ALERT_KINDS) {
                DropdownMenuItem(text = { Text(alertKindLabel(k)) }, onClick = { onPick(k); open = false })
            }
        }
    }
}

private fun severityIcon(s: String): ImageVector = when (s) {
    "critical" -> Icons.Outlined.ErrorOutline
    "warning" -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.Info
}

@Composable
private fun AlertRow(a: AlertRecord, now: Long, device: String?, onClick: (() -> Unit)?) {
    val ctx = LocalContext.current
    val tone = severityTone(a.severity)
    val shape = RoundedCornerShape(16.dp)
    val mapUrl = (a.data?.get("map_url") as? JsonPrimitive)?.contentOrNull
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(hsm.surface)
            .border(1.dp, if (a.severity == "critical") hsm.danger.copy(alpha = 0.45f) else hsm.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(toneSoft(tone)), contentAlignment = Alignment.Center) {
            Icon(severityIcon(a.severity), contentDescription = a.severity, tint = toneColor(tone), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    a.title.ifBlank { alertKindLabel(a.kind) },
                    style = MaterialTheme.typography.titleMedium,
                    color = hsm.fg,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(formatTime(a.ts), style = MaterialTheme.typography.labelMedium, color = hsm.faint)
            }
            if (a.message.isNotBlank()) Text(a.message, style = MaterialTheme.typography.bodyMedium, color = hsm.fg.copy(alpha = 0.9f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(a.severity, tone)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(alertKindLabel(a.kind), device, formatRelative(a.ts, now)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (a.channels.isNotEmpty()) "Sent via ${a.channels.joinToString(" and ") { titleCase(it) }}" else "Not delivered to any channel",
                style = MaterialTheme.typography.bodySmall,
                color = if (a.channels.isNotEmpty()) hsm.faint else hsm.warn,
            )
            if (mapUrl != null && (mapUrl.startsWith("https://") || mapUrl.startsWith("http://"))) {
                LinkButton("Open map") {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl))) }
                }
            }
        }
        if (onClick != null) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "Open",
                tint = hsm.faint,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp).size(20.dp),
            )
        }
    }
}

// --- push on this phone ------------------------------------------------------------------------

@Composable
private fun PushCard() {
    val g = graph()
    val ctx = LocalContext.current
    val navigate = LocalNavigate.current
    val status by g.push.status.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf<PublicConfig?>(null) }
    var configLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        config = runCatching { g.api.config() }.getOrNull()
        configLoaded = true
    }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    val (label, tone) = when {
        status == PushStatus.NOT_CONFIGURED -> "Unavailable" to Tone.NEUTRAL
        !notificationsOn -> "Blocked" to Tone.WARN
        status == PushStatus.ON -> "On" to Tone.OK
        status == PushStatus.REGISTERING -> "Connecting…" to Tone.INFO
        status == PushStatus.FAILED -> "Failed" to Tone.DANGER
        else -> "Off" to Tone.NEUTRAL
    }

    SectionCard(title = "Push on this phone", action = { Pill(label, tone, dot = true) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                status == PushStatus.NOT_CONFIGURED -> Text(
                    "This app build has no Firebase settings, so alerts show here only while the app is open.",
                    style = MaterialTheme.typography.bodyMedium, color = hsm.muted,
                )
                config?.fcmConfigured == false -> Notice(
                    Tone.WARN, "The server can't push to this app",
                    "It has no Firebase credentials, so alerts reach this phone only while the app is open.",
                )
            }
            if (status != PushStatus.NOT_CONFIGURED && !notificationsOn) {
                Notice(
                    Tone.WARN, "Notifications are off for this app",
                    "Android is blocking them, so push alerts stay silent.",
                    action = {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            runCatching { ctx.startActivity(intent) }
                        }) { Text("Open notification settings") }
                    },
                )
            }
            if (status == PushStatus.OFF || status == PushStatus.FAILED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (status == PushStatus.FAILED) "Registering this phone for push failed." else "This phone is not registered for push yet.",
                        style = MaterialTheme.typography.bodyMedium, color = hsm.muted, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { g.push.register() }) { Text(if (status == PushStatus.FAILED) "Try again" else "Turn on") }
                }
            }
            Text(
                "Telegram: " + when {
                    !configLoaded -> "checking…"
                    config == null -> "unknown (server not reachable)"
                    config!!.telegramConfigured -> "configured on the server"
                    else -> "not configured on the server"
                } + ". Which alerts are sent, and on which channels, is set in Settings.",
                style = MaterialTheme.typography.bodySmall, color = hsm.muted,
            )
            LinkButton("Alert settings") { navigate(Dest.SETTINGS, "alerts") }
        }
    }
}
