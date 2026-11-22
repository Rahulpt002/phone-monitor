package com.hotspotmonitor.dashboard.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.TetherClient
import com.hotspotmonitor.agent.protocol.TetherInfo
import com.hotspotmonitor.agent.protocol.TrafficInfo
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.formatBps
import com.hotspotmonitor.dashboard.data.formatBytes
import com.hotspotmonitor.dashboard.data.formatDate
import com.hotspotmonitor.dashboard.data.formatPercent
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.auth.copy
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.UpdatedAt
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.components.toneColor
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.signal.Caption
import com.hotspotmonitor.dashboard.ui.signal.ChartLine
import com.hotspotmonitor.dashboard.ui.signal.FactTile
import com.hotspotmonitor.dashboard.ui.signal.Gap
import com.hotspotmonitor.dashboard.ui.signal.HistoryHeader
import com.hotspotmonitor.dashboard.ui.signal.MetricChartCard
import com.hotspotmonitor.dashboard.ui.signal.StaleLine
import com.hotspotmonitor.dashboard.ui.signal.YesNoPill
import com.hotspotmonitor.dashboard.ui.signal.rememberHistory
import com.hotspotmonitor.dashboard.ui.signal.rememberHistoryRange
import com.hotspotmonitor.dashboard.ui.signal.seriesColor
import com.hotspotmonitor.dashboard.ui.signal.staleHint
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.max
import kotlin.math.roundToInt

private val NETWORK_METRICS = listOf(
    "net.rx_bps", "net.tx_bps", "net.ping_ms", "net.jitter_ms", "net.ping_loss_pct", "traffic.cycle_rx", "traffic.cycle_tx",
)

private val fmtBps: (Double) -> String = { formatBps(it) }
private val fmtMs: (Double) -> String = { "${trimNumber(it, 0)} ms" }
private val fmtPct: (Double) -> String = { "${trimNumber(it, 1)}%" }
private val fmtBytes: (Double) -> String = { formatBytes(it) }

private fun sumOrNull(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

@Composable
fun NetworkScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val snap = deviceId?.let { data.snapshots[it] }
    val settings = deviceId?.let { data.settings[it]?.typed } ?: Settings.DEFAULT
    val grp = remember(snap?.state) { snap?.groups }
    val net = grp?.net
    val tether = grp?.tether
    val traffic = grp?.traffic
    val netStale = staleHint(snap, settings, "net")
    val tetherStale = staleHint(snap, settings, "tether")
    val trafficStale = staleHint(snap, settings, "traffic")
    val (range, setRange) = rememberHistoryRange()
    val history = rememberHistory(NETWORK_METRICS, range)

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val c0 = seriesColor(0)
    val c1 = seriesColor(1)
    val tetherProblem = tether?.usbConnected == true && tether.usbTetherOn == false

    Page(
        title = "Network",
        subtitle = "Tethering, hotspot, speed, latency and data usage",
        refreshing = loading,
        onRefresh = {
            g.repo.reload()
            history.reload()
        },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        if (tetherProblem) {
            item {
                Notice(
                    Tone.DANGER, "USB connected but tethering is off",
                    if (settings.watchdog.tetherFix) "The watchdog turns it back on after ${settings.watchdog.tetherFixAfterS} s."
                    else "The watchdog tethering fix is turned off in Settings.",
                )
            }
        }

        item(key = "internet") { InternetCard(net, netStale, snap?.stateTs?.get("net")) }
        item(key = "sharing") { SharingCard(tether, tetherStale, snap?.stateTs?.get("tether")) }
        item(key = "clients") { ClientsCard(tether?.clients.orEmpty(), tetherStale != null) }
        item(key = "usage") { DataUsageCard(traffic, settings, trafficStale) }
        if (!traffic?.perIf.isNullOrEmpty()) item(key = "ifaces") { InterfacesCard(traffic!!, trafficStale != null) }

        item(key = "history") { HistoryHeader(range, setRange) }
        item(key = "c-speed") {
            MetricChartCard(
                "Speed", listOf(ChartLine("net.rx_bps", "Download", c0), ChartLine("net.tx_bps", "Upload", c1)),
                history, range, fmtBps, yMin = 0.0,
            )
        }
        item(key = "c-ping") {
            MetricChartCard(
                "Ping and jitter", listOf(ChartLine("net.ping_ms", "Ping", c0), ChartLine("net.jitter_ms", "Jitter", c1)),
                history, range, fmtMs, yMin = 0.0,
            )
        }
        item(key = "c-loss") {
            MetricChartCard("Packet loss", listOf(ChartLine("net.ping_loss_pct", "Loss", c0)), history, range, fmtPct, yMin = 0.0, yMax = 100.0)
        }
        item(key = "c-data") {
            MetricChartCard(
                "Data used this cycle", listOf(ChartLine("traffic.cycle_rx", "Downloaded", c0), ChartLine("traffic.cycle_tx", "Uploaded", c1)),
                history, range, fmtBytes, yMin = 0.0,
            )
        }
    }
}

