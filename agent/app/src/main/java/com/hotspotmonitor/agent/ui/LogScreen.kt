package com.hotspotmonitor.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.LogLevel
import com.hotspotmonitor.agent.core.LogLine

/** The in-memory log (last 1000 lines) with a text filter, a minimum level and auto-scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(graph: AgentGraph) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val lines by graph.log.lines.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var minLevelName by rememberSaveable { mutableStateOf(LogLevel.INFO.name) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val minLevel = LogLevel.entries.firstOrNull { it.name == minLevelName } ?: LogLevel.INFO

    val filtered = remember(lines, query, minLevel) { filterLines(lines, query, minLevel) }
    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, filtered.lastOrNull(), autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter (tag or text)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = level == minLevel,
                    onClick = { minLevelName = level.name },
                    label = { Text(level.letter.toString() + "+") },
                )
            }
            HintText("${filtered.size}/${lines.size}", Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            Text("Auto-scroll", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
            TextButton(onClick = { LogExport.share(context, graph) }) { Text("Export") }
            TextButton(onClick = { graph.log.clearMemory() }) { Text("Clear") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(filtered) { line ->
                Text(
                    line.format(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.level) {
                        LogLevel.ERROR -> colors.bad
                        LogLevel.WARN -> colors.warn
                        LogLevel.DEBUG -> colors.muted
                        LogLevel.INFO -> Color.Unspecified
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private fun filterLines(lines: List<LogLine>, query: String, minLevel: LogLevel): List<LogLine> {
    val q = query.trim()
    return lines.filter { line ->
        line.level.ordinal >= minLevel.ordinal &&
            (q.isEmpty() || line.tag.contains(q, ignoreCase = true) || line.message.contains(q, ignoreCase = true))
    }
}
