package com.hotspotmonitor.agent.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Read-only view of the settings the Agent runs on (the Server owns them; edit them in the
 * Dashboard), the device profile, local-only options and diagnostics.
 */
@Composable
fun SettingsScreen(
    graph: AgentGraph,
    onOpenFinder: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    keepScreenOn: Boolean = false,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val version by graph.settings.version.collectAsStateWithLifecycle()
    val profile by graph.profiles.profile.collectAsStateWithLifecycle()
    val groups = remember(settings) { settingsGroups(settings) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        SectionCard("Source") {
            InfoRow("Version", if (version == 0) "0 (built-in defaults)" else "$version (from the Server)")
            if (graph.settings.hasLocalEdits) InfoRow("Local edits", "yes; replaced by the next Server copy")
            HintText("The Server owns these settings. Change them in the Dashboard; they reach the phone at once or at the next connection.")
        }

        for ((title, rows) in groups) {
            SectionCard(title) {
                if (rows.isEmpty()) HintText("Empty")
                rows.forEach { (key, value) -> InfoRow(key, value, monospace = true) }
            }
        }

        SectionCard("Device profile") {
            InfoRow("Profile", "${profile.id} (${profile.name})")
            InfoRow("Charge control paths", profile.chargeControl.size.toString())
            InfoRow("USB tethering function", profile.usbFunction)
            InfoRow("Automatic power-on", profile.autoboot.method)
            if (profile.commands.isNotEmpty()) InfoRow("Command overrides", profile.commands.keys.sorted().joinToString())
            profile.notes.forEach { HintText(it) }
        }

        SectionCard("On this phone only") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the screen on", style = MaterialTheme.typography.bodyLarge)
                    HintText("Only while this app is open, e.g. while aiming the phone.")
                }
                Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOnChange)
            }
            HintText("Headless Finder (volume keys and spoken results) is switched on the Finder screen.")
            OutlinedButton(onClick = onOpenFinder) { Text("Open the Finder") }
            OutlinedButton(onClick = onOpenSetup) { Text("Permissions and setup") }
        }

        SectionCard("Diagnostics") {
            InfoRow("Agent version", "${graph.agentVersion} (${graph.agentVersionCode})")
            InfoRow("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            InfoRow("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            Button(onClick = { LogExport.share(context, graph) }) { Text("Export logs") }
        }
    }
}

private val GROUP_TITLES = linkedMapOf(
    "telemetry" to "Telemetry",
    "watchdog" to "Watchdog",
    "commands" to "Commands",
    "alerts" to "Alerts",
    "plan" to "Data plan",
    "finder" to "Signal Finder",
    "profile_override" to "Device profile override",
)

/** Settings as (group title, [(dotted key, value)]) in Dashboard order, generic over the schema. */
private fun settingsGroups(settings: Settings): List<Pair<String, List<Pair<String, String>>>> {
    val root = AgentJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject
    val known = GROUP_TITLES.keys
    val ordered = known.filter { it in root } + root.keys.filter { it !in known }
    return ordered.mapNotNull { key ->
        val value = root[key] ?: return@mapNotNull null
        if (value is JsonNull) return@mapNotNull null
        val title = GROUP_TITLES[key] ?: key
        val rows = ArrayList<Pair<String, String>>()
        if (value is JsonObject) flatten("", value, rows) else rows += key to display(value)
        title to rows
    }
}

private fun flatten(prefix: String, obj: JsonObject, out: MutableList<Pair<String, String>>) {
    for ((k, v) in obj) {
        val key = if (prefix.isEmpty()) k else "$prefix.$k"
        if (v is JsonObject && v.isNotEmpty()) flatten(key, v, out) else out += key to display(v)
    }
}

private fun display(value: JsonElement): String = when (value) {
    is JsonNull -> "-"
    is JsonPrimitive -> value.content
    is JsonArray -> value.joinToString(", ") { display(it) }
    is JsonObject -> if (value.isEmpty()) "{}" else value.toString()
}
