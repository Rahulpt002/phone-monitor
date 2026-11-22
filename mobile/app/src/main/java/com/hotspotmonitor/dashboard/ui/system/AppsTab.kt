package com.hotspotmonitor.dashboard.ui.system

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.dashboard.data.AppJson
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.commandLabel
import com.hotspotmonitor.dashboard.ui.components.commandStatus
import com.hotspotmonitor.dashboard.ui.controls.ActionButton
import com.hotspotmonitor.dashboard.ui.controls.BtnStyle
import com.hotspotmonitor.dashboard.ui.controls.ChipRow
import com.hotspotmonitor.dashboard.ui.controls.CommandRunner
import com.hotspotmonitor.dashboard.ui.controls.LastResult
import com.hotspotmonitor.dashboard.ui.controls.isInFlight
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Apps tab of System: the installed-app list from the last app_list command, with search, filters
// and force stop / disable / enable (web System.tsx AppsTab + lib/apps.ts).

internal data class AppRow(val pkg: String, val enabled: Boolean, val system: Boolean, val label: String?)

/** Parses the app_list output: a JSON array of { pkg, enabled, system, label? }. */
internal fun parseAppList(output: String?): List<AppRow>? {
    if (output.isNullOrBlank()) return null
    val arr = runCatching { AppJson.parseToJsonElement(output) }.getOrNull() as? JsonArray ?: return null
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val pkg = (o["pkg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        AppRow(
            pkg = pkg,
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
            system = (o["system"] as? JsonPrimitive)?.booleanOrNull == true,
            label = (o["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }.distinctBy { it.pkg }.sortedBy { it.pkg }
}

/** Applies app_enable / app_disable commands that finished after the list was taken. */
internal fun applyAppChanges(rows: List<AppRow>, listAt: String, commands: List<CommandRecord>): List<AppRow> {
    val since = isoMs(listAt) ?: return rows
    val latest = HashMap<String, Boolean>()
    for (c in commands.asReversed()) {
        if (c.name != "app_enable" && c.name != "app_disable") continue
        if (c.status != "done" || (isoMs(c.createdAt) ?: 0) < since) continue
        val pkg = (c.args["package"] as? JsonPrimitive)?.content ?: continue
        latest[pkg] = c.name == "app_enable"
    }
    if (latest.isEmpty()) return rows
    return rows.map { r -> latest[r.pkg]?.let { r.copy(enabled = it) } ?: r }
}

/** Newest app action per package since the list was taken (shows its progress on the row). */
internal fun recentAppCommands(listAt: String?, commands: List<CommandRecord>): Map<String, CommandRecord> {
    val since = isoMs(listAt) ?: return emptyMap()
    val out = HashMap<String, CommandRecord>()
    for (c in commands) {
        if (c.name != "app_enable" && c.name != "app_disable" && c.name != "app_force_stop") continue
        if ((isoMs(c.createdAt) ?: 0) < since) continue
        val pkg = (c.args["package"] as? JsonPrimitive)?.content ?: continue
        if (pkg !in out) out[pkg] = c
    }
    return out
}

/** Prepared rows for the tab (computed by the screen only when the tab is open). */
internal class AppsData(
    val last: CommandRecord?,
    val rows: List<AppRow>?,
    val recent: Map<String, CommandRecord>,
)

@Stable
internal class AppsState(private val scope: CoroutineScope, private val runner: CommandRunner) {
    var query by mutableStateOf("")
    var kind by mutableStateOf("user")
    var state by mutableStateOf("all")
    var busy by mutableStateOf<String?>(null)
        private set

    fun act(name: String, pkg: String?) {
        if (busy != null) return
        busy = if (pkg == null) name else "$name:$pkg"
        scope.launch {
            try {
                runner.run(name, if (pkg == null) JsonObject(emptyMap()) else buildJsonObject { put("package", pkg) })
            } finally {
                busy = null
            }
        }
    }

    fun filter(rows: List<AppRow>): List<AppRow> {
        val q = query.trim().lowercase()
        return rows.filter { r ->
            (kind == "all" || (kind == "system") == r.system) &&
                (state == "all" || (state == "enabled") == r.enabled) &&
                (q.isEmpty() || r.pkg.lowercase().contains(q) || r.label?.lowercase()?.contains(q) == true)
        }
    }
}

private const val APP_LIMIT = 300

@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.appsTab(data: AppsData, st: AppsState, canControl: Boolean) {
    val last = data.last
    val rows = data.rows
    val filtered = rows?.let { st.filter(it) }.orEmpty()
    item(key = "apps_head") {
        SectionCard(
            title = "Installed apps",
            action = {
                if (canControl) {
                    ActionButton(if (rows != null) "Reload list" else "Load app list", small = true, busy = st.busy == "app_list") { st.act("app_list", null) }
                }
            },
        ) {
            if (last != null) {
                LastResult(last, compact = last.status == "done")
                Spacer(Modifier.height(10.dp))
            }
            when {
                last == null -> EmptyState("No app list yet." + if (canControl) " Tap \"Load app list\"." else "")
                rows == null && isInFlight(last) -> Text("Waiting for the phone to list its apps…", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                rows == null && last.status == "done" -> Notice(Tone.WARN, null, "The phone sent an app list this app cannot read.")
                rows == null -> Text("No app list in the last result.", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                else -> {
                    OutlinedTextField(
                        value = st.query,
                        onValueChange = { st.query = it },
                        label = { Text("Search") },
                        placeholder = { Text("Package or app name") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (st.query.isNotEmpty()) ({ IconButton(onClick = { st.query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Segments(listOf("user" to "User", "system" to "System", "all" to "All"), st.kind) { st.kind = it }
                    Spacer(Modifier.height(8.dp))
                    Segments(listOf("all" to "Any state", "enabled" to "Enabled", "disabled" to "Disabled"), st.state) { st.state = it }
                    Spacer(Modifier.height(8.dp))
                    Text("${filtered.size} of ${rows.size} apps", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
                }
            }
        }
    }
    if (rows != null) {
        if (filtered.isEmpty()) item(key = "apps_none") { EmptyState("No apps match.") }
        items(filtered.take(APP_LIMIT), key = { "app_" + it.pkg }) { r ->
            AppItem(r, data.recent[r.pkg], st, canControl)
        }
        if (filtered.size > APP_LIMIT) {
            item(key = "apps_more") {
                EmptyState("Showing the first $APP_LIMIT. Narrow the search to see more.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segments(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, l) ->
            SegmentedButton(
                selected = value == v,
                onClick = { onChange(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(l, maxLines = 1) }
        }
    }
}

@Composable
private fun AppItem(r: AppRow, recent: CommandRecord?, st: AppsState, canControl: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = hsm.surface, contentColor = hsm.fg),
        border = BorderStroke(1.dp, hsm.border),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.label != null) Text(r.label, style = MaterialTheme.typography.titleSmall, color = hsm.fg)
            Text(r.pkg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (r.label != null) hsm.muted else hsm.fg)
            ChipRow {
                if (r.system) Pill("System", Tone.NEUTRAL) else Pill("User", Tone.INFO)
                if (!r.enabled) Pill("Disabled", Tone.WARN)
                if (recent != null && (isInFlight(recent) || recent.status in setOf("failed", "rejected", "expired") || recent.ok == false)) {
                    val look = commandStatus(recent)
                    Pill("${commandLabel(recent.name)}: ${look.label}", look.tone, dot = isInFlight(recent))
                }
            }
            if (canControl) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        "Force stop",
                        style = BtnStyle.SECONDARY,
                        small = true,
                        enabled = r.enabled && st.busy == null,
                        busy = st.busy == "app_force_stop:${r.pkg}",
                    ) { st.act("app_force_stop", r.pkg) }
                    if (r.enabled) {
                        ActionButton(
                            "Disable",
                            style = BtnStyle.DANGER,
                            small = true,
                            enabled = st.busy == null,
                            busy = st.busy == "app_disable:${r.pkg}",
                        ) { st.act("app_disable", r.pkg) }
                    } else {
                        ActionButton(
                            "Enable",
                            small = true,
                            enabled = st.busy == null,
                            busy = st.busy == "app_enable:${r.pkg}",
                        ) { st.act("app_enable", r.pkg) }
                    }
                }
            }
        }
    }
}
