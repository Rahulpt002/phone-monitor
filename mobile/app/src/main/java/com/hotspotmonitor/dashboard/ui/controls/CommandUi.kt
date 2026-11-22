package com.hotspotmonitor.dashboard.ui.controls

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.titleCase
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.auth.copy
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.commandStatus
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.theme.hsm

// Dashboard wording and small building blocks shared by Controls and System
// (port of web/src/lib/commandMeta.ts and components/commands/CommandStatus.tsx).

/** What each command does, shown on its control and in its confirmation. */
val COMMAND_HELP: Map<String, String> = mapOf(
    "mobile_data" to "Turns the SIM data connection on or off. Turning it off arms auto-revert.",
    "data_cycle" to "Mobile data off, wait, then on again. The first watchdog recovery step.",
    "airplane_mode" to "All radios off. Turning it on arms auto-revert.",
    "airplane_cycle" to "Airplane mode on for a few seconds, then off. Forces a fresh network attach.",
    "usb_tethering" to "Shares the internet over the USB cable. Turning it off cuts the home network, so you must press Keep.",
    "hotspot" to "Wi-Fi hotspot with a name and password.",
    "wifi" to "Wi-Fi client radio. Keep off unless used (heat and power).",
    "bluetooth" to "Keep off unless used (heat and power).",
    "network_mode" to "Allowed network types. A mode the cell does not offer can drop the connection, so auto-revert is armed.",
    "reboot" to "Restarts the phone. Counts toward the reboot limit.",
    "power_off" to "Switches the phone off. Someone must press the power button to turn it back on.",
    "screen" to "Wakes the screen or puts it to sleep.",
    "brightness" to "Screen brightness, 0 to 255. Keep it low to save power and heat.",
    "charging" to "Manual charge switch. The watchdog heat rule still wins.",
    "charge_limit" to "Stop charging at this level; resumes a few percent lower. Saved in Settings once the phone reports it applied.",
    "app_list" to "Lists installed apps (shown under System > Apps).",
    "app_force_stop" to "Stops an app until it is started again.",
    "app_disable" to "Disables an app for user 0 until it is enabled again.",
    "app_enable" to "Re-enables a disabled app.",
    "process_list" to "Runs top once and shows the output.",
    "send_sms" to "Sends a text message from the phone's SIM.",
    "ussd" to "Runs a USSD code (balance, plan) and shows the reply.",
    "shell" to "Runs a command as root on the phone.",
    "refresh" to "Asks the phone for fresh readings of every group now.",
    "capability_probe" to "Re-tests which commands this phone supports.",
    "watchdog_simulate" to "Pretends the internet is down so the recovery ladder runs (testing only).",
    "speed_test" to "Download, upload, ping and jitter. Each run uses about 10 to 30 MB of data.",
    "finder_spot_test" to "Hold still while the phone records average, min, max and stability of each metric.",
    "finder_set_baseline" to "Marks this spot as the installed baseline for later comparison.",
    "finder_delete_spot" to "Deletes the spot on the phone and the Server.",
)

/** Status filter options of the command log, in lifecycle order. */
val STATUS_LABELS: Map<String, String> = linkedMapOf(
    "queued" to "Queued", "sent" to "Sent", "acked" to "Running", "done" to "Done", "failed" to "Failed",
    "rejected" to "Rejected", "expired" to "Expired", "revert_armed" to "Auto-revert armed", "kept" to "Kept", "reverted" to "Reverted",
)

val COMMAND_SOURCES = listOf("dashboard", "watchdog", "schedule", "local_ui", "sms", "lan")

/** WPA2 passphrase as the Agent accepts it (ArgRules.kt): 8 to 63 printable ASCII characters. */
fun hotspotPasswordError(pw: String): String? = when {
    pw.length < 8 -> "At least 8 characters (WPA2)."
    pw.length > 63 -> "At most 63 characters."
    !pw.all { it.code in 0x20..0x7E } -> "Use printable ASCII only: letters, digits, spaces and symbols, no accents or emoji."
    else -> null
}

/** Root shell timeout range, the same on the Server, the simulator and the Agent. */
const val SHELL_TIMEOUT_MAX_S = 600

