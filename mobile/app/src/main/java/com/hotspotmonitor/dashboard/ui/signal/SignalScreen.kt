package com.hotspotmonitor.dashboard.ui.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.agent.protocol.NeighbourCell
import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.gradeRsrp
import com.hotspotmonitor.agent.protocol.gradeRsrq
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.agent.protocol.gradeSinr
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.StoredEvent
import com.hotspotmonitor.dashboard.data.fmtNum
import com.hotspotmonitor.dashboard.data.formatDateTime
import com.hotspotmonitor.dashboard.data.formatNetType
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.formatTaDistance
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.GradeChip
import com.hotspotmonitor.dashboard.ui.components.Guide
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Loading
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.RingGauge
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.UpdatedAt
import com.hotspotmonitor.dashboard.ui.components.gradeLabel
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

// Plan 7.1 thresholds (web lib/grades.ts THRESHOLD_TABLE), boundaries best to worst.
val RSRP_LINES = listOf(-80.0, -90.0, -100.0)
val SINR_LINES = listOf(20.0, 13.0, 0.0)
val RSRQ_LINES = listOf(-10.0, -15.0, -20.0)
val SCORE_LINES = listOf(75.0, 55.0, 35.0)

private data class Threshold(val label: String, val unit: String, val meaning: String, val bands: List<String>)

private val THRESHOLDS = listOf(
    Threshold("RSRP", "dBm", "Signal strength", listOf("above -80", "-80 to -90", "-90 to -100", "below -100")),
    Threshold("SINR", "dB", "How clean the signal is. Best predictor of speed.", listOf("above 20", "13 to 20", "0 to 13", "below 0")),
    Threshold("RSRQ", "dB", "Quality including load and interference", listOf("above -10", "-10 to -15", "-15 to -20", "below -20")),
)

private val SIGNAL_METRICS = listOf("radio.score", "radio.rsrp", "radio.nr.ss_rsrp", "radio.sinr", "radio.nr.ss_sinr", "radio.rsrq")

private val fmtScore: (Double) -> String = { trimNumber(it, 0) }
private val fmtDbm: (Double) -> String = { "${trimNumber(it, 0)} dBm" }
private val fmtDb: (Double) -> String = { "${trimNumber(it, 1)} dB" }

private fun bandLabel(netType: NetType?, band: Int?): String? =
    band?.let { if (netType == NetType.NR_SA) "n$it" else "B$it" }

@Composable
fun SignalScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val snap = deviceId?.let { data.snapshots[it] }
    val settings = deviceId?.let { data.settings[it]?.typed } ?: Settings.DEFAULT
    val radio = remember(snap?.state) { snap?.groups?.radio }
    val baseline = remember(snap?.baseline) { snap?.baselineSpot }
    val stale = staleHint(snap, settings, "radio")
    val (range, setRange) = rememberHistoryRange()
    val history = rememberHistory(SIGNAL_METRICS, range)
    val cells = rememberCellChanges(deviceId, data.events)

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val accent = hsm.accent
    val scoreGuides = gradeGuides(SCORE_LINES) + listOfNotNull(baseline?.metrics?.score?.avg?.let { Guide(it, accent) })
    val rsrpGuides = gradeGuides(RSRP_LINES)
    val sinrGuides = gradeGuides(SINR_LINES)
    val rsrqGuides = gradeGuides(RSRQ_LINES)
    val c0 = seriesColor(0)
    val c1 = seriesColor(1)

    Page(
        title = "Signal",
        subtitle = "Live radio readings, history and the installed baseline",
        refreshing = loading,
        onRefresh = {
            g.repo.reload()
            history.reload()
            cells.reload()
        },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }

        item(key = "hero") { LiveHero(radio, stale, snap?.stateTs?.get("radio")) }
        item(key = "meters") { QualityCard(radio, stale != null) }
        if (radio?.nr != null) item(key = "nr") { NrLegCard(radio) }
        item(key = "serving") { ServingCellCard(radio, stale != null) }
        item(key = "baseline") { BaselineCard(baseline, radio, settings) }

        item(key = "history") { HistoryHeader(range, setRange) }
        item(key = "c-score") {
            MetricChartCard(
                "Signal score", listOf(ChartLine("radio.score", "Score", c0)), history, range, fmtScore,
                yMin = 0.0, yMax = 100.0, guides = scoreGuides,
                note = if (baseline != null) "The blue dashed line is the installed baseline (${trimNumber(baseline.metrics.score.avg, 0)})." else null,
            )
        }
        item(key = "c-rsrp") {
            MetricChartCard(
                "RSRP (dBm)",
                listOf(ChartLine("radio.rsrp", "LTE / serving", c0), ChartLine("radio.nr.ss_rsrp", "5G SS-RSRP", c1)),
                history, range, fmtDbm, guides = rsrpGuides,
            )
        }
        item(key = "c-sinr") {
            MetricChartCard(
                "SINR (dB)",
                listOf(ChartLine("radio.sinr", "LTE / serving", c0), ChartLine("radio.nr.ss_sinr", "5G SS-SINR", c1)),
                history, range, fmtDb, guides = sinrGuides,
            )
        }
        item(key = "c-rsrq") {
            MetricChartCard("RSRQ (dB)", listOf(ChartLine("radio.rsrq", "RSRQ", c0)), history, range, fmtDb, guides = rsrqGuides)
        }

        item(key = "cells") { CellChangesCard(cells) }
        item(key = "neighbours") { NeighboursCard(radio?.neighbours.orEmpty(), stale != null) }
        item(key = "grades") { GradesCard(settings) }
    }
}

