package com.hotspotmonitor.dashboard.ui.finder

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.gradeRsrp
import com.hotspotmonitor.agent.protocol.gradeRsrq
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.agent.protocol.gradeSinr
import com.hotspotmonitor.agent.protocol.rankSpots
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.DeviceSnapshot
import com.hotspotmonitor.dashboard.data.LinkStatus
import com.hotspotmonitor.dashboard.data.LiveFinder
import com.hotspotmonitor.dashboard.data.formatDuration
import com.hotspotmonitor.dashboard.data.formatNetType
import com.hotspotmonitor.dashboard.data.formatTaDistance
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.GradeChip
import com.hotspotmonitor.dashboard.ui.components.Guide
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.RingGauge
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.SectionTitle
import com.hotspotmonitor.dashboard.ui.components.Series
import com.hotspotmonitor.dashboard.ui.components.TimeChart
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.gradeLabel
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.roundToInt

private const val LIVE_WINDOW_MS = 3 * 60_000L

/** The phone streams once per second; older than this counts as not live. */
private const val FRESH_MS = 6_000L

@Composable
fun FinderScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val link by g.repo.socket.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val runner = rememberCommandRunner()
    var paused by rememberSaveable { mutableStateOf(false) }
    var mirrorSince by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Ask for the 1 s mirror only while this screen is on screen and not paused (protocol 5.2):
    // it stops when the app goes to the background or another screen opens.
    LifecycleStartEffect(paused) {
        mirrorSince = System.currentTimeMillis()
        g.repo.setFinder(!paused)
        onStopOrDispose { g.repo.setFinder(false) }
    }

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val now = rememberNow(1000)
    val snap = deviceId?.let { data.snapshots[it] }
    val spots = deviceId?.let { data.spots[it] } ?: emptyList()
    val ranked = remember(spots) { rankSpots(spots) }
    val baseline = ranked.firstOrNull { it.baseline }
    val settings = deviceId?.let { data.settings[it]?.typed } ?: Settings.DEFAULT
    val caps = snap?.groups?.capabilities ?: emptyMap()
    fun supported(name: String) = caps[name]?.supported != false
    val last = data.lastCommandByName(deviceId)

    Page(
        title = "Signal Finder",
        subtitle = "Walk with the hotspot phone and read the score here. Measured speed ranks first, score second.",
        refreshing = loading,
        onRefresh = { g.repo.reloadDevice() },
        actions = {
            IconButton(onClick = { paused = !paused }) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (paused) "Resume live mirror" else "Pause live mirror",
                    tint = hsm.fg,
                )
            }
            if (spots.isNotEmpty()) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Signal Finder spots · ${snap?.name ?: "phone"}")
                        putExtra(Intent.EXTRA_TEXT, spotsToCsv(spots))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share spots as CSV"))
                }) { Icon(Icons.Outlined.Share, contentDescription = "Share spots as CSV", tint = hsm.fg) }
            }
        },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }

        item(key = "live") {
            LiveMirror(
                deviceId = deviceId,
                entry = deviceId?.let { data.finderLive[it] },
                snap = snap,
                paused = paused,
                mirrorSince = mirrorSince,
                linkOpen = link.status == LinkStatus.OPEN,
                now = now,
                onResume = { paused = false },
            )
        }

        if (session.isAdmin) {
            if (!session.canControl) {
                item { Notice(Tone.WARN, "Two-factor authentication needed", "Set up two-factor authentication to run spot and speed tests.") }
            } else {
                if (supported("finder_spot_test")) {
                    item(key = "spot_test") { SpotTestCard(runner, settings, last["finder_spot_test"], now) }
                }
                if (supported("speed_test")) {
                    item(key = "speed_test") { SpeedTestCard(runner, ranked, last["speed_test"], now) }
                }
            }
        }

        item(key = "spots_title") { SectionTitle("Saved spots (${spots.size})") }
        if (ranked.isEmpty()) {
            item(key = "spots_empty") {
                SectionCard {
                    EmptyState("No spots saved yet.")
                    Text(
                        "Run a spot test at each candidate place, then speed test the best three.",
                        style = MaterialTheme.typography.bodyMedium, color = hsm.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            itemsIndexed(ranked, key = { _, s -> "spot_" + s.id }) { i, s ->
                SpotCard(
                    spot = s,
                    rank = i + 1,
                    baseline = baseline,
                    runner = runner,
                    canControl = session.canControl,
                    supported = ::supported,
                )
            }
        }
        item(key = "tips") {
            Text(
                "Survey tips: test two or three orientations per spot, repeat speed tests at peak time (8 to 10 pm), and prefer a spot that stays on one cell. " +
                    "Walk mode (tone and voice) runs on the phone itself: open its Finder screen, or hold volume up for 3 s.",
                style = MaterialTheme.typography.bodySmall, color = hsm.muted,
            )
        }
    }
    CommandDialogs(runner)
}

private fun modeLabel(m: FinderMode): String = when (m) {
    FinderMode.IDLE -> "Idle"
    FinderMode.WALK -> "Walk mode"
    FinderMode.SPOT -> "Spot test running"
    FinderMode.SPEED -> "Speed test running"
    FinderMode.HEADLESS -> "Headless"
}

/** "8 s", "2 min 5 s". */
private fun ageText(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s s" else formatDuration(s)
}

@Composable
private fun LiveMirror(
    deviceId: String?,
    entry: LiveFinder?,
    snap: DeviceSnapshot?,
    paused: Boolean,
    mirrorSince: Long,
    linkOpen: Boolean,
    now: Long,
    onResume: () -> Unit,
) {
    val live = entry?.live
    val age = entry?.let { (now - it.receivedAtMs).coerceAtLeast(0) }
    val fresh = !paused && age != null && age < FRESH_MS
    // Just (re)started: an old reading from an earlier visit is not "stale" yet.
    val starting = !paused && !fresh && (entry == null || (entry.receivedAtMs < mirrorSince && now - mirrorSince < 15_000))

    // Score over the last 3 minutes, built from the stream while this screen is open.
    val trail = remember(deviceId) { mutableStateListOf<Pair<Long, Double?>>() }
    LaunchedEffect(deviceId, live?.ts) {
        val l = live ?: return@LaunchedEffect
        val score = l.score ?: return@LaunchedEffect
        val t = isoMs(l.ts) ?: entry?.receivedAtMs ?: System.currentTimeMillis()
        if (trail.any { it.first == t }) return@LaunchedEffect
        trail.removeAll { it.first <= t - LIVE_WINDOW_MS || it.first > t }
        trail.add(t to score)
    }

    val score = live?.score
    val grade = score?.let { gradeScore(it) }
    val gradeColor = hsm.grade(grade)

    SectionCard(
        title = "Live",
        stale = entry != null && !fresh && !paused && !starting,
        action = {
            when {
                paused -> Pill("Paused", Tone.NEUTRAL)
                fresh -> Pill(modeLabel(live!!.mode), Tone.OK, dot = true)
                starting -> Pill("Starting", Tone.INFO, dot = true)
                else -> Pill("${ageText(age ?: 0)} old", Tone.WARN, dot = true)
            }
        },
    ) {
        if (!fresh) {
            val ageLine = if (age != null) "The values below are ${ageText(age)} old." else null
            val notice: Triple<Tone, String, String?> = when {
                paused -> Triple(Tone.INFO, "Live mirror paused", "The phone stops streaming the score while paused." + (ageLine?.let { " $it" } ?: ""))
                snap?.online == false -> Triple(Tone.WARN, "The phone is offline", "The mirror starts when it reconnects." + (ageLine?.let { " $it" } ?: ""))
                !linkOpen -> Triple(Tone.INFO, "Waiting for the live connection to the server…", ageLine)
                starting -> Triple(
                    Tone.INFO, "Starting the live mirror",
                    "Asking the phone to stream the score once per second. It starts within a few seconds." +
                        (age?.let { " Until then the values below are ${ageText(it)} old." } ?: ""),
                )
                else -> Triple(Tone.WARN, "Live data is ${ageText(age!!)} old", "No update from the phone since then; it may be busy or on a weak link. The values below are the last ones received.")
            }
            Notice(
                notice.first, notice.second, notice.third,
                action = if (paused) ({ Button(onClick = onResume) { Text("Resume") } }) else null,
            )
            Spacer(Modifier.height(14.dp))
        }

        // Hero: score ring with grade, smoothed vs raw.
        val dim = if (fresh) 1f else 0.55f
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(score?.div(100), gradeColor, size = 156.dp, stroke = 14.dp, modifier = Modifier.alpha(dim)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(score?.roundToInt()?.toString() ?: DASH, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = gradeColor, lineHeight = 54.sp)
                    Text("score", style = MaterialTheme.typography.labelSmall, color = hsm.muted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GradeChip(grade)
                Row {
                    SmallStat("Smoothed", score?.let { trimNumber(it, 0) } ?: DASH, Modifier.weight(1f))
                    SmallStat("Raw now", live?.scoreRaw?.let { trimNumber(it, 0) } ?: DASH, Modifier.weight(1f))
                }
                val switches = live?.cellSwitches5min
                if (switches != null) {
                    Pill(
                        if (switches == 0) "Stable cell (5 min)" else "$switches cell switch${if (switches == 1) "" else "es"} (5 min)",
                        if (switches > 0) Tone.WARN else Tone.OK,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        ScoreBar(score)

        // Running spot / speed test.
        val progress = live?.progress
        if (progress != null) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hsm.infoSoft).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progress.label, style = MaterialTheme.typography.titleMedium, color = hsm.fg, modifier = Modifier.weight(1f))
                    Text("${progress.elapsedS} of ${progress.totalS} s", style = MaterialTheme.typography.labelLarge, color = hsm.info)
                }
                ProgressBar(if (progress.totalS > 0) progress.elapsedS * 100.0 / progress.totalS else 0.0, Tone.INFO)
            }
        }

        // RSRP / SINR / RSRQ.
        Spacer(Modifier.height(14.dp))
        EqualRow(spacing = 8.dp) {
            MetricTile("RSRP", live?.rsrp, "dBm", live?.rsrp?.let { gradeRsrp(it) }, -120.0, -70.0, Modifier.weight(1f))
            MetricTile("SINR", live?.sinr, "dB", live?.sinr?.let { gradeSinr(it) }, -5.0, 25.0, Modifier.weight(1f))
            MetricTile("RSRQ", live?.rsrq, "dB", live?.rsrq?.let { gradeRsrq(it) }, -20.0, -8.0, Modifier.weight(1f))
        }

        // Serving cell.
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyValueRow("Network", formatNetType(live?.netType?.name))
            KeyValueRow("Band", live?.band?.let { "B$it" } ?: DASH)
            KeyValueRow("PCI", live?.pci?.toString() ?: DASH)
            KeyValueRow("Cell ID", live?.cellId?.toString() ?: DASH)
            KeyValueRow("Timing advance", live?.ta?.let { "$it (${formatTaDistance(it)})" } ?: DASH)
            KeyValueRow("Cell switches (5 min)", "", trailing = {
                val cs = live?.cellSwitches5min
                if (cs == null) Text(DASH, color = hsm.fg, style = MaterialTheme.typography.bodyMedium)
                else Pill(cs.toString(), if (cs > 0) Tone.WARN else Tone.OK)
            })
        }
        if ((live?.cellSwitches5min ?: 0) > 0) {
            Text(
                "This spot keeps switching cells; speed will be unstable even if the bars look good.",
                style = MaterialTheme.typography.bodySmall, color = hsm.warn, modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Score over the last 3 minutes.
        Spacer(Modifier.height(14.dp))
        Text("Last 3 minutes", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        Spacer(Modifier.height(4.dp))
        TimeChart(
            series = listOf(Series("Score", gradeColor.takeIf { grade != null } ?: hsm.accent, trail.toList())),
            height = 130.dp,
            yMin = 0.0,
            yMax = 100.0,
            guides = listOf(Guide(75.0, hsm.gradeExcellent), Guide(55.0, hsm.gradeGood), Guide(35.0, hsm.gradeFair)),
            gapMs = 10_000,
            digits = 0,
        )
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = hsm.muted)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = hsm.fg)
    }
}

