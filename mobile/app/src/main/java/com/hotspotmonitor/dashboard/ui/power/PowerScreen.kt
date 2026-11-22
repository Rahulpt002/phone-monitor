package com.hotspotmonitor.dashboard.ui.power

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.BatteryHealth
import com.hotspotmonitor.agent.protocol.BatteryStatus
import com.hotspotmonitor.agent.protocol.HeatInfo
import com.hotspotmonitor.agent.protocol.PlugType
import com.hotspotmonitor.agent.protocol.PowerInfo
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.StopReason
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.formatCurrent
import com.hotspotmonitor.dashboard.data.formatDuration
import com.hotspotmonitor.dashboard.data.formatPercent
import com.hotspotmonitor.dashboard.data.formatTemp
import com.hotspotmonitor.dashboard.data.formatThermalStatus
import com.hotspotmonitor.dashboard.data.formatVoltage
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.components.Guide
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.RingGauge
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.UpdatedAt
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.signal.Caption
import com.hotspotmonitor.dashboard.ui.signal.ChartLine
import com.hotspotmonitor.dashboard.ui.signal.FactTile
import com.hotspotmonitor.dashboard.ui.signal.Gap
import com.hotspotmonitor.dashboard.ui.signal.HistoryHeader
import com.hotspotmonitor.dashboard.ui.signal.HistoryRange
import com.hotspotmonitor.dashboard.ui.signal.HistoryState
import com.hotspotmonitor.dashboard.ui.signal.MetricChartCard
import com.hotspotmonitor.dashboard.ui.signal.StaleLine
import com.hotspotmonitor.dashboard.ui.signal.Zone
import com.hotspotmonitor.dashboard.ui.signal.ZoneScale
import com.hotspotmonitor.dashboard.ui.signal.rememberHistory
import com.hotspotmonitor.dashboard.ui.signal.rememberHistoryRange
import com.hotspotmonitor.dashboard.ui.signal.seriesColor
import com.hotspotmonitor.dashboard.ui.signal.staleHint
import com.hotspotmonitor.dashboard.ui.signal.trailingDuration
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val POWER_METRICS = listOf(
    "power.percent", "power.current_ma", "power.voltage_mv", "heat.battery_c", "heat.cpu_c", "heat.modem_c", "heat.skin_c",
)

private val fmtPct: (Double) -> String = { "${trimNumber(it, 0)}%" }
private val fmtMa: (Double) -> String = { "${trimNumber(it, 0)} mA" }
private val fmtV: (Double) -> String = { "${trimNumber(it, 2)} V" }
private val fmtC: (Double) -> String = { "${trimNumber(it, 1)} °C" }
private val mvToV: (Double) -> Double = { it / 1000.0 }

private fun stopReasonText(r: StopReason?): String = when (r) {
    StopReason.LIMIT -> "charge limit reached"
    StopReason.HEAT -> "battery too hot"
    StopReason.MANUAL -> "stopped manually"
    null -> "unknown"
}

private fun enumLabel(e: Enum<*>?): String = e?.let { titleCase(it.name.lowercase()) } ?: DASH

/**
 * Plugged in, battery current negative, and charging is supposed to be happening (web
 * isPluggedButDraining). A pause the Agent made on purpose (limit, heat, manual) is not a weak supply.
 */
fun isPluggedButDraining(p: PowerInfo?): Boolean {
    if (p == null) return false
    val plug = p.plug
    val cur = p.currentMa
    if (plug == null || plug == PlugType.NONE || cur == null || cur >= 0) return false
    if (p.chargingEnabled == false || p.stopReason != null) return false
    return p.status != BatteryStatus.FULL
}

