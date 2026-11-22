package com.hotspotmonitor.dashboard.ui.finder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.MetricStats
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.agent.protocol.rankSpots
import com.hotspotmonitor.dashboard.data.AppJson
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.decodeOrNull
import com.hotspotmonitor.dashboard.data.formatDateTime
import com.hotspotmonitor.dashboard.data.formatMbps
import com.hotspotmonitor.dashboard.data.formatNetType
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.GradeChip
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Stat
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

private val ORIENTATIONS = listOf("upright" to "Upright", "flat" to "Flat", "rot90" to "Turned 90°")

private fun parseJson(s: String) = runCatching { AppJson.parseToJsonElement(s) }.getOrNull()

/** One line for a speed session: "↓ 42 Mbps · ↑ 8.1 Mbps · ping 31 ms · jitter 4.2 ms". */
private fun speedLine(s: SpeedSession): String =
    "↓ ${formatMbps(s.downMbps)} · ↑ ${formatMbps(s.upMbps)} · ping ${s.pingMs?.let { "${it.roundToInt()} ms" } ?: DASH}" +
        " · jitter ${s.jitterMs?.let { "${trimNumber(it, 1)} ms" } ?: DASH}"

/** Readable output of finder commands (their output is the JSON of the result). */
private fun finderOutput(name: String, raw: String): String? = when (name) {
    "speed_test" -> decodeOrNull(SpeedSession.serializer(), parseJson(raw))?.let { speedLine(it) + " · ${it.runs.size} runs" } ?: raw
    "finder_spot_test" -> decodeOrNull(FinderSpot.serializer(), parseJson(raw))?.let {
        "Saved “${it.name}”: score ${it.metrics.score.avg.roundToInt()}, RSRP ${trimNumber(it.metrics.rsrp.avg, 0)} dBm" +
            (it.metrics.sinr?.let { m -> ", SINR ${trimNumber(m.avg, 1)} dB" } ?: "")
    } ?: raw
    else -> raw
}

@Composable
private fun BusyButton(text: String, busy: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.height(48.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
            Spacer(Modifier.width(10.dp))
        }
        Text(text)
    }
}

