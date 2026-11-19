package com.hotspotmonitor.agent.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/** A titled card section. */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** "Label ........ value" row; the value wraps under long labels instead of being cut. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** A small coloured label, e.g. the link state. */
@Composable
fun StatusPill(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .widthIn(min = 24.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
    )
}

/** A short explanatory paragraph. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Wall-clock milliseconds, updated every [periodMs] so "3 min ago" texts stay current. */
@Composable
fun rememberNowMs(periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** "45 s", "12 min", "3 h 5 min", "2 d 4 h". */
fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3_600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
        else -> "${s / 86_400} d ${(s % 86_400) / 3_600} h"
    }
}

fun formatAgo(epochMs: Long, nowMs: Long): String =
    if (epochMs <= 0) "-" else formatDuration((nowMs - epochMs) / 1000) + " ago"

fun formatNumber(value: Double?, decimals: Int = 1, unit: String = ""): String =
    if (value == null || value.isNaN()) "-" else String.format(Locale.US, "%.${decimals}f", value) + unit

fun yesNo(value: Boolean?, yes: String = "yes", no: String = "no"): String = when (value) {
    true -> yes
    false -> no
    null -> "-"
}

fun toast(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
}
