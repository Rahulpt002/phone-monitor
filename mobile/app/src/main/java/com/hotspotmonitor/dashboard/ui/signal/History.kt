package com.hotspotmonitor.dashboard.ui.signal

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.LinkStatus
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.isoOf
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.Guide
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.SectionTitle
import com.hotspotmonitor.dashboard.ui.components.Series
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// History charts for the Signal, Network and Power screens (port of web useHistory.ts,
// MetricChart.tsx and lib/series.ts). Shared by the three packages of this builder.

/** (unix ms, value or null for a gap) */
typealias Point = Pair<Long, Double?>

/** Chart range chips (web RANGES). Ranges above 6 h come back as 1-minute averages. */
enum class HistoryRange(val key: String, val label: String, val ms: Long) {
    H1("1h", "1 h", 3_600_000L),
    H6("6h", "6 h", 6 * 3_600_000L),
    H24("24h", "24 h", 24 * 3_600_000L),
    D7("7d", "7 d", 7 * 86_400_000L),
    D30("30d", "30 d", 30 * 86_400_000L);

    /** Averaged ranges refetch every few minutes instead of appending live points. */
    val averaged: Boolean get() = ms > 6 * 3_600_000L

    companion object {
        fun of(key: String?): HistoryRange? = entries.firstOrNull { it.key == key }
    }
}

/** Chart metrics from shared/src/metrics.ts: key, sending tier and json_extract path. */
data class MetricDef(val key: String, val tier: String, val path: String)

val METRICS: Map<String, MetricDef> = listOf(
    MetricDef("radio.score", "fast", "$.radio.score"),
    MetricDef("radio.rsrp", "fast", "$.radio.rsrp"),
    MetricDef("radio.rsrq", "fast", "$.radio.rsrq"),
    MetricDef("radio.sinr", "fast", "$.radio.sinr"),
    MetricDef("radio.nr.ss_rsrp", "fast", "$.radio.nr.ss_rsrp"),
    MetricDef("radio.nr.ss_sinr", "fast", "$.radio.nr.ss_sinr"),
    MetricDef("radio.pci", "fast", "$.radio.pci"),
    MetricDef("net.rx_bps", "fast", "$.net.rx_bps"),
    MetricDef("net.tx_bps", "fast", "$.net.tx_bps"),
    MetricDef("net.ping_ms", "fast", "$.net.ping_ms"),
    MetricDef("net.ping_loss_pct", "fast", "$.net.ping_loss_pct"),
    MetricDef("net.jitter_ms", "fast", "$.net.jitter_ms"),
    MetricDef("power.percent", "slow", "$.power.percent"),
    MetricDef("power.current_ma", "slow", "$.power.current_ma"),
    MetricDef("power.voltage_mv", "slow", "$.power.voltage_mv"),
    MetricDef("heat.battery_c", "slow", "$.heat.battery_c"),
    MetricDef("heat.cpu_c", "slow", "$.heat.cpu_c"),
    MetricDef("heat.modem_c", "slow", "$.heat.modem_c"),
    MetricDef("heat.skin_c", "slow", "$.heat.skin_c"),
    MetricDef("system.load1", "slow", "$.system.cpu_load[0]"),
    MetricDef("system.ram_avail_mb", "slow", "$.system.ram_avail_mb"),
    MetricDef("traffic.cycle_rx", "slow", "$.traffic.cycle_rx"),
    MetricDef("traffic.cycle_tx", "slow", "$.traffic.cycle_tx"),
).associateBy { it.key }

private val PATH_TOKEN = Regex("""\.([^.\[\]]+)|\[(\d+)]""")

/** Number at a json_extract-style path ("$.radio.score", "$.system.cpu_load[0]"), or null. */
fun numericAt(obj: JsonElement, path: String): Double? {
    if (!path.startsWith("$")) return null
    var cur: JsonElement? = obj
    for (m in PATH_TOKEN.findAll(path.substring(1))) {
        val name = m.groups[1]?.value
        val idx = m.groups[2]?.value?.toIntOrNull()
        cur = when {
            name != null -> (cur as? JsonObject)?.get(name)
            idx != null -> (cur as? JsonArray)?.getOrNull(idx)
            else -> null
        }
        if (cur == null) return null
    }
    val p = cur as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.doubleOrNull?.takeIf { it.isFinite() }
}

