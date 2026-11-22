package com.hotspotmonitor.dashboard.ui.controls

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.DESTRUCTIVE
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.describeCommand
import com.hotspotmonitor.dashboard.ui.components.revertExplanation
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/*
 * Sends commands with the plan 6.4 safety UX (port of web CommandRunner.tsx): a confirmation for
 * `confirm` commands, the password for `reauth` commands, the auto-revert and reboot-limit notes,
 * and errors mapped to something a person can act on.
 */

data class RunOptions(
    /** Extra line shown in the confirmation dialog. */
    val detail: String? = null,
    /** Pre-filled password (the root console keeps it while it is open). */
    val password: String? = null,
    /** Called with the password that worked, so the caller can keep it. */
    val onPassword: ((String) -> Unit)? = null,
    /** No "sent" toast (callers that show their own progress). */
    val quiet: Boolean = false,
)

class PendingRun(
    val name: String,
    val args: JsonObject,
    val opts: RunOptions,
    val result: CompletableDeferred<CommandRecord?>,
)

/** Human text for a failed send. */
fun commandErrorText(e: Throwable): String = when {
    e is ApiException && e.isCode("totp_enrollment_required") -> "Set up two-factor authentication first."
    e is ApiException && e.isCode("reauth_required") -> "Wrong password. Try again."
    e is ApiException && e.isCode("forbidden") ->
        e.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: "Your account is not allowed to send this command."
    e is ApiException && e.isCode("device_offline") -> "The phone is offline. The command is queued until it expires."
    e is ApiException && e.isCode("locked") -> e.message?.takeIf { it.isNotBlank() } ?: "Too many failed attempts. Try again later."
    else -> e.userMessage()
}

@Stable
class CommandRunner internal constructor(private val g: AppGraph, private val ctx: Context) {
    /** The command waiting in the confirmation dialog (shown by [CommandDialogHost]). */
    var pending by mutableStateOf<PendingRun?>(null)
        internal set

