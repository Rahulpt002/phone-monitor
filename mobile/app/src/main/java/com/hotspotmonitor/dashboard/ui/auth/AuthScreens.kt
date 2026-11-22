package com.hotspotmonitor.dashboard.ui.auth

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.TotpSetupResponse
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.QrCode
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch

@Composable
private fun AuthFrame(subtitle: String, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SignalCellularAlt, null, tint = hsm.ok, modifier = Modifier.size(44.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Hotspot Monitor", style = MaterialTheme.typography.headlineMedium, color = hsm.fg)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                }
            }
            content()
        }
    }
}

/** Sign-in with password, then the 6-digit authenticator code when the account has TOTP. */
@Composable
fun LoginScreen() {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(session.user?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(session.serverUrl) }

    fun message(e: Exception): String = when {
        e is ApiException && e.status == 401 && pending == null -> "Wrong username or password."
        e is ApiException && e.status == 401 -> e.message?.takeIf { it.isNotBlank() } ?: "Wrong or already used code."
        else -> e.userMessage()
    }

    fun submitPassword() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        g.session.clearNotice()
        if (server.trim() != session.serverUrl) g.session.setServer(server)
        scope.launch {
            try {
                val r = g.api.login(username.trim(), password)
                if (r.totpRequired && r.pendingToken != null) {
                    pending = r.pendingToken
                    code = ""
                } else if (r.token != null && r.user != null) {
                    g.session.signedIn(r.token, r.user, enrollmentRequired = !r.user.totpEnabled, deviceToken = r.deviceToken)
                }
            } catch (e: Exception) {
                error = message(e)
            } finally {
                busy = false
            }
        }
    }

    fun submitCode() {
        val p = pending ?: return
        if (busy || code.length != 6) return
        busy = true
        error = null
        scope.launch {
            try {
                val s = g.api.totpLogin(p, code, username.trim())
                g.session.signedIn(s.token, s.user, enrollmentRequired = false, deviceToken = s.deviceToken)
            } catch (e: Exception) {
                error = message(e)
                code = ""
                if (e is ApiException && e.isCode("unauthorized") && e.message?.contains("expired") == true) pending = null
            } finally {
                busy = false
            }
        }
    }

    AuthFrame(if (pending == null) "Sign in to continue" else "Two-factor authentication") {
        session.notice?.let { Notice(Tone.WARN, null, it) }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (pending == null) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Enter the 6-digit code from your authenticator app.", color = hsm.muted, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.filter { it.isDigit() }.take(6)
                            if (code.length == 6) submitCode()
                        },
                        label = { Text("Authenticator code") }, singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitCode() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) Text(error!!, color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { if (pending == null) submitPassword() else submitCode() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = hsm.onAccent)
                    else Text(if (pending == null) "Sign in" else "Verify", fontWeight = FontWeight.SemiBold)
                }
                if (pending != null) {
                    TextButton(onClick = { pending = null; code = ""; error = null }) { Text("Back", color = hsm.accent) }
                }
            }
        }
        if (pending == null) {
            TextButton(onClick = { showServer = !showServer }) {
                Text(if (showServer) "Hide server address" else "Server: ${session.serverUrl.removePrefix("https://")}", color = hsm.muted)
            }
            if (showServer) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, label = { Text("Server address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Two-factor setup: every account needs an authenticator app before the Dashboard opens. */
@Composable
fun EnrollScreen() {
    val g = graph()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var setup by remember { mutableStateOf<TotpSetupResponse?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        busy = true
        error = null
        scope.launch {
            try {
                setup = g.api.totpSetup()
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun enable() {
        if (busy || code.length != 6) return
        busy = true
        error = null
        scope.launch {
            try {
                g.api.totpEnable(code)
                g.repo.refreshMe()
            } catch (e: Exception) {
                error = if (e is ApiException && e.isCode("bad_request")) "Wrong code. Check the time on your phone and try again." else e.userMessage()
                code = ""
            } finally {
                busy = false
            }
        }
    }

    AuthFrame("Set up two-factor authentication") {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val s = setup
                if (s == null) {
                    Text(
                        "The app shows the phone's location, network details and controls, so signing in takes your password and a code from an authenticator app (Google Authenticator, Aegis, 1Password…). Set it up once now.",
                        color = hsm.fg, style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { start() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = hsm.onAccent) else Text("Start setup")
                    }
                } else {
                    Text("1. Add this account to your authenticator app.", style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                    OutlinedButton(onClick = { openAuthenticator(ctx, s.otpauthUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add to authenticator app")
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(s.otpauthUrl) }
                    Text("Or enter this key by hand (time based):", color = hsm.muted, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, color = hsm.fg, modifier = Modifier.weight(1f))
                        TextButton(onClick = { copy(ctx, s.secret) }) { Text("Copy", color = hsm.accent) }
                    }
                    Text("2. Enter the code it shows.", style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.filter { it.isDigit() }.take(6)
                            if (code.length == 6) enable()
                        },
                        label = { Text("Authenticator code") }, singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { enable() }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = hsm.onAccent) else Text("Turn on two-factor")
                    }
                }
                if (error != null) Text(error!!, color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(onClick = { g.repo.signOut() }) { Text("Sign out", color = hsm.muted) }
    }
}

private fun openAuthenticator(ctx: Context, otpauth: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(otpauth)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No authenticator app found. Install Google Authenticator, or scan the QR code.", Toast.LENGTH_LONG).show()
    }
}

fun copy(ctx: Context, text: String, label: String = "Copied") {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
}