/** Appends keeping time order and drops points older than [fromMs] (web appendPoint). */
fun appendPoint(points: List<Point>, p: Point, fromMs: Long): List<Point> {
    val last = points.lastOrNull()
    val next = when {
        last == null || p.first > last.first -> points + p
        p.first == last.first -> points.dropLast(1) + p
        else -> return points // out of order; the next fetch has it
    }
    val start = next.indexOfFirst { it.first >= fromMs }
    return if (start <= 0) next else next.subList(start, next.size)
}

/** Median spacing between points (0 with fewer than 3). */
fun medianStep(points: List<Point>): Long {
    if (points.size < 3) return 0
    val steps = LongArray(points.size - 1) { points[it + 1].first - points[it].first }
    steps.sort()
    return steps[steps.size / 2]
}

/** How long (ms) the newest points have continuously satisfied [pred] (web trailingDuration). */
fun trailingDuration(points: List<Point>, pred: (Double) -> Boolean): Long {
    var i = points.size - 1
    while (i >= 0 && points[i].second == null) i--
    if (i < 0) return 0
    val end = points[i].first
    var start = end
    while (i >= 0) {
        val v = points[i].second
        if (v != null) {
            if (!pred(v)) break
            start = points[i].first
        }
        i--
    }
    return end - start
}

/** Bucket-averages long series so a 30-day chart stays light to draw and to scrub. */
fun downsample(points: List<Point>, startMs: Long, endMs: Long, buckets: Int = 320): List<Point> {
    if (points.size <= buckets * 2 || endMs <= startMs) return points
    val width = (endMs - startMs).toDouble() / buckets
    val sum = DoubleArray(buckets)
    val count = IntArray(buckets)
    val timeSum = DoubleArray(buckets)
    for ((t, v) in points) {
        if (v == null) continue
        val b = ((t - startMs) / width).toInt().coerceIn(0, buckets - 1)
        sum[b] += v
        timeSum[b] += t.toDouble()
        count[b]++
    }
    val out = ArrayList<Point>(buckets)
    for (b in 0 until buckets) if (count[b] > 0) out += (timeSum[b] / count[b]).toLong() to sum[b] / count[b]
    return out
}

// --- history state ------------------------------------------------------------------------------

@Stable
class HistoryState {
    var series by mutableStateOf<Map<String, List<Point>>>(emptyMap())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    /** "raw" or "1m" as reported by the Server. */
    var res by mutableStateOf<String?>(null)
    /** Newest fetch or live point (chart window end). */
    var lastMs by mutableLongStateOf(0L)
    var tick by mutableIntStateOf(0)

    fun reload() {
        tick++
    }
}

/** Counts reconnects of the dashboard socket (web conn epoch): loaders refetch on change. */
@Composable
fun rememberReconnectEpoch(): Int {
    val g = graph()
    var epoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var wasOpen = g.repo.socket.state.value.status == LinkStatus.OPEN
        g.repo.socket.state.collect { s ->
            val open = s.status == LinkStatus.OPEN
            if (open && !wasOpen) epoch++
            wasOpen = open
        }
    }
    return epoch
}

/**
 * History of [metrics] for the selected phone over [range] (GET /api/telemetry), kept live by
 * appending matching values from the socket, refetched after a socket reconnect, after the phone
 * comes back online (its backlog arrives late), and every 5 min for averaged ranges.
 */
