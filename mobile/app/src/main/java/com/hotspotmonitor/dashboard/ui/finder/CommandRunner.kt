package com.hotspotmonitor.dashboard.ui.finder

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.unit.dp
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.ConfirmDialog
import com.hotspotmonitor.dashboard.ui.components.DESTRUCTIVE
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.commandStatus
import com.hotspotmonitor.dashboard.ui.components.describeCommand
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Command sending for the Finder and Messages screens: a native port of the web CommandRunner's
// safety UX (plan 6.4): viewers and admins without two-factor are refused, `confirm` commands ask
// first, `reauth` commands ask for the password, and errors become something a person can act on.

/** Help lines shown in the confirmation dialog (web lib/commandMeta.ts COMMAND_HELP). */
val COMMAND_HELP: Map<String, String> = mapOf(
    "send_sms" to "Sends a text message from the phone's SIM.",
    "ussd" to "Runs a USSD code (balance, plan) and shows the reply.",
    "speed_test" to "Download, upload, ping and jitter. Each run uses about 10 to 30 MB of data.",
    "finder_spot_test" to "Hold still while the phone records average, min, max and stability of each metric.",
    "finder_set_baseline" to "Marks this spot as the installed baseline for later comparison.",
    "finder_delete_spot" to "Deletes the spot on the phone and the Server.",
)

/** A command waiting for the user's confirmation. */
class PendingRun(
    val name: String,
    val args: JsonObject,
    val key: String,
    val detail: String?,
    val quiet: Boolean,
    val onSent: (CommandRecord) -> Unit,
)

@Stable
class CommandRunner(private val g: AppGraph, private val ctx: Context, private val scope: CoroutineScope) {
    /** The command whose confirmation dialog is open. */
    var pending by mutableStateOf<PendingRun?>(null)
        private set

    /** Key of the action whose request is in flight (buttons show a spinner). */
    var busyKey by mutableStateOf<String?>(null)
        private set

    fun isBusy(key: String) = busyKey == key

    /**
     * Sends [name] for the selected phone, asking first when its spec says so. [key] identifies the
     * button for the busy state; [detail] is an extra line in the confirmation; [onSent] gets the record.
     */
    fun run(
        name: String,
        args: JsonObject = JsonObject(emptyMap()),
        key: String = name,
        detail: String? = null,
        quiet: Boolean = false,
        onSent: (CommandRecord) -> Unit = {},
    ) {
        val spec = COMMANDS[name]
        val s = g.session.current
        when {
            spec == null -> return toast("Unknown command $name")
            !s.isAdmin -> return toast("Viewer accounts cannot send commands.")
            !s.canControl -> return toast("Set up two-factor authentication before using controls.")
            g.repo.deviceId.value == null -> return toast("No phone is paired yet.")
        }
        val p = PendingRun(name, args, key, detail, quiet, onSent)
        if (spec!!.confirm || spec.reauth) pending = p else launchSend(p)
    }

    fun dismiss() {
        pending = null
    }

    /** The confirmation dialog was accepted (commands without a password). */
    fun confirm() {
        val p = pending ?: return
        pending = null
        launchSend(p)
    }

    /** Password dialog: throwing keeps it open with the message. */
    suspend fun confirmWithPassword(password: String) {
        val p = pending ?: return
        busyKey = p.key
        try {
            send(p, password)
            pending = null
        } catch (e: ApiException) {
            if (e.isCode("totp_enrollment_required") || e.isCode("forbidden")) {
                toast(errorText(e), long = true)
                pending = null
            } else throw e
        } finally {
            if (busyKey == p.key) busyKey = null
        }
    }

    private fun launchSend(p: PendingRun) {
        busyKey = p.key
        scope.launch {
            try {
                send(p, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("${describeCommand(p.name, p.args)}: ${errorText(e)}", long = true)
            } finally {
                if (busyKey == p.key) busyKey = null
            }
        }
    }

    private suspend fun send(p: PendingRun, password: String?) {
        val spec = COMMANDS.getValue(p.name)
        val rec = g.repo.runCommand(p.name, p.args, confirmed = spec.confirm, password = if (spec.reauth) password else null)
        p.onSent(rec)
        if (!p.quiet) {
            val id = g.repo.deviceId.value
            val offline = id != null && g.store.current.snapshots[id]?.online == false
            if (offline) toast("${describeCommand(p.name, p.args)}: queued. The phone is offline; it expires if not delivered in time.", long = true)
            else toast("${describeCommand(p.name, p.args)}: sent")
        }
    }

    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun errorText(e: Throwable): String = when {
            e is ApiException && e.isCode("totp_enrollment_required") -> "Set up two-factor authentication first."
            e is ApiException && e.isCode("reauth_required") -> "Wrong password. Try again."
            e is ApiException && e.isCode("forbidden") -> "Your account is not allowed to send this command."
            e is ApiException && e.isCode("device_offline") -> "The phone is offline. The command is queued until it expires."
            else -> e.userMessage()
        }
    }
}

@Composable
fun rememberCommandRunner(): CommandRunner {
    val g = graph()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(g, ctx, scope) { CommandRunner(g, ctx, scope) }
}

/** The confirmation or password dialog of [runner]'s pending command (place once per screen). */
@Composable
fun CommandDialogs(runner: CommandRunner) {
    val p = runner.pending ?: return
    val spec = COMMANDS[p.name] ?: return
    val danger = p.name in DESTRUCTIVE
    val text = listOfNotNull(describeCommand(p.name, p.args), COMMAND_HELP[p.name], p.detail).joinToString("\n\n")
    if (spec.reauth) {
        ReauthDialog(
            title = "Confirm with password: ${spec.label}",
            text = text,
            confirmLabel = spec.label,
            danger = danger,
            onDismiss = runner::dismiss,
            onConfirm = { pw, _ -> runner.confirmWithPassword(pw) },
        )
    } else {
        ConfirmDialog(
            title = "Confirm: ${spec.label}",
            text = text,
            confirmLabel = spec.label,
            danger = danger,
            onDismiss = runner::dismiss,
            onConfirm = runner::confirm,
        )
    }
}

/** True while a command record has not finished yet. */
fun CommandRecord.inFlight(): Boolean = status in setOf("queued", "sent", "acked")

/**
 * The newest record of a command: status pill, when, and its error or output.
 * [output] turns the raw output into a readable line (null hides it).
 */
@Composable
fun CommandResult(
    rec: CommandRecord?,
    now: Long,
    modifier: Modifier = Modifier,
    output: (String) -> String? = { it },
) {
    if (rec == null) return
    val look = commandStatus(rec)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Last: ", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
            Pill(look.label, look.tone)
            if (rec.inFlight()) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = hsm.accent)
            }
            Spacer(Modifier.weight(1f))
            Text(formatRelative(rec.createdAt, now), style = MaterialTheme.typography.labelSmall, color = hsm.faint)
        }
        val err = rec.error?.takeIf { it.isNotBlank() }
        val out = rec.output?.takeIf { it.isNotBlank() }?.let(output)
        if (err != null || out != null) {
            Text(
                err ?: out!!,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (err == null) FontFamily.Default else FontFamily.Monospace,
                color = if (err != null) hsm.danger else hsm.fg,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hsm.surface2).padding(10.dp),
            )
        }
    }
}
