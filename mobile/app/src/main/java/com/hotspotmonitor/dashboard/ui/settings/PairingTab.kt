package com.hotspotmonitor.dashboard.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.DataState
import com.hotspotmonitor.dashboard.data.DeviceSummary
import com.hotspotmonitor.dashboard.data.PairCodeResponse
import com.hotspotmonitor.dashboard.data.SessionState
import com.hotspotmonitor.dashboard.data.formatCountdown
import com.hotspotmonitor.dashboard.data.formatDate
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.auth.copy
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ProgressBar
import com.hotspotmonitor.dashboard.ui.components.QrCode
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class PairingState {
    var name by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pair by mutableStateOf<PairCodeResponse?>(null)
    var createdAtMs by mutableLongStateOf(0L)
    /** Phones paired before the current code was made (to spot the new one). */
    var knownIds: Set<String> = emptySet()
    /** Name of the phone that just paired with the current code. */
    var paired by mutableStateOf<String?>(null)

    var renaming by mutableStateOf<DeviceSummary?>(null)
    var renameText by mutableStateOf("")
    var renameBusy by mutableStateOf(false)
    var renameError by mutableStateOf<String?>(null)
    var unpairing by mutableStateOf<DeviceSummary?>(null)

    fun create(g: AppGraph, scope: CoroutineScope) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val r = g.api.createPairCode(name.trim().ifBlank { null })
                knownIds = g.store.current.devices.map { it.deviceId }.toSet()
                createdAtMs = System.currentTimeMillis()
                paired = null
                pair = r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun startRename(d: DeviceSummary) {
        renameText = d.name
        renameError = null
        renaming = d
    }

    fun rename(g: AppGraph, ctx: Context, scope: CoroutineScope) {
        val d = renaming ?: return
        val n = renameText.trim()
        if (n.isEmpty() || n.length > 60) {
            renameError = "Name must be 1 to 60 characters."
            return
        }
        renameBusy = true
        scope.launch {
            try {
                g.api.renameDevice(d.deviceId, n)
                renaming = null
                g.repo.reload()
                Toast.makeText(ctx, "Renamed.", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                renameError = e.userMessage()
            } finally {
                renameBusy = false
            }
        }
    }
}

/** While a code is valid, watch for the new phone so it shows up without a manual refresh. */
@Composable
internal fun PairingPolling(s: PairingState) {
    val g = graph()
    val ctx = LocalContext.current.applicationContext
    val p = s.pair
    LaunchedEffect(p) {
        val exp = isoMs(p?.expiresAt) ?: return@LaunchedEffect
        while (System.currentTimeMillis() < exp) {
            delay(5000)
            val list = runCatching { g.api.devices() }.getOrNull() ?: continue
            val fresh = list.firstOrNull { it.deviceId !in s.knownIds } ?: continue
            val label = fresh.name.ifBlank { fresh.deviceId }
            s.paired = label
            g.repo.reload()
            Toast.makeText(ctx, "Phone paired: $label", Toast.LENGTH_LONG).show()
            s.pair = null
            break
        }
    }
}

internal fun LazyListScope.pairingItems(s: PairingState, session: SessionState, data: DataState, deviceId: String?) {
    val canControl = session.canControl
    item(key = "pair-create") { PairCard(s, canControl) }
    item(key = "pair-list") {
        val devices = data.devices
        SectionCard(title = if (devices.isEmpty()) "Paired phones" else "Paired phones (${devices.size})") {
            if (devices.isEmpty()) {
                EmptyState("No phone paired yet.")
            } else {
                val now = rememberNow(15_000)
                devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = hsm.border.copy(alpha = 0.6f), modifier = Modifier.padding(start = 52.dp))
                    DeviceRow(d, selected = d.deviceId == deviceId, canControl = canControl, now = now, s = s)
                }
            }
        }
    }
}

@Composable
private fun PairCard(s: PairingState, canControl: Boolean) {
    val g = graph()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pair = s.pair

    SectionCard(title = "Pair a phone") {
        Text(
            "Make a one-time code, then scan it with the hotspot phone (Agent app) or run the adb command from a PC.",
            style = MaterialTheme.typography.bodyMedium,
            color = hsm.muted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = s.name,
            onValueChange = { s.name = it.take(60) },
            label = { Text("Name for the phone (optional)") },
            placeholder = { Text("Roof hotspot") },
            singleLine = true,
            enabled = canControl,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { s.create(g, scope) }, enabled = canControl && !s.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (s.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
            } else {
                Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (pair != null) "New pairing code" else "Create pairing code")
            }
        }
        s.error?.let {
            Spacer(Modifier.height(10.dp))
            Notice(Tone.DANGER, null, it)
        }
        s.paired?.let {
            Spacer(Modifier.height(10.dp))
            Notice(Tone.OK, "Paired: $it", "The phone is connected. It now appears in the list below.")
        }
        if (pair != null) PairCode(s, pair)
    }
}

