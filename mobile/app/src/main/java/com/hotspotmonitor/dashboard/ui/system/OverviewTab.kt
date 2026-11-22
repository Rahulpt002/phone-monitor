package com.hotspotmonitor.dashboard.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.ProcessInfo
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.DeviceSnapshot
import com.hotspotmonitor.dashboard.data.formatDateTime
import com.hotspotmonitor.dashboard.data.formatDuration
import com.hotspotmonitor.dashboard.data.formatMb
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Stat
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.UpdatedAt
import com.hotspotmonitor.dashboard.ui.components.isStale
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.controls.ActionButton
import com.hotspotmonitor.dashboard.ui.controls.ChipRow
import com.hotspotmonitor.dashboard.ui.controls.CommandCard
import com.hotspotmonitor.dashboard.ui.controls.ControlEnv
import com.hotspotmonitor.dashboard.ui.controls.LastResult
import com.hotspotmonitor.dashboard.ui.controls.OutputBlock
import com.hotspotmonitor.dashboard.ui.controls.hasResult
import com.hotspotmonitor.dashboard.ui.controls.isInFlight
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Overview tab of System: uptime and boots, load and memory, processes, software, position and the
// capability map (web System.tsx InfoTab + ProcessesTab).

private const val SLOW_STALE_MS = 5 * 60_000L

internal fun LazyListScope.overviewTab(snap: DeviceSnapshot?, settings: Settings, env: ControlEnv) {
    item(key = "ov_uptime") { UptimeCard(snap, settings) }
    item(key = "ov_load") { LoadCard(snap) }
    item(key = "ov_top") { TopProcessesCard(snap) }
    item(key = "ov_ps") { ProcessListCard(env) }
    item(key = "ov_soft") { SoftwareCard(snap) }
    item(key = "ov_pos") { PositionCard(snap, settings) }
    if (env.canControl) {
        item(key = "ov_refresh") { CommandCard("refresh", env, compact = true) }
        item(key = "ov_probe") { CommandCard("capability_probe", env, compact = true) }
    }
    item(key = "ov_caps") { CapabilityCard(env.capabilities, snap != null) }
}