/** Whole seconds 1..SHELL_TIMEOUT_MAX_S, or null. */
fun parseShellTimeout(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || !t.all { it.isDigit() } || t.length > 6) return null
    val n = t.toInt()
    return if (n in 1..SHELL_TIMEOUT_MAX_S) n else null
}

/** Finished with a result the UI can use (revert_armed already carries the output). */
fun hasResult(c: CommandRecord?): Boolean =
    c != null && c.status in setOf("done", "revert_armed", "kept", "reverted", "failed")

fun isInFlight(c: CommandRecord?): Boolean = c != null && c.status in setOf("queued", "sent", "acked")

fun durationText(c: CommandRecord): String? = c.durationMs?.let { "${trimNumber(it / 1000.0, 1)} s" }

fun toast(ctx: Context, text: String, long: Boolean = false) {
    Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Status chip of a command record. */
@Composable
fun StatusChip(c: CommandRecord) {
    val look = commandStatus(c)
    Pill(look.label, look.tone, dot = isInFlight(c))
}

/**
 * "Last result" under a control: status chip, when, who, how long, and the output or error on demand.
 */
@Composable
fun LastResult(cmd: CommandRecord?, compact: Boolean = false, startOpen: Boolean = false) {
    if (cmd == null) {
        Text("Not run yet", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
        return
    }
    val now = rememberNow(5000)
    val at = cmd.finishedAt ?: cmd.ackedAt ?: cmd.deliveredAt ?: cmd.createdAt
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(cmd)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(formatRelative(at, now), "by ${cmd.user ?: titleCase(cmd.source)}", durationText(cmd)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val text = cmd.error?.takeIf { it.isNotBlank() } ?: cmd.output?.takeIf { it.isNotBlank() }
        if (!compact && text != null) {
            var open by remember(cmd.id) { mutableStateOf(startOpen) }
            val ctx = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkButton(if (open) "Hide output" else if (cmd.error != null) "Show error" else "Show output") { open = !open }
                if (open) LinkButton("Copy") { copy(ctx, text, "Output copied") }
            }
            if (open) OutputBlock(text, isError = cmd.error?.isNotBlank() == true)
        }
    }
}

/** Monospace, selectable, scrollable output box. [wrap] = false keeps columns (top, ps) aligned. */
@Composable
fun OutputBlock(text: String, isError: Boolean = false, maxHeight: Dp = 280.dp, wrap: Boolean = true, modifier: Modifier = Modifier) {
    val v = rememberScrollState()
    val h = rememberScrollState()
    Box(
        modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(12.dp)).background(hsm.surface2),
    ) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                softWrap = wrap,
                color = if (isError) hsm.danger else hsm.fg,
                modifier = Modifier
                    .verticalScroll(v)
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(h))
                    .padding(10.dp),
            )
        }
    }
}

enum class BtnStyle { PRIMARY, SECONDARY, DANGER }

/** Button with a busy spinner and a 48 dp touch target. */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.PRIMARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val pad = if (small) PaddingValues(horizontal = 14.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding
    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = when (style) {
                    BtnStyle.SECONDARY -> hsm.accent
                    BtnStyle.DANGER -> hsm.bg
                    BtnStyle.PRIMARY -> hsm.onAccent
                },
            )
        } else {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    when (style) {
        BtnStyle.SECONDARY -> OutlinedButton(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = modifier.heightIn(min = if (small) 40.dp else 48.dp),
            contentPadding = pad,
        ) { content() }
        BtnStyle.PRIMARY -> Button(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = modifier.heightIn(min = if (small) 40.dp else 48.dp),
            contentPadding = pad,
        ) { content() }
        BtnStyle.DANGER -> Button(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = modifier.heightIn(min = if (small) 40.dp else 48.dp),
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = hsm.danger, contentColor = hsm.bg),
        ) { content() }
    }
}

/** Read-only field that opens a menu of [options] (value to label). */
@Composable
fun <T> Dropdown(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value.toString()
    Box(modifier) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay: a read-only text field swallows taps otherwise.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = hsm.surface2) {
            for ((v, l) in options) {
                DropdownMenuItem(
                    text = { Text(l, color = if (v == value) hsm.accent else hsm.fg) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

/** Wrapping row of pills/badges. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Grey box explaining why a control is disabled. */
@Composable
fun ReasonBox(text: String, color: Color = hsm.muted, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(hsm.surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        action?.invoke()
    }
}