/** The live code: QR, one-time code, countdown and the three ways to use it. */
@Composable
private fun PairCode(s: PairingState, pair: PairCodeResponse) {
    val g = graph()
    val ctx = LocalContext.current
    val session by g.session.state.collectAsStateWithLifecycle()
    val now = rememberNow(1000)
    val expiresAt = isoMs(pair.expiresAt)
    val left = if (expiresAt != null) expiresAt - now else 0L
    if (left <= 0) {
        Spacer(Modifier.height(10.dp))
        Notice(Tone.WARN, null, "The pairing code expired. Create a new one.")
        return
    }
    Column {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(pair.deepLink, size = 232.dp) }
        Spacer(Modifier.height(14.dp))
        Text("ONE-TIME CODE", style = MaterialTheme.typography.labelSmall, color = hsm.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                pair.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                letterSpacing = 6.sp,
                color = hsm.fg,
            )
            IconButton(onClick = { copy(ctx, pair.code, "Code copied") }) { Icon(Icons.Outlined.ContentCopy, "Copy code", tint = hsm.accent) }
        }
        val total = ((expiresAt ?: 0L) - s.createdAtMs).coerceAtLeast(1L)
        val soon = left < 60_000
        Text(
            "Expires in ${formatCountdown(left)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (soon) hsm.warn else hsm.fg,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        ProgressBar(100.0 * left / total, tone = if (soon) Tone.WARN else Tone.INFO)
        Spacer(Modifier.height(4.dp))
        Text(
            "Waiting for the phone… it shows up here by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.faint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        Step(1, "Scan the QR code with the hotspot phone's camera; it opens the Agent's pairing screen.")
        Step(2, "Or, for a phone with a dead screen, run this from a PC with the phone on USB:")
        Text(
            pair.adbCommand,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = hsm.fg,
            modifier = Modifier
                .padding(start = 34.dp, top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(hsm.surface2)
                .padding(10.dp),
        )
        Row(Modifier.padding(start = 26.dp)) {
            TextButton(onClick = { copy(ctx, pair.adbCommand, "ADB command copied") }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy ADB command")
            }
        }
        Step(3, "Or type the server address and the code into the Agent's manual pairing form:")
        Row(Modifier.padding(start = 34.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.serverUrl,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = hsm.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copy(ctx, session.serverUrl, "Server address copied") }) {
                Icon(Icons.Outlined.ContentCopy, "Copy server address", tint = hsm.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(hsm.infoSoft), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = hsm.accent)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = hsm.fg, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DeviceRow(d: DeviceSummary, selected: Boolean, canControl: Boolean, now: Long, s: PairingState) {
    val g = graph()
    var menu by remember { mutableStateOf(false) }
    val info = d.deviceInfo
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (d.online) hsm.okSoft else hsm.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = if (d.online) hsm.ok else hsm.muted, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                d.name.ifBlank { d.deviceId },
                style = MaterialTheme.typography.titleMedium,
                color = hsm.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (d.online) "Online" else "Offline", if (d.online) Tone.OK else Tone.DANGER, dot = true)
                if (selected) Pill("Selected", Tone.INFO)
            }
            if (info != null) {
                Text(
                    "${info.manufacturer} ${info.model} · Android ${info.androidVersion} · Agent ${info.agentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
            }
            Text(
                (if (d.online) "Online now" else "Last seen ${formatRelative(d.lastSeen, now)}") +
                    (if (d.pairedAt.isNotBlank()) " · paired ${formatDate(d.pairedAt)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
            )
            Text(d.deviceId, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = hsm.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${d.name}", tint = hsm.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!selected) {
                    DropdownMenuItem(
                        text = { Text("Show this phone") },
                        leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                        onClick = {
                            menu = false
                            g.repo.selectDevice(d.deviceId)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Shown on the dashboard") },
                        leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                        enabled = false,
                        onClick = {},
                    )
                }
                if (canControl) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = {
                            menu = false
                            s.startRename(d)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Unpair", color = hsm.danger) },
                        leadingIcon = { Icon(Icons.Outlined.LinkOff, null, tint = hsm.danger) },
                        onClick = {
                            menu = false
                            s.unpairing = d
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PairingDialogs(s: PairingState) {
    val g = graph()
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    s.renaming?.let { d ->
        AlertDialog(
            onDismissRequest = { if (!s.renameBusy) s.renaming = null },
            title = { Text("Rename ${d.name.ifBlank { "phone" }}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = s.renameText,
                        onValueChange = { s.renameText = it.take(60) },
                        label = { Text("New name") },
                        singleLine = true,
                        isError = s.renameError != null,
                        supportingText = { Text(s.renameError ?: "1 to 60 characters.") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { s.rename(g, ctx, scope) }, enabled = !s.renameBusy) {
                    if (s.renameBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent) else Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { s.renaming = null }, enabled = !s.renameBusy) { Text("Cancel") } },
            containerColor = hsm.surface,
        )
    }

    s.unpairing?.let { d ->
        key(d.deviceId) {
            ReauthDialog(
                title = "Unpair ${d.name.ifBlank { d.deviceId }}?",
                text = "Its device token is revoked immediately; the phone shows \"Not paired\" and needs a new pairing code. History is kept.",
                confirmLabel = "Unpair",
                danger = true,
                onDismiss = { s.unpairing = null },
                onConfirm = { password, _ ->
                    g.api.unpairDevice(d.deviceId, password)
                    s.unpairing = null
                    g.repo.reload()
                    Toast.makeText(ctx, "${d.name.ifBlank { "Phone" }} unpaired.", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
