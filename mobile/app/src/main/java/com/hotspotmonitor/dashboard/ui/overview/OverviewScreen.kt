package com.hotspotmonitor.dashboard.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.BatteryStatus
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.formatBps
import com.hotspotmonitor.dashboard.data.formatBytes
import com.hotspotmonitor.dashboard.data.formatCurrent
import com.hotspotmonitor.dashboard.data.formatDate
import com.hotspotmonitor.dashboard.data.formatDuration
import com.hotspotmonitor.dashboard.data.formatNetType
import com.hotspotmonitor.dashboard.data.formatPercent
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.formatTemp
import com.hotspotmonitor.dashboard.data.formatThermalStatus
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.EventList
import com.hotspotmonitor.dashboard.ui.components.GradeChip
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.PendingReverts
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.RingGauge
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Stat
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.UpdatedAt
import com.hotspotmonitor.dashboard.ui.components.isStale
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.roundToInt

@Composable
fun OverviewScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val navigate = LocalNavigate.current
    val now = rememberNow(5000)
    val snap = deviceId?.let { data.snapshots[it] }
    val settings = deviceId?.let { data.settings[it]?.typed } ?: com.hotspotmonitor.agent.protocol.Settings.DEFAULT
    val grp = snap?.groups
    val radio = grp?.radio
    val net = grp?.net
    val power = grp?.power
    val heat = grp?.heat
    val traffic = grp?.traffic
    val system = grp?.system
    val tether = grp?.tether
    val fastStale = isStale(snap?.stateTs?.get("radio"), snap?.stateTs?.get("net"))
    val slowStale = isStale(snap?.stateTs?.get("power"), snap?.stateTs?.get("heat"), afterMs = 5 * 60_000)

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val info = snap?.deviceInfo
    Page(
        title = "Overview",
        subtitle = info?.let { "${it.manufacturer} ${it.model} · Android ${it.androidVersion}" },
        refreshing = loading,
        onRefresh = { g.repo.reload() },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        item { PendingReverts() }
        if (net != null && !net.online) {
            item {
                Notice(
                    Tone.DANGER, "Internet down on the phone",
                    (net.internetDownSince?.let { "Since ${formatRelative(it, now)}. " } ?: "") +
                        "The watchdog recovers it by itself (data cycle, airplane cycle, then reboot).",
                )
            }
        }

        // Status hero
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                snap == null -> "Unknown"
                                snap.online -> "Online"
                                else -> "Offline"
                            },
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                snap == null -> hsm.muted
                                snap.online -> hsm.ok
                                else -> hsm.danger
                            },
                        )
                        Text(
                            if (snap?.online == true) "Connected ${formatRelative(snap.connectedSince, now).removeSuffix(" ago")}"
                            else "Last seen ${formatRelative(snap?.lastSeen, now)}",
                            color = hsm.muted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (net?.online) {
                            true -> Pill("Internet OK", Tone.OK, dot = true)
                            false -> Pill("Internet down", Tone.DANGER, dot = true)
                            null -> Pill("Internet ?", Tone.NEUTRAL, dot = true)
                        }
                        val clients = tether?.clients?.size
                        val tetherOn = tether?.usbTetherOn == true || tether?.hotspotOn == true
                        Pill(
                            if (tetherOn) "Sharing" + (clients?.let { " · $it device${if (it == 1) "" else "s"}" } ?: "") else "Not sharing",
                            if (tetherOn) Tone.INFO else Tone.NEUTRAL,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    listOfNotNull(
                        radio?.let { formatNetType(it.netType.name) + (it.band?.let { b -> " · B$b" } ?: "") },
                        radio?.operator,
                        snap?.outbox?.takeIf { it > 0 }?.let { "$it waiting to upload" },
                    ).joinToString(" · ").ifEmpty { DASH },
                    color = hsm.muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Signal + battery gauges
        item {
            EqualRow {
                SectionCard(Modifier.weight(1f), title = "Signal", stale = fastStale, onClick = { navigate(Dest.SIGNAL, null) }) {
                    val score = radio?.score
                    val grade = score?.let { gradeScore(it) }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RingGauge(score?.div(100), hsm.grade(grade)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(score?.roundToInt()?.toString() ?: DASH, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = hsm.grade(grade))
                                Text("of 100", style = MaterialTheme.typography.labelSmall, color = hsm.muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        GradeChip(grade)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${radio?.rsrp ?: DASH} dBm · SINR ${radio?.sinr?.let { trimNumber(it, 1) } ?: DASH}",
                            style = MaterialTheme.typography.bodySmall, color = hsm.muted,
                        )
                    }
                }
                SectionCard(Modifier.weight(1f), title = "Battery", stale = slowStale, onClick = { navigate(Dest.POWER, null) }) {
                    val pct = power?.percent
                    val low = pct != null && pct <= settings.alerts.lowBatteryPct
                    val color = when {
                        pct == null -> hsm.muted
                        low -> hsm.danger
                        power.status == BatteryStatus.CHARGING -> hsm.ok
                        else -> hsm.accent
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RingGauge(pct?.div(100.0), color) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(formatPercent(pct), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                                Text(power?.status?.let { titleCase(it.name.lowercase()) } ?: DASH, style = MaterialTheme.typography.labelSmall, color = hsm.muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(formatCurrent(power?.currentMa), style = MaterialTheme.typography.titleMedium, color = if ((power?.currentMa ?: 0) < 0) hsm.warn else hsm.fg)
                        Text(
                            when {
                                power?.chargingEnabled == false -> "Paused (${power.stopReason?.name?.lowercase() ?: "manual"})"
                                power?.chargeLimitPct != null -> "Limit ${power.chargeLimitPct}%"
                                else -> "plug ${power?.plug?.name?.lowercase() ?: DASH}"
                            },
                            style = MaterialTheme.typography.bodySmall, color = hsm.muted,
                        )
                    }
                }
            }
        }

        // Speed
        item {
            SectionCard(title = "Speed now", stale = fastStale, action = { LinkButton("Network") { navigate(Dest.NETWORK, null) } }) {
                EqualRow {
                    Stat("Download", formatBps(net?.rxBps), Modifier.weight(1f), size = 26.sp)
                    Stat("Upload", formatBps(net?.txBps), Modifier.weight(1f), size = 26.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ping ${net?.pingMs?.let { "${it.roundToInt()} ms" } ?: DASH} · loss ${formatPercent(net?.pingLossPct, 1)} · jitter ${net?.jitterMs?.let { "${trimNumber(it, 1)} ms" } ?: DASH}",
                    style = MaterialTheme.typography.bodyMedium, color = hsm.muted,
                )
                Spacer(Modifier.height(4.dp))
                UpdatedAt(snap?.stateTs?.get("net"))
            }
        }

        // Temperature
        item {
            val bt = heat?.batteryC
            val tone = when {
                bt == null -> hsm.fg
                bt >= settings.watchdog.tempStopChargeC -> hsm.danger
                bt >= settings.watchdog.tempWarnC -> hsm.warn
                else -> hsm.fg
            }
            SectionCard(title = "Temperature", stale = slowStale, action = { LinkButton("Power and heat") { navigate(Dest.POWER, null) } }) {
                EqualRow {
                    Stat("Battery", formatTemp(bt), Modifier.weight(1f), valueColor = tone)
                    Stat("CPU", formatTemp(heat?.cpuC), Modifier.weight(1f))
                    Stat("Modem", formatTemp(heat?.modemC), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Thermal status: ${formatThermalStatus(heat?.thermalStatus)}", style = MaterialTheme.typography.bodySmall, color = hsm.muted, modifier = Modifier.weight(1f))
                    if (heat?.radioResting == true) Pill("Radio resting (heat)", Tone.DANGER)
                }
            }
        }

        // Data
        item {
            val quota = if (settings.plan.quotaGb > 0) settings.plan.quotaGb * 1e9 else 0.0
            val cycle = if (traffic?.cycleRx != null || traffic?.cycleTx != null) (traffic.cycleRx ?: 0) + (traffic.cycleTx ?: 0) else null
            val today = if (traffic?.todayRx != null || traffic?.todayTx != null) (traffic.todayRx ?: 0) + (traffic.todayTx ?: 0) else null
            val pct = if (quota > 0 && cycle != null) cycle / quota * 100 else null
            SectionCard(title = "Data this cycle", stale = isStale(snap?.stateTs?.get("traffic"), afterMs = 20 * 60_000)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatBytes(cycle), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = hsm.fg)
                    Spacer(Modifier.width(8.dp))
                    Text(if (quota > 0) "of ${formatBytes(quota)}" else "no quota set", color = hsm.muted, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (pct != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(pct, if (pct >= 100) Tone.DANGER else if (pct >= 80) Tone.WARN else Tone.INFO)
                    Text("${trimNumber(pct, 1)}% used", style = MaterialTheme.typography.bodySmall, color = hsm.muted, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Today ${formatBytes(today)}" + (traffic?.cycleStart?.let { " · cycle started ${formatDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = hsm.muted,
                )
            }
        }

        // Uptime
        item {
            SectionCard(title = "Uptime", action = { LinkButton("System") { navigate(Dest.SYSTEM, null) } }) {
                EqualRow {
                    Stat("Phone", formatDuration(system?.uptimeS), Modifier.weight(1f), size = 20.sp)
                    Stat("Agent", formatDuration(system?.agentUptimeS), Modifier.weight(1f), size = 20.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Boot #${system?.bootCount ?: DASH} · ${system?.bootReason ?: "reason unknown"}", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
            }
        }

        // Events
        item {
            val events = data.events.filter { it.deviceId == deviceId }.take(8)
            SectionCard(title = "Latest events", action = { LinkButton("All events") { navigate(Dest.SYSTEM, "events") } }) {
                EventList(events)
            }
        }
    }
}