// --- spot test -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotTestCard(runner: CommandRunner, settings: Settings, last: CommandRecord?, now: Long) {
    var name by rememberSaveable { mutableStateOf("") }
    var orientation by rememberSaveable { mutableStateOf("upright") }
    var duration by rememberSaveable(settings.finder.spotTestS) { mutableStateOf(settings.finder.spotTestS.toString()) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val d = duration.trim().toIntOrNull()
    val durErr = if (d == null || d < 10 || d > 300) "Between 10 and 300 s." else null
    val nameErr = when {
        name.isBlank() -> "Name the spot, e.g. \"Roof NE corner\"."
        name.trim().length > 60 -> "At most 60 characters."
        else -> null
    }
    val busy = runner.isBusy("spot_test")

    SectionCard(title = "Spot test") {
        Text(COMMAND_HELP.getValue("finder_spot_test"), style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text("Spot name") },
            singleLine = true,
            isError = touched && nameErr != null,
            supportingText = if (touched && nameErr != null) ({ Text(nameErr) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text("Orientation", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ORIENTATIONS.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = orientation == value,
                    onClick = { orientation = value },
                    shape = SegmentedButtonDefaults.itemShape(i, ORIENTATIONS.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = duration,
                onValueChange = { v -> duration = v.filter { it.isDigit() }.take(3) },
                label = { Text("Duration") },
                suffix = { Text("s") },
                singleLine = true,
                isError = durErr != null,
                supportingText = durErr?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.width(130.dp),
            )
            Spacer(Modifier.width(12.dp))
            BusyButton("Start spot test", busy, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                touched = true
                if (nameErr == null && durErr == null) {
                    runner.run(
                        "finder_spot_test",
                        buildJsonObject {
                            put("name", name.trim())
                            put("orientation", orientation)
                            put("duration_s", d!!)
                        },
                        key = "spot_test",
                    )
                }
            }
        }
        Text("Hold the phone still at mounting height for the whole test.", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        if (last != null) {
            Spacer(Modifier.height(10.dp))
            CommandResult(last, now) { finderOutput(last.name, it) }
        }
    }
}

// --- speed test ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestCard(runner: CommandRunner, ranked: List<FinderSpot>, last: CommandRecord?, now: Long) {
    var spotId by rememberSaveable { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf(false) }
    val spot = ranked.firstOrNull { it.id == spotId }
    val busy = runner.isBusy("speed_test")

    SectionCard(title = "Speed test") {
        Text(COMMAND_HELP.getValue("speed_test"), style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
        Spacer(Modifier.height(12.dp))
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = spot?.let { it.name + if (it.baseline) " (baseline)" else "" } ?: "No spot (just measure)",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Attach to spot (optional)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("No spot (just measure)") }, onClick = { spotId = null; open = false })
                for (s in ranked) {
                    DropdownMenuItem(
                        text = { Text(s.name + if (s.baseline) " (baseline)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { spotId = s.id; open = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BusyButton("Run speed test", busy, modifier = Modifier.fillMaxWidth()) {
            runner.run(
                "speed_test",
                if (spot != null) buildJsonObject { put("spot_id", spot.id) } else buildJsonObject { },
                key = "speed_test",
                detail = spot?.let { "Attached to spot: ${it.name}" } ?: "Not attached to a spot (just measures).",
            )
        }
        if (last != null) {
            Spacer(Modifier.height(10.dp))
            CommandResult(last, now) { finderOutput(last.name, it) }
        }
    }
}

// --- saved spots ---------------------------------------------------------------------------------

private fun statLine(m: MetricStats?, unit: String): String {
    if (m == null) return DASH
    val u = if (unit.isEmpty()) "" else " $unit"
    return "${trimNumber(m.avg, 1)}$u (${trimNumber(m.min, 0)} to ${trimNumber(m.max, 0)}, ±${trimNumber(m.std, 1)})"
}

private fun signed(v: Double, digits: Int): String = (if (v > 0) "+" else "") + trimNumber(v, digits)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SpotCard(
    spot: FinderSpot,
    rank: Int,
    baseline: FinderSpot?,
    runner: CommandRunner,
    canControl: Boolean,
    supported: (String) -> Boolean,
) {
    var open by rememberSaveable(spot.id) { mutableStateOf(false) }
    val score = spot.metrics.score.avg
    val grade = gradeScore(score)
    val best = spot.bestDownMbps
    val peakTested = spot.speed.any { it.peakHour }
    val shape = RoundedCornerShape(20.dp)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = hsm.surface, contentColor = hsm.fg),
        border = BorderStroke(if (spot.baseline) 1.5.dp else 1.dp, if (spot.baseline) hsm.accent else hsm.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (rank == 1) hsm.infoSoft else hsm.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$rank", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (rank == 1) hsm.accent else hsm.fg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(spot.name, style = MaterialTheme.typography.titleMedium, color = hsm.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            formatDateTime(spot.createdAt),
                            spot.orientation ?: "orientation not set",
                            formatNetType(spot.serving.netType.name) + (spot.serving.band?.let { " B$it" } ?: ""),
                            "PCI ${spot.serving.pci ?: DASH}",
                            spot.compassDeg?.let { "facing ${it.roundToInt()}°" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = hsm.muted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${score.roundToInt()}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = hsm.grade(grade), lineHeight = 32.sp)
                    GradeChip(grade)
                }
            }

            val badges = spot.baseline || spot.cellSwitches > 0 || spot.speed.isNotEmpty()
            if (badges) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (spot.baseline) Pill("★ Baseline", Tone.INFO)
                    if (spot.cellSwitches > 0) Pill("${spot.cellSwitches} cell switch${if (spot.cellSwitches == 1) "" else "es"}", Tone.WARN)
                    if (peakTested) Pill("Peak-hour tested", Tone.OK) else if (spot.speed.isNotEmpty()) Pill("Not tested at peak", Tone.NEUTRAL)
                }
            }

            EqualRow(spacing = 8.dp) {
                Stat("Best download", formatMbps(best), Modifier.weight(1.3f), size = 20.sp)
                Stat("RSRP", "${trimNumber(spot.metrics.rsrp.avg, 0)} dBm", Modifier.weight(1f), size = 18.sp)
                Stat("SINR", spot.metrics.sinr?.let { "${trimNumber(it.avg, 1)} dB" } ?: DASH, Modifier.weight(1f), size = 18.sp)
            }

            if (baseline != null && !spot.baseline) {
                val dScore = score - baseline.metrics.score.avg
                val bBest = baseline.bestDownMbps
                val parts = mutableListOf("Score ${signed(dScore, 0)}")
                if (best != null && bBest != null) parts += "download ${signed(best - bBest, 1)} Mbps"
                Text(
                    "${parts.joinToString(" · ")} vs baseline “${baseline.name}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        dScore >= 5 -> hsm.ok
                        dScore <= -5 -> hsm.warn
                        else -> hsm.muted
                    },
                )
            }

            if (open) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hsm.surface2).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeyValueRow("Score", statLine(spot.metrics.score, ""))
                    KeyValueRow("RSRP", statLine(spot.metrics.rsrp, "dBm"))
                    KeyValueRow("SINR", statLine(spot.metrics.sinr, "dB"))
                    KeyValueRow("RSRQ", statLine(spot.metrics.rsrq, "dB"))
                    KeyValueRow("Test length", "${spot.durationS} s · ${spot.metrics.score.samples} samples")
                    if (spot.serving.cellId != null) KeyValueRow("Cell ID", spot.serving.cellId.toString())
                    if (spot.lat != null && spot.lon != null) {
                        KeyValueRow("Location", "${trimNumber(spot.lat!!, 5)}, ${trimNumber(spot.lon!!, 5)}" + (spot.accM?.let { " ±${it.roundToInt()} m" } ?: ""))
                    }
                    if (spot.hasPhoto == true) KeyValueRow("Photo", "on the phone")
                }
                Text("Speed tests", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
                if (spot.speed.isEmpty()) {
                    Text("No speed test yet.", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                } else {
                    for (s in spot.speed) {
                        Column {
                            Text(
                                formatDateTime(s.at) + (if (s.peakHour) " (peak)" else "") + " · ${s.runs.size} runs",
                                style = MaterialTheme.typography.labelSmall, color = hsm.faint,
                            )
                            Text(speedLine(s), style = MaterialTheme.typography.bodySmall, color = hsm.fg)
                        }
                    }
                }
            }
            LinkButton(if (open) "Hide details" else "Details and speed tests (${spot.speed.size})") { open = !open }

            if (canControl) {
                HorizontalDivider(color = hsm.border)
                val speedKey = "speed:${spot.id}"
                val baseKey = "base:${spot.id}"
                val delKey = "del:${spot.id}"
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (supported("speed_test")) {
                        SpotAction("Speed test here", runner.isBusy(speedKey)) {
                            runner.run("speed_test", buildJsonObject { put("spot_id", spot.id) }, key = speedKey, detail = "Spot: ${spot.name}")
                        }
                    }
                    if (!spot.baseline && supported("finder_set_baseline")) {
                        SpotAction("Set as baseline", runner.isBusy(baseKey)) {
                            runner.run("finder_set_baseline", buildJsonObject { put("spot_id", spot.id) }, key = baseKey, detail = "Spot: ${spot.name}")
                        }
                    }
                    if (supported("finder_delete_spot")) {
                        SpotAction("Delete", runner.isBusy(delKey), danger = true) {
                            runner.run("finder_delete_spot", buildJsonObject { put("spot_id", spot.id) }, key = delKey, detail = "Spot: ${spot.name}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotAction(text: String, busy: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val color: Color = if (danger) hsm.danger else hsm.accent
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        border = BorderStroke(1.dp, if (danger) hsm.danger.copy(alpha = 0.5f) else hsm.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = Modifier.height(44.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

// --- CSV export (web lib/csv.ts) -------------------------------------------------------------------

private fun csvCell(v: Any?): String = when (v) {
    null -> ""
    is Double -> if (v.isFinite()) (if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()) else ""
    is Number -> v.toString()
    is Boolean -> if (v) "yes" else "no"
    else -> {
        var s = v.toString()
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        if (s.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

private fun round2(v: Double?): Double? = v?.takeIf { it.isFinite() }?.let { Math.round(it * 100) / 100.0 }

private fun stats(m: MetricStats?): List<Any?> = if (m == null) listOf(null, null, null, null) else listOf(round2(m.avg), round2(m.min), round2(m.max), round2(m.std))

private val SPOT_CSV_HEADER = listOf(
    "rank", "name", "created_at", "baseline", "orientation", "lat", "lon", "accuracy_m", "compass_deg",
    "net_type", "band", "pci", "cell_id", "earfcn", "ta", "cell_switches",
    "score_avg", "score_min", "score_max", "score_std",
    "rsrp_avg", "rsrp_min", "rsrp_max", "rsrp_std",
    "sinr_avg", "sinr_min", "sinr_max", "sinr_std",
    "rsrq_avg", "rsrq_min", "rsrq_max", "rsrq_std",
    "best_down_mbps", "best_up_mbps", "best_ping_ms", "speed_sessions", "peak_hour_tested",
)

/** Ranked spots as CSV (same columns as the web export). */
fun spotsToCsv(spots: List<FinderSpot>): String {
    val rows = mutableListOf<List<Any?>>(SPOT_CSV_HEADER)
    rankSpots(spots).forEachIndexed { i, s ->
        rows += listOf(
            i + 1, s.name, s.createdAt, s.baseline, s.orientation, s.lat, s.lon, s.accM, s.compassDeg,
            s.serving.netType.name, s.serving.band, s.serving.pci, s.serving.cellId, s.serving.earfcn, s.serving.ta, s.cellSwitches,
        ) + stats(s.metrics.score) + stats(s.metrics.rsrp) + stats(s.metrics.sinr) + stats(s.metrics.rsrq) + listOf(
            s.bestDownMbps,
            s.speed.mapNotNull { it.upMbps }.maxOrNull(),
            s.speed.mapNotNull { it.pingMs }.minOrNull(),
            s.speed.size,
            s.speed.any { it.peakHour },
        )
    }
    return rows.joinToString("\r\n") { r -> r.joinToString(",") { csvCell(it) } } + "\r\n"
}