@Composable
fun rememberHistory(metrics: List<String>, range: HistoryRange): HistoryState {
    val g = graph()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val id = deviceId
    val key = metrics.joinToString(",")
    val state = remember(id, key, range) { HistoryState() }
    val epoch = rememberReconnectEpoch()

    LaunchedEffect(state, state.tick, epoch) {
        if (id == null) {
            state.series = emptyMap()
            state.loading = false
            return@LaunchedEffect
        }
        state.loading = true
        state.error = null
        val to = System.currentTimeMillis()
        val from = to - range.ms
        try {
            val results = coroutineScope {
                metrics.map { m -> async { runCatching { g.api.telemetry(id, m, isoOf(from), isoOf(to)) } } }.awaitAll()
            }
            results.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
            val next = LinkedHashMap<String, List<Point>>()
            results.forEachIndexed { i, r ->
                val pts = r.getOrNull()?.points ?: return@forEachIndexed
                next[metrics[i]] = pts.mapNotNull { p ->
                    val t = p.getOrNull(0)?.takeIf { it.isFinite() }?.toLong() ?: return@mapNotNull null
                    t to p.getOrNull(1)?.takeIf { it.isFinite() }
                }.sortedBy { it.first }
            }
            // Keep live points appended while the request was in flight.
            val cur = state.series
            for (k in next.keys.toList()) {
                val later = cur[k].orEmpty().filter { it.first > to }
                if (later.isNotEmpty()) next[k] = next[k]!! + later
            }
            for ((k, v) in cur) if (k !in next) next[k] = v
            state.series = next
            state.res = results.firstNotNullOfOrNull { it.getOrNull()?.res }
            state.lastMs = max(state.lastMs, to)
            val failed = results.mapNotNull { it.exceptionOrNull() }
            if (failed.isNotEmpty() && failed.size == results.size) state.error = failed.first().userMessage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.userMessage()
        } finally {
            state.loading = false
        }
    }

    if (range.averaged) {
        LaunchedEffect(state) {
            while (true) {
                delay(5 * 60_000L)
                state.tick++
            }
        }
    }

    // Phone back online after an outage: fetch the gap once its backlog has had time to arrive.
    LaunchedEffect(state) {
        if (id == null) return@LaunchedEffect
        var online: Boolean? = g.store.current.snapshots[id]?.online
        g.store.state.map { it.snapshots[id]?.online }.distinctUntilChanged().collect { now ->
            val cameBack = online == false && now == true
            online = now
            if (cameBack) launch {
                delay(20_000)
                state.tick++
                delay(70_000)
                state.tick++
            }
        }
    }

    // Live appends for raw-resolution ranges.
    if (!range.averaged) {
        LaunchedEffect(state) {
            if (id == null) return@LaunchedEffect
            val defs = metrics.mapNotNull { METRICS[it] }
            g.store.state.map { it.lastTelemetry }.distinctUntilChanged { a, b -> a === b }.collect { msg ->
                if (msg == null || msg.deviceId != id) return@collect
                val t = isoMs(msg.ts) ?: return@collect
                var next: MutableMap<String, List<Point>>? = null
                for (d in defs) {
                    if (msg.tier.isNotEmpty() && d.tier != msg.tier) continue
                    val v = numericAt(msg.groups, d.path) ?: continue
                    val m = next ?: state.series.toMutableMap().also { next = it }
                    m[d.key] = appendPoint(m[d.key].orEmpty(), t to v, System.currentTimeMillis() - range.ms)
                }
                next?.let {
                    state.series = it
                    state.lastMs = max(state.lastMs, t)
                }
            }
        }
    }
    return state
}

/** Chart range shared by the three screens and kept across launches (web storage KEYS.range). */
@Composable
fun rememberHistoryRange(): Pair<HistoryRange, (HistoryRange) -> Unit> {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hsm_ui", Context.MODE_PRIVATE) }
    var range by remember { mutableStateOf(HistoryRange.of(prefs.getString(KEY_RANGE, null)) ?: HistoryRange.H6) }
    return range to { r ->
        range = r
        prefs.edit().putString(KEY_RANGE, r.key).apply()
    }
}

private const val KEY_RANGE = "chart_range"

// --- UI -----------------------------------------------------------------------------------------

/** Segmented range chips (1 h … 30 d), full width with big touch targets. */
@Composable
fun RangeChips(value: HistoryRange, onChange: (HistoryRange) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().clip(shape).background(hsm.surface).border(1.dp, hsm.border, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (r in HistoryRange.entries) {
            val sel = r == value
            Box(
                Modifier.weight(1f).height(40.dp).clip(shape)
                    .background(if (sel) hsm.accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(r) }
                    .semantics {
                        selected = sel
                        contentDescription = "Last ${r.label}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) hsm.onAccent else hsm.muted,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** "History" title with the range chips under it. */
@Composable
fun HistoryHeader(range: HistoryRange, onChange: (HistoryRange) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("History")
        RangeChips(range, onChange)
    }
}

/** Categorical slots (dataviz reference palette, validated on this app's surfaces). */
private val SERIES_LIGHT = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100))
private val SERIES_DARK = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500))

@Composable
fun seriesColor(slot: Int): Color = (if (hsm.dark) SERIES_DARK else SERIES_LIGHT)[slot.coerceIn(0, 3)]

/** Guide lines at grade boundaries; [lines] go best to worst (excellent/good, good/fair, fair/poor). */
@Composable
fun gradeGuides(lines: List<Double>): List<Guide> =
    lines.mapIndexed { i, v -> Guide(v, hsm.grade(Grade.entries[i.coerceAtMost(3)])) }

