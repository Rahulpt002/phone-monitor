package com.hotspotmonitor.dashboard.ui.power

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.ConfirmDialog
import com.hotspotmonitor.dashboard.ui.components.DESTRUCTIVE
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.RevertCountdown
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.commandStatus
import com.hotspotmonitor.dashboard.ui.components.describeCommand
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

// Command controls for the Power screen (port of web CommandCard + CommandRunner, plan 6.4):
// confirm -> ConfirmDialog, reauth -> password, live status of the newest record, auto-revert.

private fun errorText(e: Throwable): String = when {
    e is ApiException && e.isCode("totp_enrollment_required") -> "Set up two-factor authentication first."
    e is ApiException && e.isCode("reauth_required") -> "Wrong password. Try again."
    e is ApiException && e.isCode("forbidden") -> "Your account is not allowed to send this command."
    else -> e.userMessage()
}

private fun ApiException?.offline() = this != null && isCode("device_offline")

/**
 * One control from the command catalog: label, safety badges, help, its argument form ([body]),
 * armed auto-reverts and the last result. Hidden for viewers, admins without two-factor, and when
 * the phone reports the command as unsupported.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandCard(
    name: String,
    help: String?,
    body: @Composable ColumnScope.(run: (JsonObject) -> Unit, busy: Boolean) -> Unit,
) {
    val g = graph()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val spec = COMMANDS[name] ?: return
    if (!session.canControl) return
    val snap = deviceId?.let { data.snapshots[it] }
    val cap = remember(snap?.state) { snap?.groups?.capabilities?.get(name) }
    if (cap?.supported == false) return
    val last = data.lastCommandByName(deviceId)[name]
    val armed = data.pendingReverts(deviceId).filter { it.name == name }

    var busy by remember { mutableStateOf(false) }
    var confirmArgs by remember { mutableStateOf<JsonObject?>(null) }
    var reauthArgs by remember { mutableStateOf<JsonObject?>(null) }

    fun sentToast(args: JsonObject, queued: Boolean) {
        val what = describeCommand(name, args)
        val offline = queued || snap?.online == false
        Toast.makeText(
            ctx,
            if (offline) "$what: queued. The phone is offline; it expires if not delivered in time." else "$what: sent",
            if (offline) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    fun send(args: JsonObject, confirmed: Boolean) {
        scope.launch {
            busy = true
            try {
                g.repo.runCommand(name, args, confirmed = confirmed)
                sentToast(args, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? ApiException).offline()) sentToast(args, true)
                else Toast.makeText(ctx, "${describeCommand(name, args)}: ${errorText(e)}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val run: (JsonObject) -> Unit = { args ->
        when {
            spec.reauth -> reauthArgs = args
            spec.confirm -> confirmArgs = args
            else -> send(args, false)
        }
    }

    SectionCard {
        Text(spec.label, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
        val revert = spec.revertFor(buildJsonObject { put("enabled", false) }) != null ||
            spec.revertFor(buildJsonObject { put("enabled", true) }) != null ||
            spec.revertFor(JsonObject(emptyMap())) != null
        if (spec.confirm || spec.reauth || revert) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (spec.confirm) Pill("Confirm", Tone.NEUTRAL)
                if (spec.reauth) Pill("Password", Tone.WARN)
                if (revert) Pill("Auto-revert", Tone.WARN)
            }
        }
        if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = hsm.muted, modifier = Modifier.padding(top = 4.dp))
        val capLine = listOfNotNull(cap?.method?.let { "Method: $it" }, cap?.note).joinToString(" · ")
        if (capLine.isNotEmpty()) Text(capLine, style = MaterialTheme.typography.bodySmall, color = hsm.faint, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        body(run, busy)
        for (c in armed) {
            Spacer(Modifier.height(10.dp))
            RevertCountdown(c)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = hsm.border)
        Spacer(Modifier.height(8.dp))
        LastResult(last)
    }

    confirmArgs?.let { args ->
        ConfirmDialog(
            title = spec.label,
            text = "${describeCommand(name, args)}?" + (help?.let { "\n\n$it" } ?: ""),
            confirmLabel = spec.label,
            danger = name in DESTRUCTIVE,
            onDismiss = { confirmArgs = null },
            onConfirm = {
                confirmArgs = null
                send(args, true)
            },
        )
    }
    reauthArgs?.let { args ->
        ReauthDialog(
            title = spec.label,
            text = "${describeCommand(name, args)}. Enter your password to confirm.",
            confirmLabel = spec.label,
            danger = name in DESTRUCTIVE,
            onDismiss = { reauthArgs = null },
            onConfirm = { password, _ ->
                try {
                    g.repo.runCommand(name, args, confirmed = spec.confirm, password = password)
                    reauthArgs = null
                    sentToast(args, false)
                } catch (e: ApiException) {
                    if (e.offline()) {
                        reauthArgs = null
                        sentToast(args, true)
                    } else throw e
                }
            },
        )
    }
}

/** Status, when, who and the output or error of the newest record (web LastResult). */
@Composable
fun LastResult(cmd: CommandRecord?) {
    val now = rememberNow(5000)
    var open by remember(cmd?.id) { mutableStateOf(false) }
    if (cmd == null) {
        Text("Not run yet", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
        return
    }
    val look = commandStatus(cmd)
    val at = cmd.finishedAt ?: cmd.ackedAt ?: cmd.deliveredAt ?: cmd.createdAt
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(look.label, look.tone)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(
                    formatRelative(at, now),
                    "by ${cmd.user ?: cmd.source}",
                    cmd.durationMs?.let { "${trimNumber(it / 1000.0, 1)} s" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
            )
        }
        Text(describeCommand(cmd.name, cmd.args), style = MaterialTheme.typography.bodySmall, color = hsm.fg, modifier = Modifier.padding(top = 4.dp))
        val text = cmd.error?.takeIf { it.isNotBlank() } ?: cmd.output?.takeIf { it.isNotBlank() }
        if (text != null) {
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    if (open) "Hide output" else if (cmd.error != null) "Show error" else "Show output",
                    color = hsm.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (open) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (cmd.error != null) hsm.danger else hsm.fg,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hsm.surface2).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun BusyLabel(busy: Boolean, label: String) {
    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent) else Text(label)
}

/** charge_limit { percent: 20..100 } as a 5 % step slider. */
@Composable
fun ChargeLimitControl(current: Int) {
    CommandCard(
        "charge_limit",
        "Stop charging at this level; resumes a few percent lower. Saved in Settings once the phone reports it applied.",
    ) { exec, busy ->
        var pct by remember(current) { mutableIntStateOf(current) }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Stop charging at", style = MaterialTheme.typography.bodyLarge, color = hsm.fg, modifier = Modifier.weight(1f))
            Text("$pct%", fontSize = 26.sp, color = if (pct > 80) hsm.warn else hsm.accent, style = MaterialTheme.typography.titleLarge)
        }
        Slider(
            value = pct.toFloat(),
            onValueChange = { pct = ((it / 5f).roundToInt() * 5).coerceIn(20, 100) },
            valueRange = 20f..100f,
            steps = 15,
            colors = SliderDefaults.colors(thumbColor = hsm.accent, activeTrackColor = hsm.accent, inactiveTrackColor = hsm.surface3),
        )
        Text(
            "Now $current%. 50 to 60% is safest for a phone that stays plugged in outdoors.",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.muted,
        )
        if (pct > 80) {
            Text(
                "Above 80% shortens battery life and raises the swelling risk in heat.",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.warn,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { exec(buildJsonObject { put("percent", pct) }) },
            enabled = !busy && pct != current,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { BusyLabel(busy, if (pct == current) "Limit is $current%" else "Set limit to $pct%") }
    }
}

/** charging { enabled } as two buttons, the one that changes the state highlighted. */
@Composable
fun ChargingControl(enabled: Boolean?) {
    CommandCard("charging", "Manual charge switch. The watchdog heat rule still wins.") { exec, busy ->
        when (enabled) {
            null -> Pill("State unknown", Tone.NEUTRAL)
            true -> Pill("Now on", Tone.OK, dot = true)
            false -> Pill("Now off", Tone.NEUTRAL, dot = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val on = { exec(buildJsonObject { put("enabled", true) }) }
            val off = { exec(buildJsonObject { put("enabled", false) }) }
            if (enabled == false) {
                Button(onClick = on, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { BusyLabel(busy, "Turn on") }
            } else {
                OutlinedButton(onClick = on, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("Turn on") }
            }
            if (enabled == true) {
                Button(onClick = off, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { BusyLabel(busy, "Turn off") }
            } else {
                OutlinedButton(onClick = off, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("Turn off") }
            }
        }
    }
}
