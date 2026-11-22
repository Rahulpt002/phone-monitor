package com.hotspotmonitor.dashboard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.delay

/** Current time, ticking every [intervalMs] (for "x s ago" texts and countdowns). */
@Composable
fun rememberNow(intervalMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

enum class Tone { OK, WARN, DANGER, INFO, NEUTRAL }

@Composable
fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> hsm.ok
    Tone.WARN -> hsm.warn
    Tone.DANGER -> hsm.danger
    Tone.INFO -> hsm.info
    Tone.NEUTRAL -> hsm.muted
}

@Composable
fun toneSoft(t: Tone): Color = when (t) {
    Tone.OK -> hsm.okSoft
    Tone.WARN -> hsm.warnSoft
    Tone.DANGER -> hsm.dangerSoft
    Tone.INFO -> hsm.infoSoft
    Tone.NEUTRAL -> hsm.surface3
}

/** Rounded card with an optional small uppercase title and a trailing action. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    stale: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = hsm.surface, contentColor = hsm.fg),
        border = BorderStroke(1.dp, if (stale) hsm.warn.copy(alpha = 0.5f) else hsm.border),
    ) {
        Column(Modifier.padding(contentPadding)) {
            if (title != null || action != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = hsm.muted,
                            modifier = Modifier.weight(1f),
                        )
                    } else Spacer(Modifier.weight(1f))
                    if (stale) Pill("Stale", Tone.WARN)
                    action?.invoke()
                }
            }
            content()
        }
    }
}

/** A label above a big value, with an optional small line below. */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = Color.Unspecified,
    size: TextUnit = 24.sp,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = hsm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            color = if (valueColor == Color.Unspecified) hsm.fg else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
    }
}

/** Two-column label/value list. */
@Composable
fun KeyValues(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, v) in items) KeyValueRow(k, v)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = Color.Unspecified, trailing: (@Composable () -> Unit)? = null) {
    // Label left, value right-aligned. The label keeps its natural width up to 40% of the row, so a
    // long value (a sysfs path, an IPv6 address) wraps instead of squeezing the label to nothing.
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
            Box(contentAlignment = Alignment.CenterEnd) {
                if (trailing != null) trailing() else Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (valueColor == Color.Unspecified) hsm.fg else valueColor,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { (labelM, valueM), c ->
        val w = c.maxWidth
        val gap = 12.dp.roundToPx()
        val labelKeep = min(labelM.maxIntrinsicWidth(c.maxHeight), (w * 0.4f).toInt())
        val v = valueM.measure(Constraints(maxWidth = (w - labelKeep - gap).coerceAtLeast(0)))
        val l = labelM.measure(Constraints(maxWidth = (w - v.width - gap).coerceAtLeast(labelKeep)))
        val h = max(l.height, v.height)
        layout(w, h) {
            l.placeRelative(0, (h - l.height) / 2)
            v.placeRelative(w - v.width, (h - v.height) / 2)
        }
    }
}

/** Small rounded label with a tone (Online, Stale, Live ...). */
@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(toneSoft(tone)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(toneColor(tone)))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = toneColor(tone), maxLines = 1)
    }
}

private val GRADE_LABEL = mapOf(Grade.EXCELLENT to "Excellent", Grade.GOOD to "Good", Grade.FAIR to "Fair", Grade.POOR to "Poor")
private val GRADE_ICON = mapOf(Grade.EXCELLENT to "▲", Grade.GOOD to "▲", Grade.FAIR to "■", Grade.POOR to "▼")

fun gradeLabel(g: Grade?): String = g?.let { GRADE_LABEL[it] } ?: "No data"

/** Grade as coloured text with a shape glyph (never colour alone). */
@Composable
fun GradeChip(g: Grade?, modifier: Modifier = Modifier) {
    val c = hsm.grade(g)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${g?.let { GRADE_ICON[it] } ?: "·"} ${gradeLabel(g)}", style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/** Thin progress bar 0..100. */
@Composable
fun ProgressBar(percent: Double?, tone: Tone = Tone.INFO, modifier: Modifier = Modifier, color: Color? = null) {
    val v = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val anim by animateFloatAsState(v, tween(500), label = "progress")
    LinearProgressIndicator(
        progress = { anim },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
        color = color ?: toneColor(tone),
        trackColor = hsm.surface3,
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
    )
}

/** Circular gauge (battery, score) with a centred label. */
@Composable
fun RingGauge(
    fraction: Double?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    stroke: Dp = 10.dp,
    center: @Composable () -> Unit,
) {
    val target = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    val anim by animateFloatAsState(target, tween(700), label = "ring")
    val track = hsm.surface3
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val d = this.size.minDimension - s
            val tl = Offset((this.size.width - d) / 2, (this.size.height - d) / 2)
            drawArc(track, 135f, 270f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            if (fraction != null) drawArc(color, 135f, 270f * anim, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        center()
    }
}

/** Notice banner (warning, error, info) with an optional action. */
@Composable
fun Notice(tone: Tone, title: String?, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val icon: ImageVector = when (tone) {
        Tone.DANGER -> Icons.Outlined.ErrorOutline
        Tone.WARN -> Icons.Outlined.WarningAmber
        else -> Icons.Outlined.Info
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(toneSoft(tone)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = toneColor(tone), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = hsm.fg.copy(alpha = 0.85f))
            if (action != null) Box(Modifier.padding(top = 6.dp)) { action() }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
    }
}

@Composable
fun Loading(label: String = "Loading…", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = hsm.accent)
        Spacer(Modifier.width(12.dp))
        Text(label, color = hsm.muted)
    }
}

/** "Updated 12 s ago", turning amber when older than [staleAfterMs]. */
@Composable
fun UpdatedAt(iso: String?, staleAfterMs: Long = 3 * 60_000) {
    val now = rememberNow(5000)
    val ms = isoMs(iso)
    val stale = ms != null && now - ms > staleAfterMs
    Text(
        if (ms == null) "No reading yet" else "Updated ${formatRelative(iso, now)}",
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) hsm.warn else hsm.faint,
    )
}

/** True when the newest of [isos] is older than [afterMs] (or missing). */
@Composable
fun isStale(vararg isos: String?, afterMs: Long = 3 * 60_000): Boolean {
    val now = rememberNow(10_000)
    val newest = isos.mapNotNull { isoMs(it) }.maxOrNull() ?: return false
    return now - newest > afterMs
}

/**
 * Standard page: big title, optional subtitle and actions, pull-to-refresh, padded lazy column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "__header") {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = hsm.fg)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                    }
                    if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
            content()
        }
    }
    if (onRefresh != null) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { body() }
    } else body()
}

/** Text button styled as a small link ("Details"). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = hsm.accent)
    }
}

/** Equal-width row of children. */
@Composable
fun EqualRow(modifier: Modifier = Modifier, spacing: Dp = 12.dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), content = content)
}