/** One line on a [MetricChartCard]. */
data class ChartLine(val metric: String, val label: String, val color: Color)

/**
 * A card with a history chart of metrics sharing one unit. Lines without any data in the window
 * are left out of the legend (e.g. the 5G leg on a 4G-only cell).
 */
@Composable
fun MetricChartCard(
    title: String,
    lines: List<ChartLine>,
    history: HistoryState,
    range: HistoryRange,
    format: (Double) -> String,
    yMin: Double? = null,
    yMax: Double? = null,
    guides: List<Guide> = emptyList(),
    transform: ((Double) -> Double)? = null,
    note: String? = null,
) {
    val now = rememberNow(30_000)
    val end = max(now, history.lastMs)
    val start = end - range.ms
    val seriesMap = history.series
    val prepared = remember(seriesMap, start, end, lines, transform) {
        val raw = lines.map { l ->
            l to seriesMap[l.metric].orEmpty().filter { it.first >= start }.map { (t, v) -> t to v?.let { x -> transform?.invoke(x) ?: x } }
        }
        val withData = raw.filter { (_, pts) -> pts.any { it.second != null } }.ifEmpty { raw.take(1) }
        val series = withData.map { (l, pts) -> Series(l.label, l.color, downsample(pts, start, end)) }
        val step = series.map { medianStep(it.points) }.filter { it > 0 }.minOrNull() ?: 0L
        series to max(3 * 60_000L, step * 4)
    }
    SectionCard(
        title = title,
        action = {
            history.res?.let {
                Text(if (it == "raw") "raw readings" else "1-min averages", style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            }
        },
    ) {
        val (series, gap) = prepared
        val hasData = series.any { s -> s.points.any { it.second != null } }
        when {
            !hasData && history.loading -> ChartMessage(height = 170.dp) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.accent)
                Spacer(Modifier.width(10.dp))
                Text("Loading history…", color = hsm.muted, style = MaterialTheme.typography.bodyMedium)
            }
            !hasData && history.error != null -> ChartMessage(height = 170.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load history", color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
                    Text(history.error!!, color = hsm.muted, style = MaterialTheme.typography.bodySmall)
                    LinkButton("Try again") { history.reload() }
                }
            }
            else -> HistoryChart(
                series = series,
                startMs = start,
                endMs = end,
                format = format,
                yMin = yMin,
                yMax = yMax,
                guides = guides,
                gapMs = gap,
                modifier = Modifier.semantics { contentDescription = "$title, last ${range.label}" },
            )
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = hsm.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ChartMessage(height: Dp, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

private fun nearest(points: List<Point>, t: Long): Point? {
    if (points.isEmpty()) return null
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < t) lo = mid + 1 else hi = mid
    }
    val a = points[lo]
    val b = points.getOrNull(lo - 1)
    return if (b != null && abs(b.first - t) < abs(a.first - t)) b else a
}

