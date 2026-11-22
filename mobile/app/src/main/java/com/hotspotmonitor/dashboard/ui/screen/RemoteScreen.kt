package com.hotspotmonitor.dashboard.ui.screen

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.DASH
import com.hotspotmonitor.dashboard.data.ScreenSessionResponse
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.ui.components.EqualRow
import com.hotspotmonitor.dashboard.ui.components.KeyValueRow
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlin.math.roundToLong

private data class Quality(val id: String, val label: String, val maxSize: Int, val fps: Int, val mbPerHour: Int)

private val QUALITIES = listOf(
    Quality("low", "Low", 960, 15, 360),
    Quality("standard", "Standard", 1280, 24, 900),
    Quality("high", "High", 1600, 30, 1800),
)

/** Remote screen: live view and control of the hotspot phone (admins; password + fresh code). */
@Composable
fun RemoteScreen() {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    var quality by rememberSaveable { mutableStateOf("standard") }
    var asking by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<ScreenSessionResponse?>(null) }

    val current = live
    if (current != null) {
        LiveScreen(current, onDone = { live = null })
        return
    }

    val snap = deviceId?.let { data.snapshots[it] }
    val cap = snap?.groups?.capabilities?.get("screen_mirror")
    val blocker = when {
        !session.canControl -> "Only admins with two-factor authentication can use the remote screen."
        snap == null -> "Loading the phone…"
        !snap.online -> "The phone is offline. The screen needs a live connection."
        cap == null -> "This phone has not reported remote-screen support yet. Install the latest Agent on it."
        !cap.supported -> "This phone cannot stream its screen: ${cap.note ?: "not supported"}."
        else -> null
    }
    val q = QUALITIES.first { it.id == quality }

    Page(title = "Screen", subtitle = "See and control the hotspot phone's screen live.") {
        item {
            SectionCard(title = "Start the remote screen") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "The phone streams its screen through your server and your taps go back to it. It runs only while this screen is open, for at most 30 minutes, and every session is recorded in the timeline.",
                        style = MaterialTheme.typography.bodyMedium, color = hsm.muted,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        QUALITIES.forEachIndexed { i, opt ->
                            SegmentedButton(selected = quality == opt.id, onClick = { quality = opt.id }, shape = SegmentedButtonDefaults.itemShape(i, QUALITIES.size)) {
                                Text(opt.label)
                            }
                        }
                    }
                    Text(
                        "${q.label}: up to ${q.maxSize} px, ${q.fps} frames/s. Uses the hotspot's mobile data: at most about ${q.mbPerHour} MB per hour while the screen changes, far less while it is still.",
                        style = MaterialTheme.typography.bodySmall, color = hsm.faint,
                    )
                    if (blocker != null) Notice(Tone.WARN, null, blocker)
                    Button(onClick = { asking = true }, enabled = blocker == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.ScreenShare, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start screen")
                    }
                }
            }
        }
    }

    if (asking) {
        ReauthDialog(
            title = "Start the remote screen?",
            text = "Anyone with the screen can control the phone. Confirm with your password and a fresh code from your authenticator app.",
            confirmLabel = "Start",
            askCode = true,
            onDismiss = { asking = false },
        ) { password, code ->
            val id = deviceId ?: throw IllegalStateException("No phone selected.")
            try {
                live = g.api.createScreenSession(id, quality, password, code!!)
                asking = false
            } catch (e: ApiException) {
                if (e.isCode("device_offline")) throw IllegalStateException("The phone went offline.")
                throw e
            }
        }
    }
}

