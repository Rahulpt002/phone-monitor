package com.hotspotmonitor.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.sync.PairingUrls
import kotlinx.coroutines.launch

/** Pairing state, the manual form (server URL + code), the headless options and unpairing. */
@Composable
fun PairingScreen(graph: AgentGraph) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val now = rememberNowMs(30_000)
    val info by graph.pairingState.info.collectAsStateWithLifecycle()
    val conn by graph.sync.state.collectAsStateWithLifecycle()
    val pairState by PairingRunner.state.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf(graph.pairingState.serverUrl ?: "https://") }
    var code by rememberSaveable { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pairing", style = MaterialTheme.typography.titleLarge)

        SectionCard("This phone") {
            val current = info
            if (current == null) {
                StatusPill("Not paired", colors.bad)
                HintText("Create a pairing code in the Dashboard (Settings > Pair phone), then scan its QR code, enter it below, or run its adb command.")
            } else {
                StatusPill("Paired", colors.good)
                InfoRow("Server", current.serverUrl)
                InfoRow("Device id", current.deviceId, monospace = true)
                InfoRow("Paired", formatAgo(current.pairedAtMs, now))
                InfoRow("Link", com.hotspotmonitor.agent.service.ServiceStatusText.linkLabel(conn.phase))
            }
        }

        SectionCard(if (info == null) "Pair with a Server" else "Pair again (replaces the current pairing)") {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it.trim() },
                label = { Text("Server URL") },
                placeholder = { Text("https://hsm.onrender.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(MAX_CODE_CHARS) },
                label = { Text("Pairing code") },
                placeholder = { Text("8 characters") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            val running = pairState is PairUiState.Running
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !running && server.length > "https://".length && PairingUrls.normalizeCode(code).isNotEmpty(),
                    onClick = { PairingRunner.start(context, server, code) },
                ) { Text("Pair") }
                if (running) CircularProgressIndicator(Modifier.size(28.dp))
            }
            when (val s = pairState) {
                is PairUiState.Running -> HintText("Contacting ${s.server}… a sleeping free-tier Server can take up to a minute to wake up.")
                is PairUiState.Success -> Text("Paired as ${s.deviceId} with ${s.serverUrl}.", color = colors.good)
                is PairUiState.Failed -> Text(s.message, color = colors.bad, style = MaterialTheme.typography.bodyMedium)
                PairUiState.Idle -> Unit
            }
        }

        SectionCard("Headless pairing (dead screen)") {
            HintText(
                "The Dashboard shows a QR code with a link like the one below; any QR scanner opens it in this app, " +
                    "which asks for one tap on Pair. With a broken screen, run the adb command instead: " +
                    "'--ez auto true' pairs without a tap. Add '--ez replace true' to replace an existing pairing.",
            )
            val exampleServer = info?.serverUrl ?: server.takeIf { it.length > "https://".length } ?: "https://hsm.onrender.com"
            val exampleCode = PairingUrls.normalizeCode(code).ifEmpty { "ABCD2345" }
            val link = PairingUrls.deepLink(exampleServer, exampleCode)
            val adb = PairingUrls.adbCommand(link, auto = true, replace = info != null)
            Text("Deep link", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(link, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Text("adb command", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(adb, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(adb))
                toast(context, "adb command copied")
            }) { Text("Copy adb command") }
        }

        if (info != null) {
            SectionCard("Unpair") {
                HintText(
                    "Forgets the Server and the device token on this phone. The Dashboard keeps the device's " +
                        "history; remove the device there too to revoke the token.",
                )
                OutlinedButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone", color = colors.bad) }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("The Agent stops sending data until it is paired again. Buffered data stays on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    PairingRunner.reset()
                    graph.appScope.launch { graph.pairing.unpair() }
                }) { Text("Unpair", color = colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

private const val MAX_CODE_CHARS = 16
