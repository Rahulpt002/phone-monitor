package com.hotspotmonitor.dashboard.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.controls.ActionButton
import com.hotspotmonitor.dashboard.ui.controls.BtnStyle
import com.hotspotmonitor.dashboard.ui.controls.CommandRunner
import com.hotspotmonitor.dashboard.ui.controls.RunOptions
import com.hotspotmonitor.dashboard.ui.controls.SHELL_TIMEOUT_MAX_S
import com.hotspotmonitor.dashboard.ui.controls.StatusChip
import com.hotspotmonitor.dashboard.ui.controls.isInFlight
import com.hotspotmonitor.dashboard.ui.controls.parseShellTimeout
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Root console tab of System (web System.tsx ConsoleTab): runs `shell` commands. The password that
// worked is kept in memory only while this tab is open, so each command does not re-prompt for it.

internal class ConsoleEntry(val key: Int, val cmd: String, val id: String)

@Stable
internal class ConsoleState {
    val entries = mutableStateListOf<ConsoleEntry>()
    var input by mutableStateOf("")
    var timeout by mutableStateOf("30")
    var busy by mutableStateOf(false)
    /** In memory only; cleared when the tab or the screen closes. */
    var password by mutableStateOf<String?>(null)
    var nextKey = 0
}

/** Recent shell commands for this app run (the web keeps them in sessionStorage). */
internal object ShellHistory {
    val items = mutableStateListOf<String>()

    fun add(cmd: String) {
        items.remove(cmd)
        items.add(cmd)
        while (items.size > 50) items.removeAt(0)
    }
}

private val TermBg = Color(0xFF0D1117)
private val TermFg = Color(0xFFE6EDF3)
private val TermOut = Color(0xFFC9D1D9)
private val TermPrompt = Color(0xFF7EE787)
private val TermErr = Color(0xFFFF7B72)
private val TermMuted = Color(0xFF8B949E)

@Composable
internal fun ConsoleCard(console: ConsoleState, runner: CommandRunner, canControl: Boolean, commands: List<CommandRecord>) {
    if (!canControl) {
        Notice(Tone.WARN, "Root console locked", "Set up two-factor authentication to use the root console.")
        return
    }
    val scope = rememberCoroutineScope()
    val timeoutS = parseShellTimeout(console.timeout)
    val ids = console.entries.map { it.id }.toSet()
    val byId = remember(commands, ids) { commands.filter { it.id in ids }.associateBy { it.id } }

    fun submit() {
        val cmd = console.input.trim()
        val t = timeoutS
        if (cmd.isEmpty() || console.busy || t == null) return
        console.busy = true
        scope.launch {
            try {
                val rec = runner.run(
                    "shell",
                    buildJsonObject {
                        put("cmd", cmd)
                        put("timeout_s", t)
                    },
                    RunOptions(password = console.password, onPassword = { console.password = it }, quiet = true),
                )
                if (rec != null) {
                    ShellHistory.add(cmd)
                    console.entries.add(ConsoleEntry(console.nextKey++, cmd, rec.id))
                    while (console.entries.size > 100) console.entries.removeAt(0)
                    console.input = ""
                }
            } finally {
                console.busy = false
            }
        }
    }

    SectionCard(
        title = "Root console",
        action = if (console.entries.isNotEmpty()) ({ LinkButton("Clear") { console.entries.clear() } }) else null,
    ) {
        Notice(
            Tone.WARN, null,
            "Commands run as root on the phone. Each one is confirmed with your password (remembered while this tab stays open) and stored in the command log.",
        )
        Spacer(Modifier.height(12.dp))
        Terminal(console.entries, byId)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = console.input,
            onValueChange = { console.input = it },
            label = { Text("Command") },
            placeholder = { Text("dumpsys battery", fontFamily = FontFamily.Monospace) },
            leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 4,
            enabled = !console.busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = console.timeout,
                onValueChange = { v -> console.timeout = v.filter { it.isDigit() }.take(4) },
                label = { Text("Timeout (s)") },
                singleLine = true,
                isError = timeoutS == null,
                supportingText = { Text(if (timeoutS == null) "1 to $SHELL_TIMEOUT_MAX_S s" else "max $SHELL_TIMEOUT_MAX_S s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.width(128.dp),
            )
            ActionButton(
                "Run as root",
                Modifier.weight(1f).padding(top = 8.dp),
                style = BtnStyle.DANGER,
                enabled = console.input.isNotBlank() && timeoutS != null,
                busy = console.busy,
            ) { submit() }
        }
        if (ShellHistory.items.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelMedium, color = hsm.muted, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in ShellHistory.items.asReversed().take(15)) {
                    SuggestionChip(
                        onClick = { console.input = h },
                        label = {
                            Text(h, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                        },
                    )
                }
            }
        }
        if (console.password != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Password remembered for this console.", style = MaterialTheme.typography.bodySmall, color = hsm.faint, modifier = Modifier.weight(1f))
                LinkButton("Forget") { console.password = null }
            }
        }
    }
}

@Composable
private fun Terminal(entries: List<ConsoleEntry>, byId: Map<String, CommandRecord>) {
    val scroll = rememberScrollState()
    val lastRec = entries.lastOrNull()?.let { byId[it.id] }
    LaunchedEffect(entries.size, lastRec?.status, lastRec?.output?.length, lastRec?.error?.length) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(14.dp)).background(TermBg)) {
        SelectionContainer {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                if (entries.isEmpty()) {
                    Text("Output appears here.", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermMuted)
                }
                entries.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))
                    TermEntry(e, byId[e.id])
                }
            }
        }
    }
}

@Composable
private fun TermEntry(e: ConsoleEntry, rec: CommandRecord?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("# ", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermPrompt)
            Text(e.cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermFg, modifier = Modifier.weight(1f))
            if (rec != null) {
                Spacer(Modifier.width(8.dp))
                StatusChip(rec)
            }
        }
        val err = rec?.error?.takeIf { it.isNotBlank() }
        val out = rec?.output?.takeIf { it.isNotBlank() }
        when {
            err != null -> Text(err + (out?.let { "\n$it" } ?: ""), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = TermErr)
            out != null -> Text(out, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = TermOut)
            rec == null || isInFlight(rec) -> Text("waiting for the phone…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermMuted)
            else -> Text("(no output)", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TermMuted)
        }
    }
}