// --- live --------------------------------------------------------------------------------------

@Composable
private fun LiveHero(radio: RadioInfo?, stale: String?, ts: String?) {
    val score = radio?.score
    val grade = score?.let { gradeScore(it) }
    val color = hsm.grade(grade)
    SectionCard(title = "Live", stale = stale != null, action = { UpdatedAt(ts) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(score?.div(100), color, size = 136.dp, stroke = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(score?.roundToInt()?.toString() ?: DASH, fontSize = 42.sp, fontWeight = FontWeight.Bold, color = color)
                    Text("score", style = MaterialTheme.typography.labelSmall, color = hsm.muted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GradeChip(grade)
                Text(
                    formatNetType(radio?.netType?.name),
                    style = MaterialTheme.typography.titleLarge,
                    color = hsm.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val cellLine = listOfNotNull(
                    bandLabel(radio?.netType, radio?.band)?.let { "Band $it" },
                    radio?.pci?.let { "PCI $it" },
                ).joinToString(" · ")
                if (cellLine.isNotEmpty()) Text(cellLine, style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalBars(radio?.signalLevel, color)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        radio?.operator ?: "Operator unknown",
                        style = MaterialTheme.typography.bodyMedium,
                        color = hsm.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (radio == null) {
            Text("No radio reading yet.", style = MaterialTheme.typography.bodyMedium, color = hsm.muted, modifier = Modifier.padding(top = 8.dp))
        } else if (radio.airplane == true || radio.dataEnabled == false) {
            Text(
                listOfNotNull(
                    if (radio.airplane == true) "Airplane mode is on" else null,
                    if (radio.dataEnabled == false) "Mobile data is off" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.danger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        StaleLine(stale)
    }
}

/** A graded reading with its value, grade chip and a zone meter. */
@Composable
private fun SignalMeter(
    label: String,
    meaning: String,
    value: Double?,
    unit: String,
    digits: Int,
    grade: Grade?,
    min: Double,
    max: Double,
    lines: List<Double>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                    Spacer(Modifier.width(8.dp))
                    GradeChip(grade)
                }
                Text(meaning, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
            }
            ValueUnit(value?.let { fmtNum(it, "", digits) } ?: DASH, unit, hsm.grade(grade))
        }
        Spacer(Modifier.height(6.dp))
        ZoneScale(value, min, max, gradeZones(min, max, lines), ticks = lines.reversed(), activeZone = grade?.let { 3 - it.ordinal })
    }
}

@Composable
private fun QualityCard(radio: RadioInfo?, stale: Boolean) {
    SectionCard(title = "Signal quality", stale = stale) {
        val rsrp = radio?.rsrp?.toDouble()
        val sinr = radio?.sinr
        val rsrq = radio?.rsrq?.toDouble()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SignalMeter("RSRP", "Strength", rsrp, "dBm", 0, rsrp?.let(::gradeRsrp), -120.0, -70.0, RSRP_LINES)
            SignalMeter("SINR", "Cleanliness, the best predictor of speed", sinr, "dB", 1, sinr?.let(::gradeSinr), -5.0, 25.0, SINR_LINES)
            SignalMeter("RSRQ", "Quality including load", rsrq, "dB", 0, rsrq?.let(::gradeRsrq), -24.0, -6.0, RSRQ_LINES)
        }
    }
}

@Composable
private fun NrLegCard(radio: RadioInfo) {
    val nr = radio.nr ?: return
    SectionCard(title = if (radio.netType == NetType.NR_SA) "5G standalone cell" else "5G leg (NR, non-standalone)") {
        val rsrp = nr.ssRsrp?.toDouble()
        val sinr = nr.ssSinr
        val rsrq = nr.ssRsrq?.toDouble()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SignalMeter("SS-RSRP", "5G strength", rsrp, "dBm", 0, rsrp?.let(::gradeRsrp), -120.0, -70.0, RSRP_LINES)
            SignalMeter("SS-SINR", "5G cleanliness", sinr, "dB", 1, sinr?.let(::gradeSinr), -5.0, 25.0, SINR_LINES)
            SignalMeter("SS-RSRQ", "5G quality", rsrq, "dB", 0, rsrq?.let(::gradeRsrq), -24.0, -6.0, RSRQ_LINES)
        }
        Gap(14.dp)
        FactGrid(
            listOf(
                "NR band" to (nr.band?.let { "n$it" } ?: DASH),
                "NR-ARFCN" to (nr.arfcn?.toString() ?: DASH),
                "PCI" to (nr.pci?.toString() ?: DASH),
            ),
        )
        Gap(8.dp)
        KeyValueRow("NCI", nr.nci?.toString() ?: DASH)
        if (radio.netType == NetType.NR_NSA) {
            Text(
                "The score uses the LTE anchor cell; the 5G leg carries the extra capacity.",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ServingCellCard(radio: RadioInfo?, stale: Boolean) {
    SectionCard(title = "Serving cell", stale = stale) {
        FactGrid(
            listOf(
                "Band" to (bandLabel(radio?.netType, radio?.band) ?: DASH),
                "PCI" to (radio?.pci?.toString() ?: DASH),
                "EARFCN" to (radio?.earfcn?.toString() ?: DASH),
                "Distance" to (radio?.ta?.let { formatTaDistance(it) } ?: DASH),
                "RSSI" to fmtNum(radio?.rssi, " dBm", 0),
                "CQI" to (radio?.cqi?.toString() ?: DASH),
            ),
        )
        Gap(14.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyValueRow("Network type", formatNetType(radio?.netType?.name))
            KeyValueRow("Operator", radio?.operator?.let { op -> op + (radio.mccMnc?.let { " ($it)" } ?: "") } ?: DASH)
            KeyValueRow(
                "Cell ID",
                radio?.cellId?.let { id -> if (radio.netType == NetType.NR_SA) "$id" else "$id (eNB ${id / 256})" } ?: DASH,
            )
            KeyValueRow("TAC", radio?.tac?.toString() ?: DASH)
            KeyValueRow("Timing advance", radio?.ta?.let { "$it (${formatTaDistance(it)})" } ?: DASH)
            KeyValueRow("Bars", radio?.signalLevel?.let { "$it / 4" } ?: DASH)
            KeyValueRow("SIM", radio?.simState?.let { titleCase(it.name.lowercase()) } ?: DASH)
            KeyValueRow("Roaming", "", trailing = { YesNoPill(radio?.roaming, noTone = Tone.NEUTRAL) })
            KeyValueRow("Mobile data", "", trailing = { YesNoPill(radio?.dataEnabled, "On", "Off", noTone = Tone.DANGER) })
            KeyValueRow("Airplane mode", "", trailing = {
                when (radio?.airplane) {
                    null -> YesNoPill(null)
                    true -> Pill("On", Tone.DANGER)
                    false -> Pill("Off", Tone.NEUTRAL)
                }
            })
        }
    }
}

// --- baseline ----------------------------------------------------------------------------------

@Composable
private fun BaselineCard(baseline: FinderSpot?, radio: RadioInfo?, settings: Settings) {
    val navigate = LocalNavigate.current
    SectionCard(title = "Baseline comparison") {
        if (baseline == null) {
            Text(
                "No baseline yet. After installation, set the winning spot as the baseline on the Signal Finder screen to compare live values against it.",
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.muted,
            )
            LinkButton("Open Signal Finder") { navigate(Dest.FINDER, null) }
            return@SectionCard
        }
        val base = baseline.metrics
        val pct = if (radio?.score != null && base.score.avg > 0) radio.score!! / base.score.avg * 100 else null
        val dropped = pct != null && pct < settings.alerts.signalDropPct
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("Live score vs baseline")
                Text(
                    pct?.let { "${it.roundToInt()}%" } ?: DASH,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        dropped -> hsm.danger
                        pct != null && pct >= 90 -> hsm.ok
                        else -> hsm.fg
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                Text(baseline.name, style = MaterialTheme.typography.titleMedium, color = hsm.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "set ${formatDateTime(baseline.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
                Text(
                    "PCI ${baseline.serving.pci ?: DASH}" + (baseline.serving.band?.let { " · B$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
            }
        }
        if (pct != null) {
            Gap(8.dp)
            ProgressBar(pct.coerceAtMost(100.0), color = if (dropped) hsm.danger else if (pct >= 90) hsm.ok else hsm.accent)
        }
        if (dropped) {
            Gap(10.dp)
            Notice(
                Tone.DANGER, null,
                "Below ${settings.alerts.signalDropPct}% of the installed baseline. Check for a cell change, a moved phone, or congestion.",
            )
        }
        Gap(12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DeltaRow("Score", radio?.score, base.score.avg, "")
            DeltaRow("RSRP", radio?.rsrp?.toDouble(), base.rsrp.avg, "dBm")
            DeltaRow("SINR", radio?.sinr, base.sinr?.avg, "dB")
            DeltaRow("RSRQ", radio?.rsrq?.toDouble(), base.rsrq?.avg, "dB")
        }
        if (radio?.pci != null && baseline.serving.pci != null && radio.pci != baseline.serving.pci) {
            Text(
                "Serving cell differs from the baseline (PCI ${radio.pci} now, ${baseline.serving.pci} at install).",
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.warn,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun DeltaRow(label: String, live: Double?, avg: Double?, unit: String) {
    val delta = if (live != null && avg != null) live - avg else null
    KeyValueRow(label, "", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${live?.let { trimNumber(it, 1) } ?: DASH} vs ${avg?.let { trimNumber(it, 1) } ?: DASH}${if (unit.isNotEmpty()) " $unit" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = hsm.fg,
            )
            if (delta != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (delta >= 0) "+" else "") + trimNumber(delta, 1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (delta >= 0) hsm.ok else hsm.warn,
                )
            }
        }
    })
}

// --- cell changes ------------------------------------------------------------------------------

private class CellChanges(val events: List<StoredEvent>, val loading: Boolean, val error: String?, val reload: () -> Unit)

/** cell_change events: fetched (GET /events?kind=cell_change) and merged with live ones. */
@Composable
private fun rememberCellChanges(deviceId: String?, live: List<StoredEvent>): CellChanges {
    val g = graph()
    val epoch = rememberReconnectEpoch()
    var tick by remember { mutableStateOf(0) }
    var fetched by remember(deviceId) { mutableStateOf<List<StoredEvent>>(emptyList()) }
    var loading by remember(deviceId) { mutableStateOf(true) }
    var error by remember(deviceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(deviceId, epoch, tick) {
        if (deviceId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            fetched = g.api.events(deviceId, kind = "cell_change", limit = 50)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            loading = false
        }
    }
    val merged = remember(fetched, live, deviceId) {
        val map = LinkedHashMap<String, StoredEvent>()
        for (e in fetched) map[e.id] = e
        for (e in live) if (e.kind == "cell_change" && e.deviceId == deviceId) map[e.id] = e
        map.values.sortedByDescending { isoMs(it.ts) ?: 0L }
    }
    return CellChanges(merged, loading, error) { tick++ }
}

private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

private fun cellLabel(c: JsonObject?): String {
    if (c == null) return DASH
    return listOfNotNull(
        c.num("pci")?.let { "PCI $it" },
        c.num("band")?.let { "B$it" },
        c.num("cell_id")?.let { "cell $it" },
    ).joinToString(" · ").ifEmpty { DASH }
}

@Composable
private fun CellChangesCard(cells: CellChanges) {
    var showAll by remember { mutableStateOf(false) }
    val now = rememberNow(10_000)
    SectionCard(
        title = "Cell changes",
        action = if (cells.events.size > 6) ({ LinkButton(if (showAll) "Show fewer" else "All ${cells.events.size}") { showAll = !showAll } }) else null,
    ) {
        when {
            cells.events.isEmpty() && cells.loading -> Loading()
            cells.events.isEmpty() && cells.error != null -> {
                Text("Could not load cell changes.", color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
                LinkButton("Try again", cells.reload)
            }
            cells.events.isEmpty() -> EmptyState("No cell changes recorded. A steady serving cell is good.")
            else -> {
                val list = if (showAll) cells.events else cells.events.take(6)
                Column {
                    list.forEachIndexed { i, e ->
                        val from = e.data?.get("from") as? JsonObject
                        val to = e.data?.get("to") as? JsonObject
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(hsm.accent))
                                if (i < list.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(hsm.border))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                                Text(
                                    "${formatRelative(e.ts, now)} · ${formatDateTime(e.ts)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = hsm.faint,
                                )
                                if (from != null || to != null) {
                                    Text(cellLabel(from), style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                                    Text("→ " + cellLabel(to), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = hsm.fg)
                                } else {
                                    Text(e.message, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- neighbours --------------------------------------------------------------------------------

@Composable
private fun NeighboursCard(list: List<NeighbourCell>, stale: Boolean) {
    SectionCard(title = "Neighbour cells", stale = stale, action = if (list.isNotEmpty()) ({ Text("${list.size}", style = MaterialTheme.typography.labelLarge, color = hsm.muted) }) else null) {
        if (list.isEmpty()) {
            EmptyState("No neighbour cells reported.")
            return@SectionCard
        }
        val sorted = list.sortedByDescending { it.rsrp ?: -999 }
        Column {
            sorted.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                NeighbourRow(n)
            }
        }
    }
}

@Composable
private fun NeighbourRow(n: NeighbourCell) {
    val rsrpGrade = n.rsrp?.let { gradeRsrp(it.toDouble()) }
    val rsrqGrade = n.rsrq?.let { gradeRsrq(it.toDouble()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatNetType(n.type.name)} · PCI ${n.pci ?: DASH}",
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
                    color = hsm.fg,
                )
                Text("Channel ${n.earfcn ?: DASH}", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtNum(n.rsrp, " dBm", 0), style = MaterialTheme.typography.titleMedium, color = hsm.grade(rsrpGrade))
                Text("RSRQ " + fmtNum(n.rsrq, " dB", 1), style = MaterialTheme.typography.bodySmall, color = hsm.grade(rsrqGrade))
            }
        }
        Spacer(Modifier.height(6.dp))
        val fill = n.rsrp?.let { (it + 120.0) / 50.0 * 100 }
        ProgressBar(fill, color = hsm.grade(rsrpGrade), modifier = Modifier.height(4.dp))
    }
}

// --- grades ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradesCard(settings: Settings) {
    val f = settings.finder
    SectionCard(title = "Grades") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (t in THRESHOLDS) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(t.label, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                        Spacer(Modifier.width(6.dp))
                        Text("(${t.unit})", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                    }
                    Text(t.meaning, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                    Spacer(Modifier.height(6.dp))
                    Grade.entries.chunked(2).forEachIndexed { r, pair ->
                        if (r > 0) Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (gr in pair) BandTile(gr, t.bands[gr.ordinal], Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Gap(14.dp)
        Text(
            "Strong bars alone do not mean fast internet: a clean signal (high SINR) from a less crowded cell often beats a strong signal from a busy one. " +
                "Score = ${pctOf(f.wSinr)} SINR + ${pctOf(f.wRsrp)} RSRP + ${pctOf(f.wRsrq)} RSRQ (weights editable in Settings).",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.muted,
        )
        Gap(10.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (gr in Grade.entries) {
                val band = when (gr) {
                    Grade.EXCELLENT -> "75+"
                    Grade.GOOD -> "55 to 75"
                    Grade.FAIR -> "35 to 55"
                    Grade.POOR -> "below 35"
                }
                Text(
                    "${gradeLabel(gr)}: score $band",
                    style = MaterialTheme.typography.labelMedium,
                    color = hsm.grade(gr),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(hsm.grade(gr).copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun pctOf(w: Double): String = "${trimNumber(w * 100, 0)}%"

@Composable
private fun BandTile(g: Grade, band: String, modifier: Modifier = Modifier) {
    val c = hsm.grade(g)
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(gradeLabel(g), style = MaterialTheme.typography.labelMedium, color = c)
            Text(band, style = MaterialTheme.typography.bodySmall, color = hsm.fg)
        }
    }
}