// --- internet ----------------------------------------------------------------------------------

@Composable
private fun InternetCard(net: NetInfo?, stale: String?, ts: String?) {
    val now = rememberNow(5000)
    val ctx = LocalContext.current
    SectionCard(title = "Internet", stale = stale != null, action = { UpdatedAt(ts) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (net?.online) {
                        true -> "Online"
                        false -> "Down"
                        null -> "Unknown"
                    },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (net?.online) {
                        true -> hsm.ok
                        false -> hsm.danger
                        null -> hsm.muted
                    },
                )
                Text(
                    when {
                        net == null -> "No reading yet"
                        !net.online && net.internetDownSince != null -> "Since ${formatRelative(net.internetDownSince, now)}"
                        net.httpMs != null -> "HTTP check ${net.httpMs} ms"
                        else -> "Online check"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = hsm.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (net?.dnsOk) {
                    true -> Pill("DNS OK", Tone.OK, dot = true)
                    false -> Pill("DNS failing", Tone.DANGER, dot = true)
                    null -> Pill("DNS ?", Tone.NEUTRAL, dot = true)
                }
                net?.activeIf?.let { Pill(it, Tone.INFO) }
            }
        }
        if (net?.online == false) {
            Text(
                "The watchdog recovers it by itself (data cycle, airplane cycle, then reboot).",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Gap(14.dp)
        EqualRow {
            SpeedStat("Download", net?.rxBps, Icons.Outlined.ArrowDownward, seriesColor(0), Modifier.weight(1f))
            SpeedStat("Upload", net?.txBps, Icons.Outlined.ArrowUpward, seriesColor(1), Modifier.weight(1f))
        }
        Gap(10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactTile("Ping", net?.pingMs?.let { "${it.roundToInt()} ms" } ?: DASH, Modifier.weight(1f))
            val loss = net?.pingLossPct
            FactTile(
                "Loss", formatPercent(loss, 1), Modifier.weight(1f),
                valueColor = when {
                    loss == null || loss <= 0.0 -> Color.Unspecified
                    loss >= 20 -> hsm.danger
                    else -> hsm.warn
                },
            )
            FactTile("Jitter", net?.jitterMs?.let { "${trimNumber(it, 1)} ms" } ?: DASH, Modifier.weight(1f))
        }
        Gap(14.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyRow("Public IPv4", net?.publicIpv4) { copy(ctx, it, "IPv4 address copied") }
            CopyRow("Public IPv6", net?.publicIpv6) { copy(ctx, it, "IPv6 address copied") }
            KeyValueRow("Mobile interface", net?.activeIf ?: DASH)
        }
        StaleLine(stale)
    }
}

@Composable
private fun SpeedStat(label: String, bps: Long?, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(hsm.surface2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(formatBps(bps), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = hsm.fg, maxLines = 1)
    }
}

/** Monospace address with tap-to-copy. */
@Composable
private fun CopyRow(label: String, value: String?, onCopy: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (value != null) Modifier.clickable { onCopy(value) } else Modifier)
            .semantics { if (value != null) contentDescription = "$label $value, tap to copy" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = hsm.muted, modifier = Modifier.width(110.dp))
        Text(
            value ?: DASH,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = hsm.fg,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) Icon(Icons.Outlined.ContentCopy, null, tint = hsm.faint, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

// --- tethering ---------------------------------------------------------------------------------

@Composable
private fun SharingCard(tether: TetherInfo?, stale: String?, ts: String?) {
    SectionCard(title = "Sharing", stale = stale != null, action = { UpdatedAt(ts) }) {
        // USB
        SharingHeader(Icons.Outlined.Usb, "USB tethering") {
            when (tether?.usbTetherOn) {
                true -> Pill("On", Tone.OK, dot = true)
                false -> Pill("Off", Tone.DANGER, dot = true)
                null -> Pill("Unknown", Tone.NEUTRAL)
            }
        }
        Gap(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactTile(
                "Cable",
                when (tether?.usbConnected) {
                    true -> "Connected"
                    false -> "Not connected"
                    null -> DASH
                },
                Modifier.weight(1f),
                valueColor = if (tether?.usbConnected == false) hsm.warn else Color.Unspecified,
            )
            FactTile("Function", tether?.usbFunction?.name ?: DASH, Modifier.weight(1f))
        }
        Gap(8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyValueRow("Interface", tether?.tetherIf ?: DASH)
            KeyValueRow("Gateway IP", tether?.tetherIp ?: DASH)
        }
        Gap(14.dp)
        HorizontalDivider(color = hsm.border)
        Gap(14.dp)
        // Hotspot
        SharingHeader(Icons.Outlined.Wifi, "Wi-Fi hotspot") { YesNoPill(tether?.hotspotOn, "On", "Off") }
        Gap(8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyValueRow("Name (SSID)", tether?.hotspotSsid ?: DASH)
            KeyValueRow("Clients", (tether?.clients?.size ?: 0).toString())
        }
        StaleLine(stale)
    }
}

@Composable
private fun SharingHeader(icon: ImageVector, title: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = hsm.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = hsm.fg, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ClientsCard(clients: List<TetherClient>, stale: Boolean) {
    val ctx = LocalContext.current
    SectionCard(
        title = "Connected clients",
        stale = stale,
        action = if (clients.isNotEmpty()) ({ Text("${clients.size}", style = MaterialTheme.typography.labelLarge, color = hsm.muted) }) else null,
    ) {
        if (clients.isEmpty()) {
            EmptyState("No clients seen on the tether or hotspot interfaces.")
            return@SectionCard
        }
        Column {
            clients.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                Row(
                    Modifier.fillMaxWidth().clickable { copy(ctx, c.ip, "IP address copied") }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(hsm.surface2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Devices, null, tint = hsm.muted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.hostname ?: "Unnamed device", style = MaterialTheme.typography.titleMedium, color = hsm.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.ip, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = hsm.fg)
                        if (c.mac != null) Text(c.mac!!, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = hsm.muted)
                    }
                    c.iface?.let { Pill(it, Tone.NEUTRAL) }
                }
            }
        }
        Text("Tap a client to copy its IP address.", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
    }
}

// --- data usage --------------------------------------------------------------------------------

@Composable
private fun DataUsageCard(traffic: TrafficInfo?, settings: Settings, stale: String?) {
    val quota = if (settings.plan.quotaGb > 0) settings.plan.quotaGb * 1e9 else 0.0
    val cycleUsed = sumOrNull(traffic?.cycleRx, traffic?.cycleTx)
    val today = sumOrNull(traffic?.todayRx, traffic?.todayTx)
    val pct = if (quota > 0 && cycleUsed != null) cycleUsed / quota * 100 else null
    val tone = when {
        pct == null -> Tone.INFO
        pct >= 100 -> Tone.DANGER
        pct >= 80 -> Tone.WARN
        else -> Tone.INFO
    }
    SectionCard(title = "Data usage", stale = stale != null) {
        Caption("This cycle" + (traffic?.cycleStart?.let { " · since ${formatDate(it)}" } ?: ""))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBytes(cycleUsed), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = if (pct != null && pct >= 80) toneColor(tone) else hsm.fg)
            Spacer(Modifier.width(8.dp))
            Text(
                if (quota > 0) "of ${formatBytes(quota)}" else "no quota (unlimited)",
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (pct != null) {
            Gap(8.dp)
            QuotaBar(pct, settings.alerts.quotaStepsPct, toneColor(tone))
            Gap(6.dp)
            Text(
                "${formatPercent(pct, 1)} used · ${formatBytes(max(0.0, quota - (cycleUsed ?: 0)))} left",
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.fg,
            )
            Text(
                "Alerts at ${settings.alerts.quotaStepsPct.joinToString("%, ")}% of the plan.",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
            )
        } else {
            Text("Set a quota in Settings > Data plan to track it here.", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        }
        Gap(14.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactTile("Today", formatBytes(today), Modifier.weight(1f), sub = "↓ ${formatBytes(traffic?.todayRx)} · ↑ ${formatBytes(traffic?.todayTx)}")
            FactTile("Cycle", formatBytes(cycleUsed), Modifier.weight(1f), sub = "↓ ${formatBytes(traffic?.cycleRx)} · ↑ ${formatBytes(traffic?.cycleTx)}")
        }
        StaleLine(stale)
    }
}

/** Quota progress with tick marks at the alert steps. */
@Composable
private fun QuotaBar(pct: Double, steps: List<Int>, color: Color) {
    val track = hsm.surface3
    val tick = hsm.fg.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxWidth().height(12.dp).semantics { contentDescription = "${trimNumber(pct, 0)} percent of the plan used" }) {
        val h = size.height
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        val fw = (pct / 100).coerceIn(0.0, 1.0).toFloat() * size.width
        if (fw > 0) drawRoundRect(color, size = Size(max(fw, h), h), cornerRadius = r)
        for (s in steps) {
            if (s <= 0 || s >= 100) continue
            val x = size.width * s / 100f
            drawLine(tick, Offset(x, -2f), Offset(x, h + 2f), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun InterfacesCard(traffic: TrafficInfo, stale: Boolean) {
    val rows = traffic.perIf.orEmpty().entries.sortedByDescending { it.value.rx + it.value.tx }
    SectionCard(title = "Interfaces", stale = stale) {
        Column {
            rows.forEachIndexed { i, (name, c) ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = hsm.fg)
                        Text("↓ ${formatBytes(c.rx)} · ↑ ${formatBytes(c.tx)}", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                    }
                    Text(formatBytes(c.rx + c.tx), style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                }
            }
        }
        Text(
            "Kernel counters per interface since it came up; they reset when the phone reboots.",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.faint,
        )
    }
}
