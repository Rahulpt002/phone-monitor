package com.hotspotmonitor.dashboard.ui.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.SmsRecord
import com.hotspotmonitor.dashboard.data.formatRelative
import com.hotspotmonitor.dashboard.data.formatTime
import com.hotspotmonitor.dashboard.data.isoMs
import com.hotspotmonitor.dashboard.ui.alerts.dayLabel
import com.hotspotmonitor.dashboard.ui.auth.copy
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.commandStatus
import com.hotspotmonitor.dashboard.ui.components.rememberNow
import com.hotspotmonitor.dashboard.ui.finder.CommandDialogs
import com.hotspotmonitor.dashboard.ui.finder.CommandRunner
import com.hotspotmonitor.dashboard.ui.finder.inFlight
import com.hotspotmonitor.dashboard.ui.finder.rememberCommandRunner
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// --- rules shared with the web Messages screen, the Server and the Agent -------------------------

private val PHONE_SEPARATORS = Regex("[ \\t\\n\\u000B\\u000C\\r().-]")
private val PHONE = Regex("^\\+?[0-9]{2,20}$")
private val USSD = Regex("^[*#][0-9*#]{1,30}#$")
private const val SMS_MAX = 918

/** Separators removed, optional + and 2 to 20 digits; null when it is not a phone number (shared normalizePhoneNumber). */
fun normalizePhoneNumber(value: String): String? = value.replace(PHONE_SEPARATORS, "").takeIf { PHONE.matches(it) }

private const val GSM7 = "\n\r !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_abcdefghijklmnopqrstuvwxyz{|}~" +
    "£¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ¡ÄÖÑÜ§¿äöñüà"

/** GSM-7 messages split at 153 chars per part, anything else (UCS-2) at 67. */
fun smsSegments(body: String): Pair<Int, Int> {
    val gsm = body.all { it in GSM7 }
    val single = if (gsm) 160 else 70
    val multi = if (gsm) 153 else 67
    if (body.length <= single) return (if (body.isEmpty()) 0 else 1) to single
    return ((body.length + multi - 1) / multi) to multi
}

private enum class Tab(val label: String) { INBOX("Inbox"), SEND("Send SMS"), USSD("USSD") }

@Composable
fun MessagesScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val runner = rememberCommandRunner()
    val now = rememberNow(30_000)

    var tab by rememberSaveable { mutableStateOf(Tab.INBOX) }
    var dir by rememberSaveable { mutableStateOf("all") }
    var to by rememberSaveable { mutableStateOf("") }
    var ussdCode by rememberSaveable { mutableStateOf("") }

    if (!session.isAdmin) {
        Page(title = "Messages") {
            item { Notice(Tone.INFO, "Admins only", "Text messages and USSD codes of the hotspot phone are visible to admin accounts only.") }
        }
        return
    }
    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    val all = remember(data.sms, deviceId) { data.sms.filter { it.deviceId == deviceId } }
    val received = all.count { it.direction == "in" }
    val list = all.filter { dir == "all" || it.direction == dir }
    val commands = remember(data.commands, deviceId) { data.commands.filter { it.deviceId == deviceId } }

    Page(
        title = "Messages",
        subtitle = "The SIM's text messages and USSD codes (balance and plan checks).",
        refreshing = loading,
        onRefresh = { g.repo.reloadDevice() },
    ) {
        if (loadError != null) item { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        if (!session.canControl) item { Notice(Tone.WARN, "Two-factor authentication needed", "Set up two-factor authentication to send messages or run codes.") }

        item(key = "tabs") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Tab.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t },
                        shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                    ) { Text(if (t == Tab.INBOX && all.isNotEmpty()) "${t.label} (${all.size})" else t.label, maxLines = 1) }
                }
            }
        }

        when (tab) {
            Tab.INBOX -> {
                item(key = "filters") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirChip("All (${all.size})", dir == "all") { dir = "all" }
                        DirChip("Received ($received)", dir == "in") { dir = "in" }
                        DirChip("Sent (${all.size - received})", dir == "out") { dir = "out" }
                    }
                }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        SectionCard {
                            EmptyState(if (all.isEmpty()) "No messages." else "No messages in this view.")
                            Text(
                                "Carrier messages about plan expiry and quota arrive here (and as alerts).",
                                style = MaterialTheme.typography.bodySmall, color = hsm.muted,
                            )
                        }
                    }
                } else {
                    var lastDay: String? = null
                    for (m in list) {
                        val day = dayLabel(isoMs(m.at), now)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day_${m.id}") {
                                Text(day, style = MaterialTheme.typography.labelLarge, color = hsm.muted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        item(key = "sms_${m.id}") {
                            SmsBubble(
                                m, now,
                                onReply = if (m.direction == "in" && session.canControl && normalizePhoneNumber(m.peer) != null) {
                                    { to = m.peer; tab = Tab.SEND }
                                } else null,
                            )
                        }
                    }
                }
            }
            Tab.SEND -> item(key = "send") {
                SendSms(runner, to, { to = it }, session.canControl, commands, now)
            }
            Tab.USSD -> item(key = "ussd") {
                Ussd(runner, ussdCode, { ussdCode = it }, session.canControl, commands, now)
            }
        }
    }
    CommandDialogs(runner)
}

