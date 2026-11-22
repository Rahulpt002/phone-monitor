package com.hotspotmonitor.dashboard.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.StoredEvent
import com.hotspotmonitor.dashboard.data.formatDateTime
import com.hotspotmonitor.dashboard.data.formatTime
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.Loading
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.describeCommand
import com.hotspotmonitor.dashboard.ui.components.prettyJson
import com.hotspotmonitor.dashboard.ui.components.severityTone
import com.hotspotmonitor.dashboard.ui.components.toneColor
import com.hotspotmonitor.dashboard.ui.controls.ActionButton
import com.hotspotmonitor.dashboard.ui.controls.BtnStyle
import com.hotspotmonitor.dashboard.ui.controls.COMMAND_SOURCES
import com.hotspotmonitor.dashboard.ui.controls.Dropdown
import com.hotspotmonitor.dashboard.ui.controls.OutputBlock
import com.hotspotmonitor.dashboard.ui.controls.STATUS_LABELS
import com.hotspotmonitor.dashboard.ui.controls.StatusChip
import com.hotspotmonitor.dashboard.ui.controls.durationText
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// "Load older" paging merged with the live store, and the day-grouped timelines of the Events and
// Commands tabs (port of web usePagedList + System.tsx EventsTab/AuditTab).

/**
 * A REST list paged with `before=<oldest time>`. Older pages are kept here (the store caps its
 * lists), and [merged] adds the live items the socket delivers, newest first.
 */
@Stable
internal class PagedList<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val keyOf: (T) -> String,
    private val timeOf: (T) -> String?,
) {
    var pages by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var epoch = 0

    fun reset(fetch: suspend (before: String?) -> List<T>) {
        job?.cancel()
        val e = ++epoch
        pages = emptyList()
        loaded = false
        hasMore = false
        error = null
        job = scope.launch { load(e, null, fetch) }
    }

    fun loadMore(before: String?, fetch: suspend (before: String?) -> List<T>) {
        if (loading || before == null) return
        val e = epoch
        job = scope.launch { load(e, before, fetch) }
    }

    private suspend fun load(e: Int, before: String?, fetch: suspend (before: String?) -> List<T>) {
        loading = true
        error = null
        try {
            val list = fetch(before)
            if (e != epoch) return
            pages = if (before == null) list else pages + list
            hasMore = list.size >= pageSize
            loaded = true
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            if (e == epoch) error = x.userMessage()
        } finally {
            if (e == epoch) loading = false
        }
    }

    fun merged(live: List<T>): List<T> {
        val map = LinkedHashMap<String, T>()
        for (x in pages) map[keyOf(x)] = x
        for (x in live) map[keyOf(x)] = x
        return map.values.sortedByDescending { isoMs(timeOf(it)) ?: Long.MIN_VALUE }
    }
}

/** A run of rows from one day; only the first chunk of a day carries the day label. */
internal class DayChunk<T>(val key: String, val label: String?, val items: List<T>)

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DAY_FMT)
}

/** Groups a newest-first list by local day, in chunks of at most [chunk] rows (one lazy item each). */
internal fun <T> chunkByDay(list: List<T>, timeOf: (T) -> String?, chunk: Int = 30): List<DayChunk<T>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val out = ArrayList<DayChunk<T>>()
    var curDay: LocalDate? = null
    var cur = ArrayList<T>()
    var part = 0
    fun flush() {
        if (cur.isEmpty()) return
        val d = curDay
        out += DayChunk("${d ?: "unknown"}#$part", if (part == 0) (d?.let { dayLabel(it, today) } ?: "Unknown time") else null, cur)
        cur = ArrayList()
        part++
    }
    var started = false
    for (x in list) {
        val d = isoMs(timeOf(x))?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (!started) {
            curDay = d
            started = true
        } else if (d != curDay) {
            flush()
            curDay = d
            part = 0
        }
        cur += x
        if (cur.size >= chunk) flush()
    }
    flush()
    return out
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = hsm.muted,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun <T> ChunkCard(chunk: DayChunk<T>, row: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chunk.label != null) DayHeader(chunk.label)
        SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
            chunk.items.forEachIndexed { i, x ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                row(x)
            }
        }
    }
}

