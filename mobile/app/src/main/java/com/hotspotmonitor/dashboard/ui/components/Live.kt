package com.hotspotmonitor.dashboard.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.StoredEvent
import com.hotspotmonitor.dashboard.data.formatCountdown
import com.hotspotmonitor.dashboard.data.formatDateTime
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// --- command descriptions (port of web/src/lib/commandMeta.ts) ----------------------------------

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

val NETWORK_MODE_LABELS = linkedMapOf(
    "auto" to "Automatic (best available)",
    "nr_lte" to "5G and 4G",
    "lte_only" to "4G only",
    "lte_wcdma" to "4G and 3G",
    "wcdma_only" to "3G only",
)

fun commandLabel(name: String): String = COMMANDS[name]?.label ?: titleCase(name)

/** One-line human description of a command with its arguments; secret arguments never appear. */
fun describeCommand(name: String, args: JsonObject): String {
    val label = commandLabel(name)
    fun onOff(v: Boolean?) = when (v) { true -> "on"; false -> "off"; null -> "" }
    return when (name) {
        "mobile_data", "airplane_mode", "wifi", "bluetooth", "charging" -> "$label ${onOff(args.b("enabled"))}".trim()
        "usb_tethering" -> "$label ${onOff(args.b("enabled"))}${args.s("function")?.let { " (${it.uppercase()})" } ?: ""}"
        "hotspot" -> if (args.b("enabled") == false) "$label off" else "$label on${args.s("ssid")?.let { " as \"$it\"" } ?: ""}"
        "data_cycle", "airplane_cycle" -> "$label${args.s("off_seconds")?.let { " ($it s off)" } ?: ""}"
        "network_mode" -> "$label: ${NETWORK_MODE_LABELS[args.s("mode")] ?: args.s("mode") ?: ""}"
        "screen" -> "$label ${if (args.b("on") == true) "on" else "off"}"
        "brightness" -> "$label ${args.s("level") ?: ""}"
        "charge_limit" -> "$label ${args.s("percent") ?: ""}%"
        "app_force_stop", "app_disable", "app_enable" -> "$label: ${args.s("package") ?: ""}"
        "send_sms" -> "$label to ${args.s("to") ?: ""}"
        "ussd" -> "$label ${args.s("code") ?: ""}"
        "shell" -> "$label: ${args.s("cmd") ?: ""}"
        "watchdog_simulate" -> "$label for ${args.s("duration_s") ?: ""} s"
        "finder_spot_test" -> "$label: ${args.s("name") ?: ""}${args.s("orientation")?.let { " ($it)" } ?: ""}"
        else -> label
    }
}

/** Commands whose confirmation should look dangerous. */
val DESTRUCTIVE = setOf("reboot", "power_off", "shell", "app_disable", "finder_delete_spot", "watchdog_simulate")

fun revertExplanation(mode: String, seconds: Long): String =
    if (mode == "dashboard") {
        "This can cut the home network while the phone stays online, so the phone cannot judge it by itself. Press \"Keep\" within $seconds s or the phone undoes the change."
    } else {
        "If the phone cannot reach the Server again within $seconds s, it undoes the change by itself. It confirms automatically once its link works."
    }

data class StatusLook(val label: String, val tone: Tone)

fun commandStatus(c: CommandRecord): StatusLook = when (c.status) {
    "queued" -> StatusLook("Queued", Tone.INFO)
    "sent" -> StatusLook("Sent", Tone.INFO)
    "acked" -> StatusLook("Running", Tone.INFO)
    "done" -> StatusLook(if (c.ok == false) "Failed" else "Done", if (c.ok == false) Tone.DANGER else Tone.OK)
    "failed" -> StatusLook("Failed", Tone.DANGER)
    "rejected" -> StatusLook("Rejected", Tone.DANGER)
    "expired" -> StatusLook("Expired", Tone.WARN)
    "revert_armed" -> StatusLook("Will auto-revert", Tone.WARN)
    "kept" -> StatusLook("Kept", Tone.OK)
    "reverted" -> StatusLook("Reverted", Tone.WARN)
    else -> StatusLook(titleCase(c.status), Tone.NEUTRAL)
}

/** Every armed auto-revert for the selected phone, with a countdown (and Keep when needed). */
@Composable
fun PendingReverts() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val pending = data.pendingReverts(deviceId)
    if (pending.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in pending) RevertCountdown(c)
    }
}

@Composable
fun RevertCountdown(cmd: CommandRecord) {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val now = rememberNow(1000)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val deadline = isoMs(cmd.revertDeadline)
    val left = deadline?.let { it - now }
    val mode = cmd.revertConfirm ?: "agent_link"
    val finished = isoMs(cmd.finishedAt)
    val seconds = if (deadline != null && finished != null) (deadline - finished) / 1000 else 120
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(hsm.warnSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(describeCommand(cmd.name, cmd.args), style = MaterialTheme.typography.titleMedium, color = hsm.fg, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        left == null -> ""
                        left > 0 -> "reverts in ${formatCountdown(left)}"
                        else -> "reverting…"
                    },
                    color = hsm.warn,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(revertExplanation(mode, seconds), style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        }
        if (mode == "dashboard" && session.canControl) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            g.api.keepCommand(cmd.id)
                            Toast.makeText(ctx, "Kept: ${describeCommand(cmd.name, cmd.args)}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Keep failed: ${e.userMessage()}", Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && (left == null || left > 0),
            ) { Text("Keep") }
        }
    }
}

// --- events ------------------------------------------------------------------------------------

fun severityTone(s: String): Tone = when (s) {
    "critical" -> Tone.DANGER
    "warning" -> Tone.WARN
    "info" -> Tone.INFO
    else -> Tone.NEUTRAL
}

@Composable
fun EventList(events: List<StoredEvent>, empty: String = "No events yet.") {
    if (events.isEmpty()) {
        EmptyState(empty)
        return
    }
    val now = rememberNow(10_000)
    Column {
        events.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = hsm.border)
            EventRow(e, now)
        }
    }
}

@Composable
fun EventRow(e: StoredEvent, now: Long) {
    var open by remember { mutableStateOf(false) }
    val hasData = e.data?.isNotEmpty() == true
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.severity, severityTone(e.severity))
            Spacer(Modifier.width(8.dp))
            Text(titleCase(e.kind), style = MaterialTheme.typography.labelMedium, color = hsm.muted, modifier = Modifier.weight(1f))
            Text(formatRelative(e.ts, now), style = MaterialTheme.typography.labelSmall, color = hsm.faint)
        }
        Text(e.message, style = MaterialTheme.typography.bodyMedium, color = hsm.fg, modifier = Modifier.padding(top = 4.dp))
        if (hasData) {
            TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (open) "Hide details" else "Details", color = hsm.accent, style = MaterialTheme.typography.labelMedium)
            }
            if (open) {
                Text(
                    "${formatDateTime(e.ts)}\n" + prettyJson(e.data!!),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = hsm.fg,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hsm.surface2).padding(8.dp),
                )
            }
        }
    }
}

fun prettyJson(o: JsonObject): String = PrettyJson.encodeToString(JsonObject.serializer(), o)

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

/** Section title inside a page (outside cards). */
@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = hsm.fg, modifier = Modifier.padding(top = 8.dp))
}