/** A signal value with its unit, grade and a bar between [worst] and [best]. */
@Composable
private fun MetricTile(label: String, value: Double?, unit: String, grade: Grade?, worst: Double, best: Double, modifier: Modifier = Modifier) {
    val c = hsm.grade(grade)
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(hsm.surface2).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { trimNumber(it, 1) } ?: DASH, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = hsm.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        ProgressBar(value?.let { (it - worst) / (best - worst) * 100 }, color = c)
        Text(
            when (grade) {
                Grade.EXCELLENT, Grade.GOOD -> "▲ "
                Grade.FAIR -> "■ "
                Grade.POOR -> "▼ "
                null -> ""
            } + gradeLabel(grade),
            style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1,
        )
    }
}

/** Grade bands of the score (plan 7.2) with a marker at the current value. */
@Composable
private fun ScoreBar(score: Double?) {
    val c = hsm
    val pos by animateFloatAsState(((score ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat(), tween(500), label = "scoreBar")
    val bands = listOf(0.35f to c.gradePoor, 0.20f to c.gradeFair, 0.20f to c.gradeGood, 0.25f to c.gradeExcellent)
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val barH = 12.dp.toPx()
            val top = (size.height - barH) / 2
            val clip = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + barH, CornerRadius(barH / 2))) }
            clipPath(clip) {
                var x = 0f
                for ((frac, color) in bands) {
                    val w = frac * size.width
                    drawRect(color, Offset(x, top), Size(w, barH))
                    x += w
                }
            }
            if (score != null) {
                val mw = 6.dp.toPx()
                val ring = 2.dp.toPx()
                val cx = (pos * size.width).coerceIn(mw / 2 + ring, size.width - mw / 2 - ring)
                drawRoundRect(c.bg, Offset(cx - mw / 2 - ring, 0f), Size(mw + 2 * ring, size.height), CornerRadius(mw))
                drawRoundRect(c.fg, Offset(cx - mw / 2, ring), Size(mw, size.height - 2 * ring), CornerRadius(mw / 2))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val w = maxWidth
            for (tick in listOf(0, 35, 55, 75, 100)) {
                val x = (w * (tick / 100f) - 12.dp).coerceIn(0.dp, w - 24.dp)
                Box(Modifier.offset(x = x).width(24.dp).height(16.dp), contentAlignment = Alignment.Center) {
                    Text(tick.toString(), fontSize = 10.sp, color = c.muted)
                }
            }
        }
    }
}