@Composable
private fun LoadMoreRow(list: PagedList<*>, endText: String, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        if (list.hasMore) {
            ActionButton("Load older", style = BtnStyle.SECONDARY, busy = list.loading, onClick = onMore)
        } else {
            Text(if (list.loading) "Loading…" else endText, style = MaterialTheme.typography.bodySmall, color = hsm.faint)
        }
    }
}

// --- events -------------------------------------------------------------------------------------

internal const val EVENTS_PAGE = 100

internal val EVENT_KINDS: List<String> = EventKind.entries.map { it.wire }

private val SEVERITIES = listOf("" to "All", "info" to "Info", "warning" to "Warning", "critical" to "Critical")

@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.eventsTab(
    list: PagedList<StoredEvent>,
    live: List<StoredEvent>,
    kind: String,
    severity: String,
    onKind: (String) -> Unit,
    onSeverity: (String) -> Unit,
    onMore: (before: String?) -> Unit,
) {
    val all = list.merged(live)
    val shown = if (severity.isEmpty()) all else all.filter { it.severity == severity }
    item(key = "ev_filters") {
        SectionCard(title = "Event timeline") {
            Dropdown(
                label = "Kind",
                value = kind,
                options = listOf("" to "All events") + EVENT_KINDS.map { it to titleCase(it) },
                onChange = onKind,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SEVERITIES.forEachIndexed { i, (v, l) ->
                    SegmentedButton(
                        selected = severity == v,
                        onClick = { onSeverity(v) },
                        shape = SegmentedButtonDefaults.itemShape(i, SEVERITIES.size),
                        icon = {},
                    ) { Text(l, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${shown.size} event${if (shown.size == 1) "" else "s"} loaded" + if (severity.isNotEmpty()) " (${titleCase(severity)} only)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.faint,
            )
        }
    }
    list.error?.let { item(key = "ev_err") { Notice(Tone.DANGER, "Couldn't load events", it) } }
    when {
        shown.isEmpty() && list.loading -> item(key = "ev_loading") { Loading() }
        shown.isEmpty() -> item(key = "ev_empty") {
            EmptyState(if (kind.isNotEmpty() || severity.isNotEmpty()) "No events match these filters." else "No events yet.")
        }
        else -> items(chunkByDay(shown, { it.ts }), key = { "ev_" + it.key }) { chunk ->
            ChunkCard(chunk) { e -> TimelineEventRow(e) }
        }
    }
    if (list.loaded || shown.isNotEmpty()) {
        item(key = "ev_more") { LoadMoreRow(list, "Start of the timeline") { onMore(all.lastOrNull()?.ts) } }
    }
}

@Composable
private fun TimelineEventRow(e: StoredEvent) {
    var open by remember(e.id) { mutableStateOf(false) }
    val hasData = e.data?.isNotEmpty() == true
    val tone = severityTone(e.severity)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasData) { open = !open }
                .padding(vertical = 10.dp),
        ) {
            Text(formatTime(e.ts), style = MaterialTheme.typography.labelMedium, color = hsm.muted, modifier = Modifier.width(56.dp).padding(top = 1.dp))
            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(toneColor(tone)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleCase(e.kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (tone == Tone.INFO || tone == Tone.NEUTRAL) hsm.muted else toneColor(tone),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (e.severity == "warning" || e.severity == "critical") Pill(titleCase(e.severity), tone)
                }
                Text(e.message, style = MaterialTheme.typography.bodyMedium, color = hsm.fg, modifier = Modifier.padding(top = 2.dp))
                if (hasData) {
                    Text(
                        if (open) "Hide details" else "Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = hsm.accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (hasData && open) {
            OutputBlock(formatDateTime(e.ts) + "\n" + prettyJson(e.data!!), maxHeight = 320.dp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}

// --- command log ---------------------------------------------------------------------------------

internal const val LOG_PAGE = 50

internal class LogFilter(val name: String, val status: String, val source: String) {
    fun accept(c: CommandRecord) = (name.isEmpty() || c.name == name) && (status.isEmpty() || c.status == status) && (source.isEmpty() || c.source == source)
    val active get() = name.isNotEmpty() || status.isNotEmpty() || source.isNotEmpty()
}

internal fun LazyListScope.commandsTab(
    list: PagedList<CommandRecord>,
    live: List<CommandRecord>,
    filter: LogFilter,
    onName: (String) -> Unit,
    onStatus: (String) -> Unit,
    onSource: (String) -> Unit,
    onMore: (before: String?) -> Unit,
) {
    val all = list.merged(live)
    val shown = all.filter(filter::accept)
    item(key = "log_filters") {
        SectionCard(title = "Command log") {
            Dropdown(
                label = "Command",
                value = filter.name,
                options = listOf("" to "All commands") + CommandNames.ALL.map { it to (COMMANDS[it]?.label ?: it) },
                onChange = onName,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dropdown(
                    label = "Status",
                    value = filter.status,
                    options = listOf("" to "Any status") + STATUS_LABELS.entries.map { it.key to it.value },
                    onChange = onStatus,
                    modifier = Modifier.weight(1f),
                )
                Dropdown(
                    label = "Source",
                    value = filter.source,
                    options = listOf("" to "Any source") + COMMAND_SOURCES.map { it to titleCase(it) },
                    onChange = onSource,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${shown.size} of ${all.size} loaded commands",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.faint,
            )
        }
    }
    list.error?.let { item(key = "log_err") { Notice(Tone.DANGER, "Couldn't load the command log", it) } }
    when {
        shown.isEmpty() && list.loading -> item(key = "log_loading") { Loading() }
        shown.isEmpty() -> item(key = "log_empty") {
            EmptyState(if (filter.active) "No loaded commands match. Load older ones to search further." else "No commands yet.")
        }
        else -> items(chunkByDay(shown, { it.createdAt }), key = { "log_" + it.key }) { chunk ->
            ChunkCard(chunk) { c -> CommandLogRow(c) }
        }
    }
    if (list.loaded || shown.isNotEmpty()) {
        item(key = "log_more") { LoadMoreRow(list, "End of the log") { onMore(all.lastOrNull()?.createdAt) } }
    }
}

@Composable
private fun CommandLogRow(c: CommandRecord) {
    var open by remember(c.id) { mutableStateOf(false) }
    val text = c.error?.takeIf { it.isNotBlank() }?.let { err -> if (!c.output.isNullOrBlank()) "$err\n\n${c.output}" else err }
        ?: c.output?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = text != null) { open = !open }
                .padding(vertical = 10.dp),
        ) {
            Text(formatTime(c.createdAt), style = MaterialTheme.typography.labelMedium, color = hsm.muted, modifier = Modifier.width(56.dp).padding(top = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(describeCommand(c.name, c.args), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = hsm.fg)
                Text(
                    listOfNotNull("by ${c.user ?: titleCase(c.source)}", durationText(c), if (c.revertConfirm != null) "auto-revert" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
                if (!c.error.isNullOrBlank() && !open) {
                    Text(c.error, style = MaterialTheme.typography.bodySmall, color = hsm.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (text != null) {
                    Text(
                        if (open) "Hide output" else "Show output",
                        style = MaterialTheme.typography.labelMedium,
                        color = hsm.accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(c)
        }
        if (open && text != null) {
            OutputBlock(text, isError = !c.error.isNullOrBlank(), maxHeight = 320.dp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}
