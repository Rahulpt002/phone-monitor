package com.hotspotmonitor.dashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.formatTimeMs
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One line on a chart: [points] are (unix ms, value or null for a gap). */
data class Series(val label: String, val color: Color, val points: List<Pair<Long, Double?>>, val unit: String = "")

/** Horizontal guide line (grade thresholds). */
data class Guide(val value: Double, val color: Color)

/**
 * Time-series line chart. Drag or tap to read values at a time. Gaps (null values, or holes longer
 * than [gapMs]) break the line instead of joining distant points.
 */
@Composable
fun TimeChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    guides: List<Guide> = emptyList(),
    gapMs: Long = 10 * 60_000,
    fill: Boolean = true,
    digits: Int = 1,
) {
    val all = series.flatMap { s -> s.points.mapNotNull { it.second } }
    val times = series.flatMap { s -> s.points.map { it.first } }
    if (all.isEmpty() || times.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No data for this period", color = hsm.muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val lo0 = yMin ?: min(all.minOrNull()!!, guides.minOfOrNull { it.value } ?: Double.MAX_VALUE)
    val hi0 = yMax ?: max(all.maxOrNull()!!, guides.maxOfOrNull { it.value } ?: -Double.MAX_VALUE)
    val pad = if (hi0 - lo0 < 1e-9) 1.0 else (hi0 - lo0) * 0.08
    val lo = if (yMin != null) yMin else lo0 - pad
    val hi = if (yMax != null) yMax else hi0 + pad
    val t0 = times.minOrNull()!!
    val t1 = max(times.maxOrNull()!!, t0 + 1)

    var scrub by remember(series) { mutableStateOf<Float?>(null) }
    val grid = hsm.border
    val muted = hsm.muted
    val fg = hsm.fg

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(
                Modifier.fillMaxWidth().height(height)
                    .pointerInput(series) {
                        detectTapGestures(onPress = { p ->
                            scrub = (p.x / size.width).coerceIn(0f, 1f)
                            tryAwaitRelease()
                            scrub = null
                        })
                    }
                    .pointerInput(series) {
                        // Sideways only, so a vertical swipe that starts on a chart still scrolls the page.
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
                fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0).toFloat()) * w
                fun y(v: Double) = (h - ((v - lo) / (hi - lo)).toFloat() * h).coerceIn(0f, h)
                // grid
                for (i in 0..3) {
                    val gy = h * i / 3f
                    drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }
                for (g in guides) {
                    val gy = y(g.value)
                    drawLine(g.color.copy(alpha = 0.55f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                }
                for (s in series) {
                    val path = Path()
                    val area = Path()
                    var started = false
                    var lastT = 0L
                    var firstX = 0f
                    var lastX = 0f
                    fun closeArea() {
                        if (fill && started) {
                            area.lineTo(lastX, h)
                            area.lineTo(firstX, h)
                            area.close()
                        }
                    }
                    for ((t, v) in s.points.sortedBy { it.first }) {
                        if (v == null || (started && t - lastT > gapMs)) {
                            closeArea()
                            started = false
                            if (v == null) continue
                        }
                        val px = x(t)
                        val py = y(v)
                        if (!started) {
                            path.moveTo(px, py)
                            if (fill) {
                                area.moveTo(px, h)
                                area.lineTo(px, py)
                            }
                            firstX = px
                            started = true
                        } else {
                            path.lineTo(px, py)
                            if (fill) area.lineTo(px, py)
                        }
                        lastX = px
                        lastT = t
                    }
                    closeArea()
                    if (fill && series.size == 1) {
                        drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0.02f))))
                    }
                    drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                scrub?.let { frac ->
                    val cx = frac * w
                    drawLine(fg.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                    val tAt = t0 + (frac * (t1 - t0)).toLong()
                    for (s in series) {
                        val near = s.points.filter { it.second != null }.minByOrNull { abs(it.first - tAt) } ?: continue
                        drawCircle(s.color, radius = 5.dp.toPx(), center = Offset(x(near.first), y(near.second!!)))
                    }
                }
            }
            // y labels
            Text(trimNumber(hi, digits), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.align(Alignment.TopStart))
            Text(trimNumber(lo, digits), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.align(Alignment.BottomStart))
        }
        // readout / legend
        val sx = scrub
        val tAt = sx?.let { t0 + (it * (t1 - t0)).toLong() }
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sx != null) {
                Text(formatTimeMs(tAt!!), style = MaterialTheme.typography.labelMedium, color = hsm.muted)
            } else {
                Text(formatTimeMs(t0), style = MaterialTheme.typography.labelSmall, color = muted)
                Spacer(Modifier.weight(1f))
            }
            for (s in series) {
                val value = if (tAt != null) {
                    s.points.filter { it.second != null }.minByOrNull { abs(it.first - tAt) }?.second
                } else s.points.lastOrNull { it.second != null }?.second
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${s.label} ${value?.let { trimNumber(it, digits) + s.unit } ?: DASH}",
                        style = MaterialTheme.typography.labelMedium,
                        color = hsm.fg,
                    )
                }
            }
            if (sx == null) {
                Spacer(Modifier.weight(1f))
                Text(formatTimeMs(t1), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/** Tiny line for stat tiles (no axes). */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.width(80.dp).height(28.dp)) {
    if (values.size < 2) return
    val lo = values.minOrNull()!!
    val hi = values.maxOrNull()!!
    val span = if (hi - lo < 1e-9) 1.0 else hi - lo
    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val px = i * step
            val py = size.height - ((v - lo) / span).toFloat() * size.height
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
