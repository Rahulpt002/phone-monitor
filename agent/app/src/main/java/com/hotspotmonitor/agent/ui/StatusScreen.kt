package com.hotspotmonitor.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.AgentStartReason
import com.hotspotmonitor.agent.core.ConnPhase
import com.hotspotmonitor.agent.protocol.AgentEvent
import com.hotspotmonitor.agent.protocol.BatteryStatus
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.agent.service.AgentService
import com.hotspotmonitor.agent.service.ServiceStatusText
import kotlinx.coroutines.launch

/** Service, link, outbox and live values at a glance (plan 6.1 "Local UI"). */
@Composable
fun StatusScreen(graph: AgentGraph, onOpenSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val now = rememberNowMs()

    val running by graph.running.collectAsStateWithLifecycle()
    val info by graph.pairingState.info.collectAsStateWithLifecycle()
    val conn by graph.sync.state.collectAsStateWithLifecycle()
    val rates by graph.sync.rates.collectAsStateWithLifecycle()
    val pending by graph.outbox.pendingCount.collectAsStateWithLifecycle()
    val radio by graph.telemetry.radio.collectAsStateWithLifecycle()
    val net by graph.telemetry.net.collectAsStateWithLifecycle()
    val power by graph.telemetry.power.collectAsStateWithLifecycle()
    val heat by graph.telemetry.heat.collectAsStateWithLifecycle()
    val tether by graph.telemetry.tether.collectAsStateWithLifecycle()
    val charge by graph.charge.state.collectAsStateWithLifecycle()
    val watchdog by graph.watchdog.state.collectAsStateWithLifecycle()
    val capabilities by graph.commands.capabilityMap.collectAsStateWithLifecycle()
    val profile by graph.profiles.profile.collectAsStateWithLifecycle()
    val events by produceState(initialValue = emptyList<AgentEvent>()) {
        graph.events.recent.collect { event -> value = (value + event).takeLast(RECENT_EVENTS) }
    }

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permissions = remember(refresh) { PermissionReport.read(context, graph) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Hotspot Agent ${graph.agentVersion}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusPill(if (running) "Running" else "Stopped", if (running) colors.good else colors.bad)
        }

        if (!permissions.allGood) {
            SectionCard("Setup incomplete") {
                Text(
                    "${permissions.openItems} permission item(s) missing. The Agent may stop in the background or miss cell data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warn,
                )
                Button(onClick = onOpenSetup) { Text("Fix permissions") }
            }
        }

        SectionCard("Server link") {
            val phaseColor = when (conn.phase) {
                ConnPhase.CONNECTED -> colors.good
                ConnPhase.CONNECTING, ConnPhase.WAITING -> colors.warn
                else -> colors.bad
            }
            val phaseLabel = if (conn.phase == ConnPhase.STOPPED && !running) "Stopped" else ServiceStatusText.linkLabel(conn.phase)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusPill(phaseLabel, phaseColor)
                if (conn.sinceMs > 0) Text("since " + formatAgo(conn.sinceMs, now).removeSuffix(" ago"), style = MaterialTheme.typography.bodyMedium)
            }
            conn.lastError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.warn) }
            val pairing = info
            InfoRow("Server", pairing?.serverUrl ?: "not paired")
            InfoRow("Device id", pairing?.deviceId ?: "-", monospace = true)
            InfoRow("Outbox", if (pending == 0) "empty" else "$pending waiting", if (pending > OUTBOX_WARN) colors.warn else Color.Unspecified)
            InfoRow(
                "Clock offset",
                if (graph.clock.synced) formatSignedSeconds(graph.clock.serverOffsetMs) else "not synced yet",
            )
            InfoRow("Fast telemetry", "every ${rates.fastS} s" + if (rates.mirror) " · mirror on" else "")
            InfoRow("Device profile", profile.id)
        }

        SectionCard("Actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    OutlinedButton(
                        onClick = {
                            graph.prefs.putBoolean(AgentService.KEY_USER_STOPPED, true)
                            AgentService.stop(context)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop service") }
                } else {
                    Button(
                        onClick = {
                            graph.prefs.putBoolean(AgentService.KEY_USER_STOPPED, false)
                            AgentService.start(context, AgentStartReason.MANUAL)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Start service") }
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        graph.appScope.launch {
                            try {
                                graph.sync.publishTelemetry(setOf(TelemetryTier.STATIC, TelemetryTier.SLOW, TelemetryTier.FAST))
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh now") }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    graph.appScope.launch {
                        try {
                            val map = graph.commands.probeCapabilities()
                            graph.sync.publishTelemetry(setOf(TelemetryTier.STATIC))
                            graph.log.i(TAG, "Capabilities re-tested from the local UI: ${map.count { it.value.supported }}/${map.size} supported")
                        } catch (e: Exception) {
                            graph.log.w(TAG, "Capability probe from the local UI failed", e)
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Re-test capabilities") }
            if (busy) HintText("Working…")
            if (!running) HintText("The Magisk keep-alive module, if installed, restarts a stopped service within a few minutes.")
        }

        SectionCard("Signal and internet") {
            val r = radio
            val score = r?.score
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    formatNumber(score),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = score?.let { gradeColor(gradeScore(it)) } ?: Color.Unspecified,
                )
                Column {
                    Text("Signal score", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(r?.netType?.name, r?.band?.let { "band $it" }, r?.operator).joinToString(" · ").ifEmpty { "no reading yet" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            InfoRow("RSRP / SINR / RSRQ", "${r?.rsrp ?: "-"} dBm / ${formatNumber(r?.sinr)} dB / ${r?.rsrq ?: "-"} dB")
            InfoRow("Cell", listOfNotNull(r?.pci?.let { "PCI $it" }, r?.cellId?.let { "ID $it" }).joinToString(" · ").ifEmpty { "-" })
            val n = net
            InfoRow(
                "Internet",
                when (n?.online) {
                    true -> "online" + (n?.pingMs?.let { " · ping ${formatNumber(it, 0)} ms" } ?: "")
                    false -> "OFFLINE"
                    null -> "-"
                },
                when (n?.online) {
                    true -> colors.good
                    false -> colors.bad
                    null -> Color.Unspecified
                },
            )
            InfoRow("Mobile data", yesNo(r?.dataEnabled, "on", "off"))
        }

        SectionCard("Power and heat") {
            val p = power
            InfoRow(
                "Battery",
                (p?.percent?.let { "$it%" } ?: "-") + when (p?.status) {
                    BatteryStatus.CHARGING -> " · charging"
                    BatteryStatus.DISCHARGING -> " · discharging"
                    BatteryStatus.FULL -> " · full"
                    BatteryStatus.NOT_CHARGING -> " · not charging"
                    else -> ""
                },
            )
            InfoRow("Current", p?.currentMa?.let { (if (it > 0) "+" else "") + "$it mA" } ?: "-")
            InfoRow("Battery temperature", formatNumber(heat?.batteryC, 1, " °C"), tempColor(heat?.batteryC))
            InfoRow(
                "Charging control",
                when (charge.chargingEnabled) {
                    true -> "charging allowed"
                    false -> "stopped" + (charge.stopReason?.let { " (${it.name.lowercase()})" } ?: "")
                    null -> "not available"
                } + " · limit ${charge.limitPct}%",
            )
        }

        SectionCard("Tethering") {
            val t = tether
            InfoRow(
                "USB tethering",
                yesNo(t?.usbTetherOn, "on", "off") + (t?.tetherIf?.let { " ($it)" } ?: ""),
                if (t?.usbTetherOn == true) colors.good else if (t?.usbConnected == true) colors.warn else Color.Unspecified,
            )
            InfoRow("USB cable", yesNo(t?.usbConnected, "connected", "not connected"))
            InfoRow("Wi-Fi hotspot", yesNo(t?.hotspotOn, "on", "off"))
            InfoRow("Clients", t?.clients?.size?.toString() ?: "-")
        }

        SectionCard("Watchdog") {
            val w = watchdog
            InfoRow("Enabled", yesNo(w.enabled), if (w.enabled) Color.Unspecified else colors.warn)
            InfoRow(
                "Internet down since",
                w.internetDownSinceMs?.let { formatAgo(it - graph.clock.serverOffsetMs, now) } ?: "-",
                if (w.internetDownSinceMs != null) colors.bad else Color.Unspecified,
            )
            InfoRow("Recovery step", if (w.ladderStep == 0) "none" else "${w.ladderStep}" + if (w.stepRunning) " (running)" else "")
            InfoRow("Last action", w.lastAction?.let { action -> action + (w.lastActionAtMs?.let { " · " + formatAgo(it - graph.clock.serverOffsetMs, now) } ?: "") } ?: "-")
            if (w.radioResting) Text("Radio resting because of heat", color = colors.warn)
            if (w.heatStop) Text("Charging stopped because of heat", color = colors.warn)
            if (w.simulatedUntilMs != null) Text("Internet loss simulation running", color = colors.warn)
        }

        SectionCard("Capabilities (${capabilities.count { it.value.supported }}/${capabilities.size})") {
            if (capabilities.isEmpty()) HintText("Not probed yet.")
            capabilities.entries.sortedBy { it.key }.forEach { (name, entry) ->
                InfoRow(
                    name,
                    if (entry.supported) (entry.method ?: "yes") else ("no" + (entry.note?.let { ": $it" } ?: "")),
                    if (entry.supported) colors.good else colors.muted,
                )
            }
        }

        SectionCard("Recent events") {
            if (events.isEmpty()) HintText("No events since the app started.")
            events.asReversed().forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider()
                Text(
                    "${event.ts.substringAfter('T').take(8)}  ${event.kind.wire}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (event.severity) {
                        Severity.INFO -> colors.muted
                        Severity.WARNING -> colors.warn
                        Severity.CRITICAL -> colors.bad
                    },
                )
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
            }
        }

        TextButton(onClick = onOpenSetup) { Text("Permissions and setup") }
    }
}

@Composable
private fun gradeColor(grade: Grade): Color {
    val colors = LocalStatusColors.current
    return when (grade) {
        Grade.EXCELLENT, Grade.GOOD -> colors.good
        Grade.FAIR -> colors.warn
        Grade.POOR -> colors.bad
    }
}

@Composable
private fun tempColor(celsius: Double?): Color {
    val colors = LocalStatusColors.current
    return when {
        celsius == null -> Color.Unspecified
        celsius >= 45 -> colors.bad
        celsius >= 40 -> colors.warn
        else -> Color.Unspecified
    }
}

private fun formatSignedSeconds(ms: Long): String =
    (if (ms >= 0) "+" else "-") + formatNumber(kotlin.math.abs(ms) / 1000.0, 1, " s")

private const val TAG = "ui"
private const val RECENT_EVENTS = 20
private const val OUTBOX_WARN = 1_000
