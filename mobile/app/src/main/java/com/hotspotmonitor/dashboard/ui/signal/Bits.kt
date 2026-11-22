package com.hotspotmonitor.dashboard.ui.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.DeviceSnapshot
import com.hotspotmonitor.dashboard.data.formatRelativeMs
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.max

// Small building blocks shared by the Signal, Network and Power screens.

private val FAST_GROUPS = setOf("radio", "net")

/**
 * Web useStaleness: a hint when the newest reading of [groups] is older than its sending tier
 * allows (plan 8.2), or when the phone is offline. Null when fresh or never read.
 */
@Composable
fun staleHint(snap: DeviceSnapshot?, settings: Settings, vararg groups: String): String? {
    val now = rememberNow(5000)
    if (snap == null) return null
    val newest = groups.mapNotNull { isoMs(snap.stateTs[it]) }.maxOrNull() ?: return null
    val fast = groups.any { it in FAST_GROUPS }
    val limit = if (fast) max(75_000L, settings.telemetry.fastActiveS * 3000L) else max(150_000L, settings.telemetry.slowS * 2500L)
    if (!snap.online) return "Phone offline · last reading ${formatRelativeMs(newest, now)}"
    if (now - newest > limit) return "Stale · last reading ${formatRelativeMs(newest, now)}"
    return null
}

/** Amber line under a card's headline when its readings are old. */
@Composable
fun StaleLine(hint: String?) {
    if (hint == null) return
    Text(hint, style = MaterialTheme.typography.bodySmall, color = hsm.warn, modifier = Modifier.padding(top = 8.dp))
}

/** Yes/No (or On/Off) as a pill; a dash when unknown. */
@Composable
fun YesNoPill(value: Boolean?, yes: String = "Yes", no: String = "No", noTone: Tone = Tone.NEUTRAL) {
    when (value) {
        null -> Text(DASH, color = hsm.faint, style = MaterialTheme.typography.bodyMedium)
        true -> Pill(yes, Tone.OK)
        false -> Pill(no, noTone)
    }
}

/** Small tile: label over a value (serving-cell facts, temperatures). */
@Composable
fun FactTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
    sub: String? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(hsm.surface2).padding(horizontal = 10.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = hsm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (valueColor == Color.Unspecified) hsm.fg else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = hsm.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Grid of [FactTile]s, [columns] per row. */
@Composable
fun FactGrid(items: List<Pair<String, String>>, columns: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, v) in row) FactTile(k, v, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One coloured span of a [ZoneScale]. */
data class Zone(val from: Double, val to: Double, val color: Color)

/**
 * A meter split into coloured zones (grade bands, temperature limits) with a marker at [value].
 * The zone holding the value is drawn solid; the others are tinted. [ticks] are labelled below.
 */
@Composable
fun ZoneScale(
    value: Double?,
    min: Double,
    max: Double,
    zones: List<Zone>,
    modifier: Modifier = Modifier,
    ticks: List<Double> = emptyList(),
    tickLabel: (Double) -> String = { trimNumber(it, 0) },
    barHeight: Dp = 10.dp,
    /** Zone to highlight when the caller grades the value itself (boundary rules differ per metric). */
    activeZone: Int? = null,
) {
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontSize = 10.sp, color = hsm.faint)
    val fg = hsm.fg
    val ring = hsm.surface
    val hasTicks = ticks.isNotEmpty()
    Canvas(modifier.fillMaxWidth().height(barHeight + 8.dp + if (hasTicks) 14.dp else 0.dp)) {
        val w = size.width
        val bh = barHeight.toPx()
        val top = 4.dp.toPx()
        val gap = 2.dp.toPx()
        fun fx(v: Double) = (((v - min) / (max - min)).coerceIn(0.0, 1.0) * w).toFloat()
        val active = activeZone ?: value?.let { v ->
            zones.indexOfFirst { v >= it.from && v < it.to }.takeIf { it >= 0 } ?: if (v >= max) zones.lastIndex else 0
        }
        zones.forEachIndexed { i, z ->
            val x0 = fx(z.from) + if (i > 0) gap / 2 else 0f
            val x1 = fx(z.to) - if (i < zones.lastIndex) gap / 2 else 0f
            if (x1 <= x0) return@forEachIndexed
            drawRoundRect(
                color = if (i == active) z.color else z.color.copy(alpha = 0.22f),
                topLeft = Offset(x0, top),
                size = Size(x1 - x0, bh),
                cornerRadius = CornerRadius(bh / 2, bh / 2),
            )
        }
        if (value != null) {
            val c = Offset(fx(value).coerceIn(bh / 2, w - bh / 2), top + bh / 2)
            drawCircle(ring, radius = bh * 0.95f, center = c)
            drawCircle(fg, radius = bh * 0.65f, center = c)
        }
        for (t in ticks) {
            val layout = measurer.measure(tickLabel(t), tickStyle)
            val tx = (fx(t) - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
            drawText(layout, topLeft = Offset(tx, top + bh + 3.dp.toPx()))
        }
    }
}

/** Zones for a graded metric; [lines] are its boundaries best to worst (THRESHOLD_TABLE lines). */
@Composable
fun gradeZones(min: Double, max: Double, lines: List<Double>): List<Zone> = listOf(
    Zone(min, lines[2], hsm.grade(Grade.POOR)),
    Zone(lines[2], lines[1], hsm.grade(Grade.FAIR)),
    Zone(lines[1], lines[0], hsm.grade(Grade.GOOD)),
    Zone(lines[0], max, hsm.grade(Grade.EXCELLENT)),
)

/** Android's 0..4 bars as a little bar glyph. */
@Composable
fun SignalBars(level: Int?, color: Color, modifier: Modifier = Modifier) {
    val off = hsm.surface3
    Canvas(modifier.size(width = 26.dp, height = 20.dp).semantics { contentDescription = "${level ?: "unknown"} of 4 bars" }) {
        val bw = size.width / 4f * 0.7f
        val step = size.width / 4f
        for (i in 0 until 4) {
            val bhgt = size.height * (0.35f + 0.65f * (i + 1) / 4f)
            drawRoundRect(
                color = if (level != null && i < level) color else off,
                topLeft = Offset(i * step, size.height - bhgt),
                size = Size(bw, bhgt),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

/** Uppercase small caption used inside cards. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = hsm.muted, letterSpacing = 0.6.sp, modifier = modifier)
}

/** Value with a muted unit after it, baseline aligned. */
@Composable
fun ValueUnit(value: String, unit: String, color: Color, size: Int = 28) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        if (unit.isNotEmpty() && value != DASH) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = hsm.muted, modifier = Modifier.padding(bottom = (size / 7).dp))
        }
    }
}

/** Thin divider-like gap. */
@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

/** Box helper for an even row. */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center) { content() }
