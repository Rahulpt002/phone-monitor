package com.hotspotmonitor.dashboard.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.PublicConfig
import com.hotspotmonitor.dashboard.data.SessionState
import com.hotspotmonitor.dashboard.data.formatTime
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.push.PushStatus
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class AccountState {
    var config by mutableStateOf<PublicConfig?>(null)
    var loading by mutableStateOf(false)

    // change password
    var current by mutableStateOf("")
    var next by mutableStateOf("")
    var repeat by mutableStateOf("")
    var showPasswords by mutableStateOf(false)
    var pwBusy by mutableStateOf(false)
    var pwError by mutableStateOf<String?>(null)

    // push test
    var testBusy by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
    var testError by mutableStateOf<String?>(null)

    /** The "move to a new authenticator" prompt is open. */
    var moveTotp by mutableStateOf(false)

    fun loadConfig(g: AppGraph, scope: CoroutineScope, manual: Boolean = false) {
        scope.launch {
            loading = manual
            try {
                config = g.api.config()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only used for the channel summary; the rest of the tab works without it.
            } finally {
                loading = false
            }
        }
    }

    fun changePassword(g: AppGraph, ctx: Context, scope: CoroutineScope) {
        if (pwBusy) return
        pwError = when {
            current.isEmpty() -> "Enter your current password."
            next.length < MIN_PASSWORD -> "The new password needs at least $MIN_PASSWORD characters."
            next != repeat -> "The new passwords do not match."
            next == current -> "The new password must be different."
            else -> null
        }
        if (pwError != null) return
        pwBusy = true
        scope.launch {
            try {
                g.api.changePassword(current, next)
                current = ""
                next = ""
                repeat = ""
                Toast.makeText(ctx, "Password changed. Your other devices are signed out.", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pwError = e.userMessage()
            } finally {
                pwBusy = false
            }
        }
    }

    fun sendTest(g: AppGraph, ctx: Context, scope: CoroutineScope) {
        if (testBusy) return
        testBusy = true
        testError = null
        testResult = null
        scope.launch {
            try {
                val a = g.api.testAlert()
                g.store.mergeAlerts(listOf(a))
                testResult = if (a.channels.isEmpty()) {
                    "Recorded at ${formatTime(a.ts)}, but no channel sent it. Check the Telegram and push setup on the Server, and the alert channels under Device."
                } else {
                    "Sent at ${formatTime(a.ts)} via ${a.channels.joinToString(", ") { channelName(it) }}."
                }
                Toast.makeText(ctx, "Test alert sent.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                testError = e.userMessage()
            } finally {
                testBusy = false
            }
        }
    }
}

private fun channelName(c: String) = when (c) {
    "telegram" -> "Telegram"
    "push" -> "push notifications"
    else -> c
}

internal fun LazyListScope.accountItems(s: AccountState, session: SessionState) {
    val user = session.user
    item(key = "acc-profile") {
        val g = graph()
        SectionCard(title = "Account") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user?.username ?: "?", session.isAdmin, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user?.username ?: "", style = MaterialTheme.typography.titleLarge, color = hsm.fg)
                    Pill(if (session.isAdmin) "Admin" else "Viewer", if (session.isAdmin) Tone.INFO else Tone.NEUTRAL)
                }
            }
            Spacer(Modifier.height(12.dp))
            KeyValueRow("Server", session.serverUrl.removePrefix("https://"))
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { g.repo.signOut() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }

    item(key = "acc-totp") {
        val g = graph()
        SectionCard(title = "Two-factor authentication") {
            if (user?.totpEnabled == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("On", Tone.OK, dot = true)
                    Spacer(Modifier.width(10.dp))
                    Text("Signing in needs a code from your authenticator app.", style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "New phone? Move your account to a new authenticator. It asks for your password and a current code, then takes you straight to the setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { s.moveTotp = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Outlined.PhonelinkLock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Set up a new authenticator…")
                }
            } else {
                Notice(Tone.WARN, "Not set up", "Every account needs two-factor authentication.") {
                    Button(onClick = { user?.let { g.session.updateMe(it, true) } }) { Text("Set up now") }
                }
            }
        }
    }

    item(key = "acc-password") {
        val g = graph()
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val vt = if (s.showPasswords) VisualTransformation.None else PasswordVisualTransformation()
        val eye: @Composable () -> Unit = {
            IconButton(onClick = { s.showPasswords = !s.showPasswords }) {
                Icon(if (s.showPasswords) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (s.showPasswords) "Hide passwords" else "Show passwords")
            }
        }
        SectionCard(title = "Change password") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.current, onValueChange = { s.current = it }, label = { Text("Current password") }, singleLine = true,
                    visualTransformation = vt, trailingIcon = eye,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.next, onValueChange = { s.next = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = vt,
                    supportingText = { Text("At least $MIN_PASSWORD characters. Your other devices are signed out.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.repeat, onValueChange = { s.repeat = it }, label = { Text("Repeat new password") }, singleLine = true,
                    visualTransformation = vt,
                    isError = s.repeat.isNotEmpty() && s.repeat != s.next,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                s.pwError?.let { Text(it, color = hsm.danger, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = { s.changePassword(g, ctx.applicationContext, scope) },
                    enabled = !s.pwBusy && s.current.isNotEmpty() && s.next.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (s.pwBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent) else Text("Change password")
                }
            }
        }
    }

    item(key = "acc-push") { PushCard(s, session) }
}

@Composable
private fun PushCard(s: AccountState, session: SessionState) {
    val g = graph()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val push by g.push.status.collectAsStateWithLifecycle()
    var systemAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        systemAllowed = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    SectionCard(title = "Push notifications") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This phone", style = MaterialTheme.typography.bodyLarge, color = hsm.fg, modifier = Modifier.weight(1f))
            when (push) {
                PushStatus.ON -> Pill("On", Tone.OK, dot = true)
                PushStatus.REGISTERING -> Pill("Connecting", Tone.INFO)
                PushStatus.FAILED -> Pill("Failed", Tone.DANGER, dot = true)
                PushStatus.OFF -> Pill("Off", Tone.NEUTRAL)
                PushStatus.NOT_CONFIGURED -> Pill("Not set up", Tone.NEUTRAL)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (push) {
                PushStatus.ON -> "Alerts arrive as notifications, even when the app is closed."
                PushStatus.REGISTERING -> "Registering this phone with the Server…"
                PushStatus.FAILED -> "Couldn't register this phone for push notifications."
                PushStatus.OFF -> "This phone is not registered for push notifications yet."
                PushStatus.NOT_CONFIGURED -> "Firebase is not configured in this build of the app, so it cannot receive alerts while closed. Alerts still go to Telegram and appear under Alerts."
            },
            style = MaterialTheme.typography.bodySmall,
            color = hsm.muted,
        )
        if (push == PushStatus.FAILED || push == PushStatus.OFF) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { g.push.register() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (push == PushStatus.FAILED) "Try again" else "Turn on")
            }
        }
        if (!systemAllowed && push != PushStatus.NOT_CONFIGURED) {
            Spacer(Modifier.height(10.dp))
            Notice(Tone.WARN, "Notifications are blocked", "Android is not showing notifications from this app.") {
                OutlinedButton(onClick = { openNotificationSettings(ctx) }) { Text("Open notification settings") }
            }
        }

        val cfg = s.config
        if (cfg != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = hsm.border)
            Spacer(Modifier.height(10.dp))
            Text("SERVER CHANNELS", style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelRow("Telegram", cfg.telegramConfigured)
                ChannelRow("App push (Firebase)", cfg.fcmConfigured)
                ChannelRow("Browser push", cfg.pushConfigured)
            }
        }

        if (session.canControl) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { s.sendTest(g, ctx.applicationContext, scope) },
                enabled = !s.testBusy,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (s.testBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send test alert")
                }
            }
            s.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.ok, modifier = Modifier.padding(top = 6.dp)) }
            s.testError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.danger, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun ChannelRow(label: String, configured: Boolean) {
    KeyValueRow(label, "") {
        Pill(if (configured) "Configured" else "Not configured", if (configured) Tone.OK else Tone.NEUTRAL)
    }
}

private fun openNotificationSettings(ctx: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

@Composable
internal fun AccountDialogs(s: AccountState) {
    if (!s.moveTotp) return
    val g = graph()
    val ctx = LocalContext.current.applicationContext
    ReauthDialog(
        title = "Move to a new authenticator?",
        text = "Every account needs two-factor authentication. This turns it off for your account and takes you straight to setting it up again, " +
            "for example with an authenticator on a new phone.",
        confirmLabel = "Turn off and set up again",
        askCode = true,
        danger = true,
        onDismiss = { s.moveTotp = false },
        onConfirm = { password, code ->
            g.api.totpDisable(password, code.orEmpty())
            s.moveTotp = false
            // The session now needs enrollment again: the app switches to the setup screen.
            runCatching { g.repo.refreshMe() }.onFailure {
                g.session.current.user?.let { u -> g.session.updateMe(u.copy(totpEnabled = false), true) }
            }
            Toast.makeText(ctx, "Two-factor authentication is off. Set up your new authenticator now.", Toast.LENGTH_LONG).show()
        },
    )
}