@Composable
private fun Rows(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun UptimeCard(snap: DeviceSnapshot?, settings: Settings) {
    val sys = snap?.groups?.system
    val now = rememberNow(10_000)
    val stale = isStale(snap?.stateTs?.get("system"), afterMs = SLOW_STALE_MS)
    SectionCard(title = "Uptime and boots", stale = stale) {
        EqualRow {
            Stat("Phone uptime", formatDuration(sys?.uptimeS), Modifier.weight(1f), size = 22.sp)
            Stat("Agent uptime", formatDuration(sys?.agentUptimeS), Modifier.weight(1f), size = 22.sp)
        }
        Spacer(Modifier.height(14.dp))
        Rows {
            KeyValueRow("Boot count", sys?.bootCount?.toString() ?: DASH)
            KeyValueRow(
                "Last boot",
                sys?.lastBootAt?.let { "${formatDateTime(it)} (${formatRelative(it, now)})" } ?: DASH,
            )
            KeyValueRow("Boot reason", "", trailing = {
                Text(sys?.bootReason ?: DASH, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = hsm.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            val used = sys?.rebootsInWindow
            val w = settings.watchdog
            KeyValueRow(
                "Reboots in window",
                if (used != null) "$used of ${w.maxReboots} (${trimNumber(w.rebootWindowH, 1)} h)" else DASH,
                valueColor = if (used != null && used >= w.maxReboots) hsm.warn else androidx.compose.ui.graphics.Color.Unspecified,
            )
            KeyValueRow("Screen", "", trailing = {
                when (sys?.screenOn) {
                    true -> Pill("On", Tone.OK, dot = true)
                    false -> Pill("Off", Tone.NEUTRAL, dot = true)
                    null -> Text(DASH, color = hsm.fg)
                }
            })
        }
        Spacer(Modifier.height(10.dp))
        UpdatedAt(snap?.stateTs?.get("system"), staleAfterMs = SLOW_STALE_MS)
    }
}

@Composable
private fun LoadCard(snap: DeviceSnapshot?) {
    val sys = snap?.groups?.system
    val stale = isStale(snap?.stateTs?.get("system"), afterMs = SLOW_STALE_MS)
    SectionCard(title = "Load and memory", stale = stale) {
        Text("CPU load average", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        Spacer(Modifier.height(4.dp))
        val load = sys?.cpuLoad
        EqualRow {
            listOf("1 min", "5 min", "15 min").forEachIndexed { i, l ->
                Stat(l, load?.getOrNull(i)?.let { trimNumber(it, 2) } ?: DASH, Modifier.weight(1f), size = 20.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Meter("RAM", sys?.ramTotalMb, sys?.ramAvailMb)
        Spacer(Modifier.height(14.dp))
        Meter("Storage", sys?.storageTotalMb, sys?.storageFreeMb)
    }
}

@Composable
private fun Meter(label: String, total: Long?, free: Long?) {
    val used = if (total != null && free != null) total - free else null
    val pct = if (used != null && total != null && total > 0) used * 100.0 / total else null
    val hot = pct != null && pct > 90
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = hsm.fg, modifier = Modifier.weight(1f))
        Text(
            pct?.let { "${it.roundToInt()}% used" } ?: DASH,
            style = MaterialTheme.typography.labelLarge,
            color = if (hot) hsm.warn else hsm.fg,
        )
    }
    Spacer(Modifier.height(6.dp))
    ProgressBar(pct, if (hot) Tone.WARN else Tone.INFO)
    Spacer(Modifier.height(4.dp))
    Text(
        if (free != null) "${formatMb(free)} free of ${formatMb(total)}" else "No reading",
        style = MaterialTheme.typography.bodySmall,
        color = hsm.muted,
    )
}

@Composable
private fun TopProcessesCard(snap: DeviceSnapshot?) {
    val top = snap?.groups?.system?.top.orEmpty()
    val stale = isStale(snap?.stateTs?.get("system"), afterMs = SLOW_STALE_MS)
    SectionCard(
        title = "Top processes",
        stale = stale,
        action = { Text("last slow reading", style = MaterialTheme.typography.labelSmall, color = hsm.faint) },
    ) {
        if (top.isEmpty()) {
            EmptyState("No process list in the last reading.")
        } else {
            val maxCpu = top.maxOf { it.cpuPct ?: 0.0 }.coerceAtLeast(1.0)
            top.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                ProcessRow(p, maxCpu)
            }
        }
    }
}

@Composable
private fun ProcessRow(p: ProcessInfo, maxCpu: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = hsm.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("PID ${p.pid}", style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(p.cpuPct?.let { "${trimNumber(it, 1)}%" } ?: DASH, style = MaterialTheme.typography.labelLarge, color = hsm.fg)
                Text(p.memMb?.let { formatMb(it) } ?: DASH, style = MaterialTheme.typography.labelSmall, color = hsm.muted)
            }
        }
        val frac = ((p.cpuPct ?: 0.0) / maxCpu).coerceIn(0.0, 1.0).toFloat()
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(hsm.surface3)) {
            Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(RoundedCornerShape(50)).background(hsm.accent))
        }
    }
}

@Composable
private fun ProcessListCard(env: ControlEnv) {
    val last = env.lastByName["process_list"]
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    SectionCard(
        title = "Full process list",
        action = {
            if (env.canControl) {
                ActionButton("Run top", small = true, busy = busy) {
                    busy = true
                    scope.launch {
                        try {
                            env.runner.run("process_list")
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
    ) {
        Text("Output of top -n 1 on the phone.", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
        Spacer(Modifier.height(8.dp))
        if (last == null) {
            EmptyState(if (env.canControl) "No process list yet. Tap \"Run top\"." else "No process list yet.")
        } else {
            LastResult(last, compact = true)
            Spacer(Modifier.height(8.dp))
            val text = last.error?.takeIf { it.isNotBlank() } ?: last.output?.takeIf { it.isNotBlank() }
            when {
                hasResult(last) && text != null -> OutputBlock(text, isError = !last.error.isNullOrBlank(), maxHeight = 420.dp, wrap = false)
                isInFlight(last) -> Text("Waiting for the phone…", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                else -> Text("No output.", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
            }
        }
    }
}

@Composable
private fun SoftwareCard(snap: DeviceSnapshot?) {
    val info = snap?.deviceInfo
    val sys = snap?.groups?.system
    SectionCard(title = "Software") {
        Rows {
            KeyValueRow("Phone", info?.let { "${it.manufacturer} ${it.model} (${it.device})" } ?: DASH)
            KeyValueRow("Android", info?.let { "${it.androidVersion} (SDK ${it.sdk})" } ?: sys?.androidVersion ?: DASH)
            KeyValueRow("Build", "", trailing = {
                Text(info?.buildId ?: DASH, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = hsm.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            KeyValueRow(
                "Agent",
                info?.let { i -> i.agentVersion + (i.agentVersionCode?.let { " ($it)" } ?: "") } ?: sys?.agentVersion ?: DASH,
            )
            KeyValueRow("Device profile", info?.profileId ?: DASH)
            KeyValueRow("Root", info?.let { if (it.rooted) it.rootImpl ?: "yes" else "not rooted" } ?: DASH)
            KeyValueRow("IMEI (last 4)", info?.imeiTail ?: DASH)
        }
    }
}

@Composable
private fun PositionCard(snap: DeviceSnapshot?, settings: Settings) {
    val pos = snap?.groups?.position
    val uri = LocalUriHandler.current
    val now = rememberNow(10_000)
    val stale = isStale(snap?.stateTs?.get("position"), afterMs = SLOW_STALE_MS)
    val w = settings.watchdog
    SectionCard(
        title = "Position",
        stale = stale,
        action = if (pos?.lat != null && pos.lon != null) ({
            LinkButton("Open map") {
                runCatching { uri.openUri("https://www.openstreetmap.org/?mlat=${pos.lat}&mlon=${pos.lon}#map=18/${pos.lat}/${pos.lon}") }
            }
        }) else null,
    ) {
        Rows {
            KeyValueRow("Location", if (pos?.lat != null && pos.lon != null) "${trimNumber(pos.lat, 5)}, ${trimNumber(pos.lon, 5)}" else DASH)
            KeyValueRow("Accuracy", pos?.accM?.let { "±${it.roundToInt()} m" } ?: DASH)
            KeyValueRow("Fix", pos?.fixAt?.let { formatRelative(it, now) } ?: DASH)
            KeyValueRow("Pitch / roll", pos?.pitchDeg?.let { "${trimNumber(it, 1)}° / ${trimNumber(pos.rollDeg ?: 0.0, 1)}°" } ?: DASH)
            KeyValueRow("Compass", pos?.azimuthDeg?.let { "${it.roundToInt()}°" } ?: DASH)
            KeyValueRow(
                "Tilt change",
                pos?.tiltChangeDeg?.let { "${trimNumber(it, 1)}° (alert at ${trimNumber(w.moveTiltDeg, 1)}°)" } ?: DASH,
                valueColor = if ((pos?.tiltChangeDeg ?: 0.0) >= w.moveTiltDeg) hsm.warn else androidx.compose.ui.graphics.Color.Unspecified,
            )
            KeyValueRow(
                "Moved",
                pos?.movedM?.let { "${it.roundToInt()} m (alert at ${w.moveDistanceM.roundToInt()} m)" } ?: DASH,
                valueColor = if ((pos?.movedM ?: 0.0) >= w.moveDistanceM) hsm.warn else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
    }
}

@Composable
private fun CapabilityCard(caps: Map<String, CapabilityEntry>, hasPhone: Boolean) {
    val entries = caps.entries.sortedBy { it.key }
    val unsupported = entries.filter { !it.value.supported }
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "Capability map",
        action = if (entries.isNotEmpty()) ({ LinkButton(if (open) "Hide list" else "Show all") { open = !open } }) else null,
    ) {
        if (entries.isEmpty()) {
            EmptyState(if (hasPhone) "The phone has not reported its capability map yet." else "No phone data yet.")
            return@SectionCard
        }
        ChipRow {
            Pill("${entries.size - unsupported.size} supported", Tone.OK)
            if (unsupported.isNotEmpty()) Pill("${unsupported.size} not supported", Tone.DANGER)
        }
        val shown = if (open) entries else unsupported
        if (!open && unsupported.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Every command is supported on this phone.", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        }
        if (shown.isNotEmpty()) Spacer(Modifier.height(6.dp))
        shown.forEachIndexed { i, (name, c) ->
            if (i > 0) HorizontalDivider(color = hsm.border)
            CapabilityRow(name, c)
        }
    }
}

@Composable
private fun CapabilityRow(name: String, c: CapabilityEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(COMMANDS[name]?.label ?: name, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
            Text(name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = hsm.faint)
            val detail = listOfNotNull(c.method, c.note?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = hsm.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.padding(start = 8.dp))
        if (c.supported) Pill("Yes", Tone.OK) else Pill("No", Tone.DANGER)
    }
}