@Composable
fun PowerScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val snap = deviceId?.let { data.snapshots[it] }
    val settings = deviceId?.let { data.settings[it]?.typed } ?: Settings.DEFAULT
    val grp = remember(snap?.state) { snap?.groups }
    val power = grp?.power
    val heat = grp?.heat
    val powerStale = staleHint(snap, settings, "power")
    val heatStale = staleHint(snap, settings, "heat")
    val (range, setRange) = rememberHistoryRange()
    val history = rememberHistory(POWER_METRICS, range)
    val draining = isPluggedButDraining(power)
    // How long it has been draining comes from 6 h of current history (web DrainingNotice).
    val drainHistory = if (draining) rememberHistory(listOf("power.current_ma"), HistoryRange.H6) else null

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val w = settings.watchdog
    val limit = power?.chargeLimitPct ?: w.chargeLimitPct
    val accent = hsm.accent
    val muted = hsm.muted
    val warn = hsm.warn
    val danger = hsm.danger
    val tempLines = listOf(Guide(w.tempWarnC, warn), Guide(w.tempStopChargeC, danger))

    Page(
        title = "Power and heat",
        subtitle = "Battery health is the biggest safety risk in a hot outdoor box",
        refreshing = loading,
        onRefresh = {
            g.repo.reload()
            history.reload()
        },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        if (draining && power != null && drainHistory != null) item(key = "drain") { DrainingNotice(power, settings, drainHistory) }
        if (heat?.radioResting == true) {
            item(key = "rest") {
                Notice(Tone.DANGER, "Radio resting to cool down", "The battery reached ${trimNumber(w.tempRadioRestC, 1)} °C, so the phone paused its radio for a while.")
            }
        }

        item(key = "battery") { BatteryCard(power, settings, limit, powerStale, snap?.stateTs?.get("power")) }
        item(key = "charge") { ChargeControlCard(power, settings, hasPower = snap?.state?.get("power") != null, stale = powerStale != null) }
        if (session.canControl) {
            item(key = "ctl-limit") { ChargeLimitControl(limit) }
            item(key = "ctl-charging") { ChargingControl(power?.chargingEnabled) }
        }
        item(key = "temps") { TemperaturesCard(heat, settings, heatStale, snap?.stateTs?.get("heat")) }
        val zones = heat?.zones.orEmpty()
        if (zones.isNotEmpty()) item(key = "zones") { ZonesCard(zones, heatStale != null) }

        item(key = "history") { HistoryHeader(range, setRange) }
        item(key = "c-pct") {
            MetricChartCard(
                "Battery (%)", listOf(ChartLine("power.percent", "Battery", seriesColor(0))), history, range, fmtPct,
                yMin = 0.0, yMax = 100.0, guides = listOf(Guide(limit.toDouble(), accent)),
                note = "Dashed line: charge limit $limit%.",
            )
        }
        item(key = "c-cur") {
            MetricChartCard(
                "Net battery current (mA)", listOf(ChartLine("power.current_ma", "Current", seriesColor(0))), history, range, fmtMa,
                guides = listOf(Guide(0.0, muted)),
                note = "Above zero charges the battery, below zero drains it.",
            )
        }
        item(key = "c-volt") {
            MetricChartCard(
                "Battery voltage (V)", listOf(ChartLine("power.voltage_mv", "Voltage", seriesColor(0))), history, range, fmtV,
                transform = mvToV,
            )
        }
        item(key = "c-temp") {
            MetricChartCard(
                "Temperatures (°C)",
                listOf(
                    ChartLine("heat.battery_c", "Battery", seriesColor(0)),
                    ChartLine("heat.cpu_c", "CPU", seriesColor(1)),
                    ChartLine("heat.modem_c", "Modem", seriesColor(2)),
                    ChartLine("heat.skin_c", "Skin", seriesColor(3)),
                ),
                history, range, fmtC, guides = tempLines,
                note = "Dashed lines: warning ${trimNumber(w.tempWarnC, 0)} °C, stop charging ${trimNumber(w.tempStopChargeC, 0)} °C.",
            )
        }
    }
}