private fun axisLabel(ms: Long, spanMs: Long): String =
    if (spanMs > 26 * 3_600_000L) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Time-series line chart over a fixed window [startMs]..[endMs]. Tap or drag sideways to read
 * values (vertical drags keep scrolling the page). Holes longer than [gapMs] break the line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryChart(
    series: List<Series>,
    startMs: Long,
    endMs: Long,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    guides: List<Guide> = emptyList(),
    gapMs: Long = 10 * 60_000L,
) {
    val valid = remember(series) { series.map { s -> s.points.filter { it.second != null }.sortedBy { it.first } } }
    val values = valid.flatMap { pts -> pts.map { it.second!! } }
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No data for this period", color = hsm.muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val vMin = values.min()
    val vMax = values.max()
    var lo = yMin ?: min(vMin, guides.minOfOrNull { it.value } ?: vMin)
    var hi = yMax ?: max(vMax, guides.maxOfOrNull { it.value } ?: vMax)
    val span0 = hi - lo
    val pad = if (span0 < 1e-9) max(1.0, abs(hi) * 0.1) else span0 * 0.08
    if (yMin == null) lo -= pad
    if (yMax == null) hi += pad
    if (hi - lo < 1e-9) hi = lo + 1
    val t0 = startMs
    val t1 = max(endMs, t0 + 1)

    var scrub by remember { mutableStateOf<Float?>(null) }
    val grid = hsm.border
    val fg = hsm.fg
    val ring = hsm.surface
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(
                Modifier.fillMaxWidth().height(height)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { p ->
                            scrub = (p.x / size.width).coerceIn(0f, 1f)
                            tryAwaitRelease()
                            scrub = null
                        })
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { c, _ -> scrub = (c.position.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { scrub = null },
                            onDragCancel = { scrub = null },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                val top = 6.dp.toPx()
                val plotH = h - top
                fun x(t: Long) = ((t - t0).toDouble() / (t1 - t0).toDouble() * w).toFloat()
                fun y(v: Double) = (top + plotH - ((v - lo) / (hi - lo)).toFloat() * plotH).coerceIn(0f, h)
                for (i in 0..2) {
                    val gy = top + plotH * i / 2f
                    drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }
                for (g in guides) {
                    if (g.value < lo || g.value > hi) continue
                    val gy = y(g.value)
                    drawLine(g.color.copy(alpha = 0.7f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
                val stroke = 2.dp.toPx()
                for ((si, s) in series.withIndex()) {
                    val pts = valid[si]
                    val path = Path()
                    val area = Path()
                    var segStart = -1
                    var segLen = 0
                    var lastT = 0L
                    var firstX = 0f
                    var lastX = 0f
                    var lastY = 0f
                    fun endSegment() {
                        if (segLen == 1) drawCircle(s.color, radius = stroke, center = Offset(lastX, lastY))
                        if (series.size == 1 && segLen > 1) {
                            area.lineTo(lastX, h)
                            area.lineTo(firstX, h)
                            area.close()
                        }
                        segLen = 0
                    }
                    for ((idx, p) in pts.withIndex()) {
                        val (t, v) = p
                        val px = x(t)
                        val py = y(v!!)
                        if (segLen > 0 && t - lastT > gapMs) endSegment()
                        if (segLen == 0) {
                            path.moveTo(px, py)
                            if (series.size == 1) {
                                area.moveTo(px, h)
                                area.lineTo(px, py)
                            }
                            firstX = px
                            segStart = idx
                        } else {
                            path.lineTo(px, py)
                            if (series.size == 1) area.lineTo(px, py)
                        }
                        segLen++
                        lastT = t
                        lastX = px
                        lastY = py
                    }
                    if (segStart >= 0) endSegment()
                    if (series.size == 1) {
                        drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.26f), s.color.copy(alpha = 0.02f))))
                    }
                    drawPath(path, s.color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                scrub?.let { frac ->
                    val cx = frac * w
                    drawLine(fg.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.dp.toPx())
                    val tAt = t0 + (frac * (t1 - t0)).toLong()
                    for ((si, s) in series.withIndex()) {
                        val n = nearest(valid[si], tAt) ?: continue
                        if (abs(n.first - tAt) > gapMs) continue
                        val c = Offset(x(n.first), y(n.second!!))
                        drawCircle(ring, radius = 6.dp.toPx(), center = c)
                        drawCircle(s.color, radius = 4.dp.toPx(), center = c)
                    }
                }
            }
            Text(format(hi), style = labelStyle, color = hsm.muted, modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 2.dp))
            Text(format(lo), style = labelStyle, color = hsm.muted, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 2.dp, start = 2.dp))
        }
        // x axis
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(axisLabel(t0, t1 - t0), style = labelStyle, color = hsm.faint)
            Spacer(Modifier.weight(1f))
            Text(axisLabel(t0 + (t1 - t0) / 2, t1 - t0), style = labelStyle, color = hsm.faint)
            Spacer(Modifier.weight(1f))
            Text(axisLabel(t1, t1 - t0), style = labelStyle, color = hsm.faint)
        }
        // readout / legend
        val sx = scrub
        val tAt = sx?.let { t0 + (it * (t1 - t0)).toLong() }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (tAt != null) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(tAt)) else "Latest",
                style = MaterialTheme.typography.labelMedium,
                color = hsm.muted,
            )
            for ((si, s) in series.withIndex()) {
                val pts = valid[si]
                val v = if (tAt != null) nearest(pts, tAt)?.takeIf { abs(it.first - tAt) <= gapMs }?.second else pts.lastOrNull()?.second
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(s.color))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${s.label} ${v?.let(format) ?: DASH}",
                        style = MaterialTheme.typography.labelMedium,
                        color = hsm.fg,
                    )
                }
            }
        }
    }
}