    /**
     * Sends [name] for the selected phone. Suspends until it is sent, or the confirmation is
     * cancelled (null). Failures are shown as a toast (or in the dialog) and return null.
     */
    suspend fun run(name: String, args: JsonObject = JsonObject(emptyMap()), opts: RunOptions = RunOptions()): CommandRecord? {
        val spec = COMMANDS[name]
        if (spec == null) {
            toast(ctx, "Unknown command $name", true)
            return null
        }
        val s = g.session.current
        if (!s.isAdmin) {
            toast(ctx, "Viewer accounts cannot send commands.", true)
            return null
        }
        if (s.totpEnrollmentRequired) {
            toast(ctx, "Set up two-factor authentication before using controls.", true)
            return null
        }
        if (g.repo.deviceId.value == null) {
            toast(ctx, "No phone is paired yet.", true)
            return null
        }
        if (spec.confirm || spec.reauth) {
            pending?.result?.complete(null)
            val d = CompletableDeferred<CommandRecord?>()
            pending = PendingRun(name, args, opts, d)
            return try {
                d.await()
            } finally {
                if (pending?.result === d) pending = null
            }
        }
        return try {
            submit(name, args, opts, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(ctx, "${describeCommand(name, args)}: ${commandErrorText(e)}", true)
            null
        }
    }

    internal suspend fun submit(name: String, args: JsonObject, opts: RunOptions, password: String?): CommandRecord {
        val spec = COMMANDS.getValue(name)
        val rec = g.repo.runCommand(name, args, confirmed = spec.confirm, password = if (spec.reauth) password else null)
        if (!opts.quiet) {
            val id = g.repo.deviceId.value
            val offline = id != null && g.store.current.snapshots[id]?.online == false
            if (offline) toast(ctx, "${describeCommand(name, args)}: queued. The phone is offline; it expires if not delivered in time.", true)
            else toast(ctx, "${describeCommand(name, args)}: sent")
        }
        return rec
    }

    internal fun toastError(text: String) = toast(ctx, text, true)
}

@Composable
fun rememberCommandRunner(): CommandRunner {
    val g = graph()
    val ctx = LocalContext.current.applicationContext
    return remember(g) { CommandRunner(g, ctx) }
}

/** Place once per screen that uses [runner]; shows the confirmation / password dialog. */
@Composable
fun CommandDialogHost(runner: CommandRunner) {
    val p = runner.pending ?: return
    key(p) { CommandDialog(runner, p) }
}

private fun confirmLabel(name: String, label: String): String = when (name) {
    "shell" -> "Run as root"
    "network_mode" -> "Change mode"
    else -> label
}

@Composable
private fun CommandDialog(runner: CommandRunner, p: PendingRun) {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val spec = COMMANDS.getValue(p.name)
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf(p.opts.password ?: "") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remembered = !p.opts.password.isNullOrEmpty()

    val settingsResp = deviceId?.let { data.settings[it] }
    val settings = settingsResp?.typed ?: Settings.DEFAULT
    val revertAfter = settings.commands.revertAfterS.takeIf { it > 0 } ?: 120
    val revertMode = spec.revertFor(p.args)
    val destructive = p.name in DESTRUCTIVE
    val rebootInfo = if (spec.isReboot && settingsResp != null) {
        val used = deviceId?.let { data.snapshots[it] }?.groups?.system?.rebootsInWindow
        "${used ?: "?"} of ${settings.watchdog.maxReboots} reboots used in the last ${trimNumber(settings.watchdog.rebootWindowH, 1)} h."
    } else null

    fun close() {
        if (busy) return
        p.result.complete(null)
        if (runner.pending === p) runner.pending = null
    }

    fun confirm() {
        if (busy) return
        if (spec.reauth && password.isEmpty()) {
            error = "Enter your password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val rec = runner.submit(p.name, p.args, p.opts, password)
                if (spec.reauth) p.opts.onPassword?.invoke(password)
                p.result.complete(rec)
                if (runner.pending === p) runner.pending = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = commandErrorText(e)
                val final = e is ApiException && (e.isCode("totp_enrollment_required") || e.isCode("forbidden") || e.isCode("device_offline"))
                if (final) {
                    runner.toastError("${describeCommand(p.name, p.args)}: $msg")
                    p.result.complete(null)
                    if (runner.pending === p) runner.pending = null
                } else {
                    if (e is ApiException && e.isCode("reauth_required")) password = ""
                    error = msg
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        icon = if (destructive) ({ Icon(Icons.Outlined.WarningAmber, null, tint = hsm.danger) }) else null,
        title = { Text(if (spec.reauth) "${spec.label}: confirm with password" else "Confirm: ${spec.label}") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(describeCommand(p.name, p.args), style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                COMMAND_HELP[p.name]?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hsm.muted) }
                p.opts.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hsm.fg) }
                if (rebootInfo != null) Notice(Tone.WARN, null, rebootInfo)
                if (revertMode != null) Notice(Tone.WARN, "Auto-revert after $revertAfter s", revertExplanation(revertMode.wire, revertAfter.toLong()))
                if (p.name == "shell") {
                    Text(
                        (p.args["cmd"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = hsm.fg,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hsm.surface2).padding(10.dp),
                    )
                }
                if (spec.reauth) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        label = { Text("Your password") },
                        singleLine = true,
                        enabled = !busy,
                        isError = error != null,
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPw) "Hide password" else "Show password")
                            }
                        },
                        supportingText = if (remembered && error == null) ({ Text("Remembered while this console is open") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) Text(error!!, color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = { confirm() },
                enabled = !busy,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = hsm.danger, contentColor = hsm.bg) else ButtonDefaults.buttonColors(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (destructive) hsm.bg else hsm.onAccent)
                else Text(confirmLabel(p.name, spec.label))
            }
        },
        dismissButton = { TextButton(onClick = { close() }, enabled = !busy) { Text("Cancel") } },
        containerColor = hsm.surface,
    )
}