@Composable
private fun DirChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = hsm.infoSoft, selectedLabelColor = hsm.fg),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsBubble(m: SmsRecord, now: Long, onReply: (() -> Unit)?) {
    val ctx = LocalContext.current
    val incoming = m.direction == "in"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (incoming) Alignment.Start else Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (incoming) "From" else "To", if (incoming) Tone.INFO else Tone.NEUTRAL)
            Spacer(Modifier.width(8.dp))
            Text(m.peer, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = if (incoming) 4.dp else 18.dp, topEnd = if (incoming) 18.dp else 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(if (incoming) hsm.surface2 else hsm.infoSoft)
                .combinedClickable(onClick = {}, onLongClick = { copy(ctx, m.body, "Message copied") })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(m.body, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text("${formatTime(m.at)} · ${formatRelative(m.at, now)}", style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            if (onReply != null) {
                Text(
                    "Reply",
                    style = MaterialTheme.typography.labelLarge,
                    color = hsm.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReply).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SendSms(
    runner: CommandRunner,
    to: String,
    setTo: (String) -> Unit,
    canControl: Boolean,
    commands: List<CommandRecord>,
    now: Long,
) {
    var body by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    var sentId by rememberSaveable { mutableStateOf<String?>(null) }
    val normalized = normalizePhoneNumber(to)
    val toErr = if (normalized == null) "Enter a phone number, e.g. +919876543210." else null
    val bodyErr = when {
        body.isBlank() -> "Write a message."
        body.length > SMS_MAX -> "At most $SMS_MAX characters (6 parts)."
        else -> null
    }
    val (parts, _) = smsSegments(body)
    val sent = sentId?.let { id -> commands.firstOrNull { it.id == id } }
    val busy = runner.isBusy("send_sms")

    SectionCard(title = "Send SMS") {
        Text("Sends a text message from the phone's SIM. Carrier rates apply.", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = to,
            onValueChange = { setTo(it.take(40)) },
            label = { Text("To") },
            placeholder = { Text("+919876543210") },
            singleLine = true,
            enabled = canControl,
            isError = touched && toErr != null,
            supportingText = if (touched && toErr != null) ({ Text(toErr) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Message") },
            minLines = 4,
            maxLines = 10,
            enabled = canControl,
            isError = (touched && bodyErr != null) || body.length > SMS_MAX,
            supportingText = {
                Text(
                    if (touched && bodyErr != null) bodyErr
                    else "${body.length} characters · $parts part${if (parts == 1) "" else "s"}",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                touched = true
                if (toErr == null && bodyErr == null) {
                    runner.run(
                        "send_sms",
                        buildJsonObject {
                            put("to", normalized!!)
                            put("body", body)
                        },
                        key = "send_sms",
                        detail = "“$body”",
                        onSent = {
                            sentId = it.id
                            body = ""
                            touched = false
                        },
                    )
                }
            },
            enabled = canControl && !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
                Spacer(Modifier.width(10.dp))
            }
            Text("Send")
        }
        if (sent != null) {
            Spacer(Modifier.height(12.dp))
            val look = commandStatus(sent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last message to ${sent.args.str("to") ?: "?"}: ", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                Pill(look.label, look.tone)
                if (sent.inFlight()) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = hsm.accent)
                }
                Spacer(Modifier.weight(1f))
                Text(formatRelative(sent.createdAt, now), style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            }
            sent.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.danger, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

@Composable
private fun Ussd(
    runner: CommandRunner,
    code: String,
    setCode: (String) -> Unit,
    canControl: Boolean,
    commands: List<CommandRecord>,
    now: Long,
) {
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
    val history = remember(commands) { commands.filter { it.name == "ussd" }.take(10) }
    // The code this screen ran, else the newest one (so a reply that arrived meanwhile shows).
    val current = lastId?.let { id -> commands.firstOrNull { it.id == id } } ?: history.firstOrNull()
    val trimmed = code.trim()
    val valid = USSD.matches(trimmed)
    val busy = runner.isBusy("ussd")

    SectionCard(title = "USSD") {
        Text("Runs a USSD code (balance, plan) and shows the network's reply.", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> setCode(v.filter { it.isDigit() || it == '*' || it == '#' }.take(32)) },
                label = { Text("Code") },
                placeholder = { Text("*123#") },
                singleLine = true,
                enabled = canControl,
                isError = code.isNotEmpty() && !valid,
                supportingText = if (code.isNotEmpty() && !valid) ({ Text("Like *123# or *121*1#") }) else null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    if (valid) runner.run("ussd", buildJsonObject { put("code", trimmed) }, key = "ussd", quiet = true, onSent = { lastId = it.id })
                },
                enabled = canControl && valid && !busy,
                modifier = Modifier.padding(top = 8.dp).height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Run")
            }
        }

        if (current != null) {
            Spacer(Modifier.height(12.dp))
            val look = commandStatus(current)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.args.str("code") ?: "", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = hsm.fg)
                Spacer(Modifier.width(8.dp))
                Pill(look.label, look.tone)
                Spacer(Modifier.weight(1f))
                Text(formatRelative(current.createdAt, now), style = MaterialTheme.typography.labelSmall, color = hsm.faint)
            }
            Spacer(Modifier.height(6.dp))
            val reply = current.error?.takeIf { it.isNotBlank() } ?: current.output?.takeIf { it.isNotBlank() }
            when {
                reply != null -> Text(
                    reply,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current.error.isNullOrBlank()) hsm.fg else hsm.danger,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(hsm.surface2).padding(12.dp),
                )
                current.inFlight() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = hsm.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Waiting for the network's reply…", style = MaterialTheme.typography.bodyMedium, color = hsm.muted)
                }
            }
        }

        if (history.size > 1 || (history.isNotEmpty() && current?.id != history.first().id)) {
            Spacer(Modifier.height(16.dp))
            Text("RECENT CODES", style = MaterialTheme.typography.titleSmall, color = hsm.muted)
            Text("Tap a code to run it again.", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
            Spacer(Modifier.height(4.dp))
            history.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = hsm.border)
                val look = commandStatus(c)
                val cCode = c.args.str("code") ?: ""
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = canControl) { setCode(cCode) }
                        .padding(vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cCode, fontFamily = FontFamily.Monospace, color = hsm.fg)
                        Spacer(Modifier.width(8.dp))
                        Pill(look.label, look.tone)
                        Spacer(Modifier.weight(1f))
                        Text(formatRelative(c.createdAt, now), style = MaterialTheme.typography.labelSmall, color = hsm.faint)
                    }
                    (c.output ?: c.error)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.muted, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
