package com.hotspotmonitor.dashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size

/** Yes/no confirmation. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, color = hsm.muted) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = hsm.danger, contentColor = hsm.bg) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = hsm.surface,
    )
}

/**
 * Asks for the signed-in user's password, and optionally a fresh authenticator code, before a
 * sensitive action. [onConfirm] does the action; throwing keeps the dialog open with the message.
 */
@Composable
fun ReauthDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    askCode: Boolean = false,
    danger: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: suspend (password: String, code: String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        if (password.isEmpty()) {
            error = "Enter your password to confirm."
            return
        }
        if (askCode && !Regex("^\\d{6}$").matches(code.trim())) {
            error = "Enter the 6-digit code from your authenticator app."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                onConfirm(password, if (askCode) code.trim() else null)
            } catch (e: Exception) {
                error = when {
                    e is ApiException && e.isCode("reauth_required") -> "Wrong password. Try again."
                    e is ApiException && e.isCode("bad_request") && askCode -> {
                        code = ""
                        "Wrong or already used code. Wait for the next code and try again."
                    }
                    e is ApiException && e.isCode("locked") -> e.message ?: "Too many failed attempts. Try again later."
                    else -> e.userMessage()
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (text != null) Text(text, color = hsm.muted, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke()
                if (askCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        label = { Text("Authenticator code") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Your password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error!!, color = hsm.danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !busy,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = hsm.danger, contentColor = hsm.bg) else ButtonDefaults.buttonColors(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.bg) else Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        containerColor = hsm.surface,
    )
}