@Composable
private fun LiveScreen(session: ScreenSessionResponse, onDone: () -> Unit) {
    val g = graph()
    val client = remember(session.sessionId) {
        ScreenClient(g.http, g.session.current.serverUrl, g.session.current.token ?: "", session.sessionId, g.scope)
    }
    val state by client.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = rememberNow(1000)
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    DisposableEffect(client, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> client.pause()
                Lifecycle.Event.ON_START -> client.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            // Leaving the screen ends the session (no video nobody watches).
            if (client.state.value.phase != ScreenPhase.ENDED) client.stop()
            client.close()
        }
    }

    val ended = state.phase == ScreenPhase.ENDED
    val left = ((isoMs(session.expiresAt) ?: now) - now).coerceAtLeast(0) / 1000
    val aspect = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 9f / 20f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Screen", style = MaterialTheme.typography.headlineMedium, color = hsm.fg, modifier = Modifier.weight(1f))
            val (label, tone) = when (state.phase) {
                ScreenPhase.CONNECTING -> "Connecting" to Tone.INFO
                ScreenPhase.WAITING -> "Waiting for the phone" to Tone.INFO
                ScreenPhase.STARTING -> "Starting" to Tone.INFO
                ScreenPhase.STREAMING -> "Live" to Tone.OK
                ScreenPhase.RECONNECTING -> "Reconnecting" to Tone.WARN
                ScreenPhase.ENDED -> "Ended" to Tone.NEUTRAL
            }
            Pill(label, tone, dot = true)
        }

        // The phone, in a rounded frame sized to the video.
        Box(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 640.dp)
                .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(28.dp)).background(Color.Black)
                .border(4.dp, hsm.surface3, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        keepScreenOn = true
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = client.attachSurface(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                            override fun surfaceDestroyed(h: SurfaceHolder) = client.detachSurface()
                        })
                    }
                },
            )
            // Touch layer: every pointer becomes a touch on the phone, in fractions of the frame.
            Box(
                Modifier.fillMaxSize().pointerInput(client) {
                    val ids = HashMap<Long, Int>()
                    val lastMove = HashMap<Long, Long>()
                    awaitEachGesture {
                        while (true) {
                            val ev = awaitPointerEvent()
                            for (c in ev.changes) {
                                val x = c.position.x / size.width
                                val y = c.position.y / size.height
                                when {
                                    c.pressed && !c.previousPressed -> {
                                        val used = ids.values.toSet()
                                        val idx = (0..9).firstOrNull { it !in used } ?: continue
                                        ids[c.id.value] = idx
                                        client.touch("down", idx, x, y)
                                    }
                                    !c.pressed && c.previousPressed -> {
                                        ids.remove(c.id.value)?.let { client.touch("up", it, x, y) }
                                        lastMove.remove(c.id.value)
                                    }
                                    c.pressed && ev.type == PointerEventType.Move -> {
                                        val idx = ids[c.id.value] ?: continue
                                        val t = c.uptimeMillis
                                        if (t - (lastMove[c.id.value] ?: 0) >= 16) {
                                            lastMove[c.id.value] = t
                                            client.touch("move", idx, x, y)
                                        }
                                    }
                                }
                                c.consume()
                            }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                },
            )
            if (state.phase != ScreenPhase.STREAMING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    if (!ended) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (ended) state.message ?: "Ended." else when (state.phase) {
                            ScreenPhase.WAITING -> "Waiting for the phone…"
                            ScreenPhase.STARTING -> "Starting the stream…"
                            ScreenPhase.RECONNECTING -> "Reconnecting…"
                            else -> "Connecting…"
                        },
                        color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // The phone's own navigation bar.
        Row(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hsm.surface).padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavKey(Icons.Outlined.Menu, "Recents", !ended) { client.key("recents") }
            NavKey(Icons.Outlined.CropSquare, "Home", !ended) { client.key("home") }
            NavKey(Icons.AutoMirrored.Outlined.ArrowBack, "Back", !ended) { client.key("back") }
        }

        SectionCard(title = "Buttons") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EqualRow(spacing = 8.dp) {
                    QuickButton(Icons.Outlined.PowerSettingsNew, "Power", !ended, Modifier.weight(1f)) { client.key("power") }
                    QuickButton(Icons.AutoMirrored.Outlined.VolumeDown, "Vol −", !ended, Modifier.weight(1f)) { client.key("volume_down") }
                    QuickButton(Icons.AutoMirrored.Outlined.VolumeUp, "Vol +", !ended, Modifier.weight(1f)) { client.key("volume_up") }
                }
                EqualRow(spacing = 8.dp) {
                    QuickButton(Icons.Outlined.Notifications, "Notifications", !ended, Modifier.weight(1f)) { client.panel("notifications") }
                    QuickButton(Icons.Outlined.Settings, "Quick settings", !ended, Modifier.weight(1f)) { client.panel("settings") }
                }
                EqualRow(spacing = 8.dp) {
                    QuickButton(Icons.Outlined.Keyboard, if (typing) "Hide keyboard" else "Type text", !ended, Modifier.weight(1f)) { typing = !typing }
                    QuickButton(Icons.Outlined.Refresh, "Refresh", !ended, Modifier.weight(1f)) { client.requestKeyframe(force = true) }
                }
                if (typing && !ended) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Text to type on the phone") },
                        supportingText = { Text("Tap a text field on the phone first.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (text.isNotEmpty()) { client.text(text); text = "" } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EqualRow(spacing = 8.dp) {
                        Button(onClick = { client.text(text); text = "" }, enabled = text.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Send") }
                        TextButton(onClick = { client.key("delete") }, modifier = Modifier.weight(1f)) { Text("⌫ Delete") }
                        TextButton(onClick = { client.key("enter") }, modifier = Modifier.weight(1f)) { Text("↵ Enter") }
                    }
                }
            }
        }

        SectionCard(title = "Session") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValueRow("Quality", QUALITIES.firstOrNull { it.id == session.quality }?.label ?: session.quality)
                KeyValueRow("Picture", if (state.width > 0) "${state.width} × ${state.height}" else DASH)
                KeyValueRow("Frames", if (state.phase == ScreenPhase.STREAMING) "${state.fps}/s" else DASH)
                val mb = state.bytes / 1_048_576.0
                KeyValueRow("Data used", if (mb >= 10) "${mb.roundToLong()} MB" else String.format("%.1f MB", mb))
                KeyValueRow("Time left", if (ended) DASH else "${left / 60}:${(left % 60).toString().padStart(2, '0')}")
            }
            Spacer(Modifier.height(14.dp))
            if (ended) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Start a new session") }
            } else {
                Button(
                    onClick = { client.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = hsm.danger, contentColor = hsm.bg),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop screen") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun NavKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = label, tint = if (enabled) hsm.fg else hsm.faint)
    }
}

@Composable
private fun QuickButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = hsm.muted, maxLines = 1)
    }
}