@Composable
private fun DrainingNotice(power: PowerInfo, settings: Settings, history: HistoryState) {
    val forMs = trailingDuration(history.series["power.current_ma"].orEmpty()) { it < 0 }
    val weak = forMs / 60_000 >= settings.watchdog.weakPowerMin
    Notice(
        if (weak) Tone.DANGER else Tone.WARN,
        if (weak) "Power supply too weak" else "Plugged in but discharging",
        "The battery is draining at ${formatCurrent(power.currentMa)} while on ${power.plug?.name ?: "external"} power" +
            (if (forMs > 60_000) " for ${formatDuration(forMs / 1000)}" else "") +
            ". Use a stronger USB port, a shorter or thicker cable, or a powered hub.",
    )
}

// --- battery -----------------------------------------------------------------------------------

/** Tick on a [RingGauge] at [percent] (same 270° arc geometry). */
@Composable
private fun RingTick(percent: Int, size: Dp, stroke: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val s = stroke.toPx()
        val r = (this.size.minDimension - s) / 2
        val a = Math.toRadians(135.0 + 270.0 * percent.coerceIn(0, 100) / 100.0)
        val c = center
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val inner = r - s * 0.9f
        val outer = r + s * 0.9f
        drawLine(color, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun BatteryCard(power: PowerInfo?, settings: Settings, limit: Int, stale: String?, ts: String?) {
    val pct = power?.percent
    val low = pct != null && pct <= settings.alerts.lowBatteryPct
    val color = when {
        pct == null -> hsm.muted
        low -> hsm.danger
        power.status == BatteryStatus.CHARGING -> hsm.ok
        else -> hsm.accent
    }
    val cur = power?.currentMa
    val ringSize = 132.dp
    val ringStroke = 12.dp
    SectionCard(title = "Battery", stale = stale != null, action = { UpdatedAt(ts) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics { contentDescription = "Battery ${formatPercent(pct)}, charge limit $limit percent" }) {
                RingGauge(pct?.div(100.0), color, size = ringSize, stroke = ringStroke) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatPercent(pct), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(enumLabel(power?.status), style = MaterialTheme.typography.labelSmall, color = hsm.muted)
                    }
                }
                RingTick(limit, ringSize, ringStroke, hsm.fg)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Caption("Net current")
                Text(
                    formatCurrent(cur),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        cur == null -> hsm.fg
                        cur < 0 -> hsm.warn
                        else -> hsm.ok
                    },
                )
                Text(
                    when {
                        cur == null -> "no reading"
                        cur < 0 -> "draining"
                        cur > 0 -> "charging"
                        else -> "idle"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
                Spacer(Modifier.height(8.dp))
                Text("Tick on the ring: limit $limit%", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                if (low) Text("Low battery (alert at ${settings.alerts.lowBatteryPct}%)", style = MaterialTheme.typography.bodySmall, color = hsm.danger)
            }
        }
        Gap(14.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactTile("Voltage", formatVoltage(power?.voltageMv), Modifier.weight(1f))
            FactTile("Plug", power?.plug?.let { if (it == PlugType.NONE) "Nothing" else it.name.let { n -> if (n.length <= 3) n else titleCase(n.lowercase()) } } ?: DASH, Modifier.weight(1f))
            FactTile(
                "Health",
                enumLabel(power?.health).replace('_', ' '),
                Modifier.weight(1f),
                valueColor = when (power?.health) {
                    null, BatteryHealth.UNKNOWN -> Color.Unspecified
                    BatteryHealth.GOOD -> hsm.ok
                    else -> hsm.danger
                },
            )
        }
        StaleLine(stale)
    }
}

@Composable
private fun ChargeControlCard(power: PowerInfo?, settings: Settings, hasPower: Boolean, stale: Boolean) {
    val w = settings.watchdog
    SectionCard(title = "Charge control", stale = stale) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (power?.chargingEnabled) {
                true -> Pill("Charging allowed", Tone.OK, dot = true)
                false -> Pill("Charging stopped", Tone.WARN, dot = true)
                null -> Pill("State unknown", Tone.NEUTRAL)
            }
            if (power?.chargingEnabled == false) {
                Spacer(Modifier.width(8.dp))
                Text(stopReasonText(power.stopReason), style = MaterialTheme.typography.bodyMedium, color = hsm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Gap(12.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactTile("Limit now", power?.chargeLimitPct?.let { "$it%" } ?: DASH, Modifier.weight(1f))
            FactTile("Resumes at", "${w.chargeLimitPct - w.chargeResumeDropPct}%", Modifier.weight(1f))
            FactTile("Stops above", "${trimNumber(w.tempStopChargeC, 1)} °C", Modifier.weight(1f))
        }
        Gap(12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyValueRow("Why stopped", if (power?.chargingEnabled == false) stopReasonText(power.stopReason) else DASH)
            KeyValueRow("Control", "", trailing = {
                Text(
                    power?.chargeControl ?: "none found",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (power?.chargeControl == null) hsm.warn else hsm.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            })
        }
        if (power?.chargeControl == null && hasPower) {
            Text(
                "No charge-control file found for this phone. Add one to its device profile.",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.warn,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

// --- heat --------------------------------------------------------------------------------------

@Composable
private fun TemperaturesCard(heat: HeatInfo?, settings: Settings, stale: String?, ts: String?) {
    val w = settings.watchdog
    val bt = heat?.batteryC
    val btColor = when {
        bt == null -> Color.Unspecified
        bt >= w.tempStopChargeC -> hsm.danger
        bt >= w.tempWarnC -> hsm.warn
        else -> Color.Unspecified
    }
    val status = heat?.thermalStatus ?: 0
    SectionCard(title = "Temperatures", stale = stale != null, action = { UpdatedAt(ts) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactTile("Battery", formatTemp(bt), Modifier.weight(1f), valueColor = btColor)
                FactTile("CPU", formatTemp(heat?.cpuC), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactTile("Modem", formatTemp(heat?.modemC), Modifier.weight(1f))
                FactTile("Skin", formatTemp(heat?.skinC), Modifier.weight(1f))
            }
        }
        Gap(14.dp)
        Caption("Battery vs limits")
        val top = w.tempRadioRestC + 5
        ZoneScale(
            bt,
            min = 15.0,
            max = top,
            zones = listOf(
                Zone(15.0, w.tempWarnC, hsm.ok),
                Zone(w.tempWarnC, w.tempStopChargeC, hsm.warn),
                Zone(w.tempStopChargeC, w.tempRadioRestC, hsm.danger),
                Zone(w.tempRadioRestC, top, hsm.danger),
            ),
            ticks = listOf(w.tempWarnC, w.tempStopChargeC, w.tempRadioRestC),
            tickLabel = { "${trimNumber(it, 0)}°" },
        )
        Gap(10.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thermal status ", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
            Text(
                formatThermalStatus(heat?.thermalStatus),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    status >= 3 -> hsm.danger
                    status >= 2 -> hsm.warn
                    else -> hsm.fg
                },
                modifier = Modifier.weight(1f),
            )
            if (heat?.radioResting == true) Pill("Radio resting", Tone.DANGER)
        }
        Text(
            "Warning at ${trimNumber(w.tempWarnC, 1)} °C, stop charging at ${trimNumber(w.tempStopChargeC, 1)} °C, rest radio at ${trimNumber(w.tempRadioRestC, 1)} °C.",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        StaleLine(stale)
    }
}

@Composable
private fun ZonesCard(zones: Map<String, Double>, stale: Boolean) {
    var showAll by remember { mutableStateOf(false) }
    val sorted = zones.entries.sortedByDescending { it.value }
    val hottest = sorted.first().value
    val scaleMax = maxOf(60.0, hottest)
    SectionCard(
        title = "Heat zones",
        stale = stale,
        action = if (sorted.size > 6) ({ LinkButton(if (showAll) "Show fewer" else "All ${sorted.size}") { showAll = !showAll } }) else null,
    ) {
        val list = if (showAll) sorted else sorted.take(6)
        Column {
            list.forEachIndexed { i, (name, c) ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = hsm.fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatTemp(c), style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp), color = hsm.fg)
                    }
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(min(100.0, c / scaleMax * 100), color = hsm.info, modifier = Modifier.height(4.dp))
                }
            }
        }
        Text("Raw thermal zones reported by the kernel, hottest first.", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
    }
}
