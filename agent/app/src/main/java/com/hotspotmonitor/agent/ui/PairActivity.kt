package com.hotspotmonitor.agent.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.BuildConfig
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.PairingInfo
import com.hotspotmonitor.agent.sync.PairLaunch
import com.hotspotmonitor.agent.sync.PairLink
import com.hotspotmonitor.agent.sync.PairingUrls
import kotlinx.coroutines.delay

/**
 * Handles the pairing deep link `hsm://pair?server=<urlencoded base url>&code=<code>`.
 *
 * Two entries (docs/protocol.md 2):
 * - the browsable link (QR code, another app, a web page): shows the Server and a Pair button; a
 *   tap is always needed, and replacing an existing pairing needs a second confirmation. The
 *   `auto` / `replace` extras are ignored here;
 * - the activity-alias `.ui.AdbPair`, guarded by android.permission.DUMP so only adb (shell uid) or
 *   root can start it: `adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a
 *   android.intent.action.VIEW -d '<deep_link>' --ez auto true"` pairs at once (dead screen, plan
 *   6.1 "Headless operation"); replacing an existing pairing that way also needs `--ez replace true`.
 * [PairLaunch.decide] makes that choice from the launch intent's component.
 */
class PairActivity : ComponentActivity() {
    private lateinit var graph: AgentGraph

    private var link by mutableStateOf<PairLink?>(null)
    private var linkError by mutableStateOf<String?>(null)
    private var autoNote by mutableStateOf<String?>(null)
    private var autoStarted by mutableStateOf(false)

    /** The user tapped Pair while paired: asking to confirm the replacement. */
    private var confirmReplace by mutableStateOf(false)

    /** The link last approved to replace a pairing (the Replace confirmation, or adb's auto entry). */
    private var approvedLink by mutableStateOf<PairLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AgentApp.graph(this)
        // Visible over the lock screen and wakes the display, for pairing at the pole.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handle(intent, firstLaunch = savedInstanceState == null)
        addOnNewIntentListener { newIntent ->
            setIntent(newIntent)
            handle(newIntent, firstLaunch = true)
        }
        setContent { AgentTheme { PairContent() } }
    }

    private fun handle(intent: Intent?, firstLaunch: Boolean) {
        val parsed = PairLink.parse(intent?.data?.toString())
        autoNote = null
        if (parsed == null) {
            link = null
            linkError = "This is not a valid pairing link. Expected hsm://pair?server=<url>&code=<code>."
            graph.log.w(TAG, "Invalid pairing link received")
            return
        }
        if (PairingUrls.normalizeBaseUrl(parsed.server, BuildConfig.DEBUG) == null) {
            link = null
            linkError = "The Server address in this link is not an https:// URL: ${parsed.server.take(100)}"
            graph.log.w(TAG, "Pairing link with an unusable server URL")
            return
        }
        link = parsed
        linkError = null
        graph.log.i(TAG, "Pairing link for ${parsed.server.take(200)}")
        if (!firstLaunch) return
        PairingRunner.reset()
        confirmReplace = false
        val launch = PairLaunch.decide(
            componentClass = intent?.component?.className,
            auto = intent?.getBooleanExtra(PairLink.EXTRA_AUTO, false) == true,
            replace = intent?.getBooleanExtra(PairLink.EXTRA_REPLACE, false) == true,
            paired = graph.pairingState.paired.value,
        )
        when (launch) {
            PairLaunch.TAP -> Unit
            PairLaunch.TAP_AUTO_IGNORED -> {
                autoNote = "Automatic pairing works only through adb (am start -n ${PairingUrls.ADB_COMPONENT}). Tap Pair to continue."
                graph.log.w(TAG, "Pairing link with the auto extra from outside the adb entry: waiting for a tap")
            }
            PairLaunch.TAP_ALREADY_PAIRED -> {
                autoNote = "Already paired. Automatic pairing replaces a pairing only with --ez replace true."
                graph.log.w(TAG, "Automatic pairing refused: already paired and no replace extra")
            }
            PairLaunch.AUTO -> {
                graph.log.i(TAG, "Automatic (headless) pairing requested through adb")
                autoStarted = startPairing(parsed, approvesReplace = true)
            }
        }
    }

    /**
     * [approvesReplace]: this start was approved to replace an existing pairing, so "Try again" for
     * the same link needs no new confirmation. A tap while unpaired approves only pairing.
     */
    private fun startPairing(target: PairLink, approvesReplace: Boolean): Boolean {
        confirmReplace = false
        val started = PairingRunner.start(this, target.server, target.code)
        if (started && approvesReplace) approvedLink = target
        return started
    }

    @Composable
    private fun ReplaceConfirmation(target: PairLink, paired: PairingInfo, colors: StatusColors) {
        // Second confirmation: this link would re-point a paired phone to another Server.
        Text(
            "Replace the pairing with ${paired.serverUrl} by ${target.server}? The phone then reports to " +
                "and takes commands from that Server only.",
            color = colors.bad,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { startPairing(target, approvesReplace = true) }) { Text("Replace pairing") }
        OutlinedButton(onClick = { finish() }) { Text("Keep the current pairing") }
    }

    @Composable
    private fun PairContent() {
        val state by PairingRunner.state.collectAsStateWithLifecycle()
        val paired by graph.pairingState.info.collectAsStateWithLifecycle()
        val colors = LocalStatusColors.current
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Pair with Server", style = MaterialTheme.typography.titleLarge)
                val target = link
                if (target == null) {
                    Text(linkError ?: "Reading pairing link…", color = colors.bad)
                    OutlinedButton(onClick = { finish() }) { Text("Close") }
                } else {
                    PairDetails(target, state, paired, colors)
                }
            }
        }
    }

    @Composable
    private fun PairDetails(target: PairLink, state: PairUiState, paired: PairingInfo?, colors: StatusColors) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            InfoRow("Server", target.server)
            InfoRow("Code", PairingUrls.normalizeCode(target.code), monospace = true)
            paired?.let { current ->
                Text(
                    "This phone is paired with ${current.serverUrl} as ${current.deviceId}. Pairing again replaces that.",
                    color = colors.warn,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            autoNote?.let { Text(it, color = colors.warn) }
            when (state) {
                is PairUiState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    val other = if (state.server != target.server.trim()) " with ${state.server}" else ""
                    Text("Pairing$other… a sleeping Server can take up to a minute.")
                }
                is PairUiState.Success -> {
                    Text(
                        "Paired as ${state.deviceId}. The Agent is connecting.",
                        color = colors.good,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = {
                        startActivity(Intent(this@PairActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }) { Text("Open the app") }
                    if (autoStarted) {
                        LaunchedEffect(state) {
                            delay(AUTO_CLOSE_MS)
                            finish()
                        }
                    }
                }
                is PairUiState.Failed -> {
                    Text(state.message, color = colors.bad)
                    if (confirmReplace && paired != null) {
                        ReplaceConfirmation(target, paired, colors)
                    } else {
                        // The link shown may have arrived (onNewIntent) while another pairing ran:
                        // retrying it over an existing pairing needs the same confirmation as Pair.
                        Button(onClick = {
                            if (PairLaunch.retryNeedsConfirm(target, approvedLink, paired != null)) {
                                confirmReplace = true
                            } else {
                                startPairing(target, approvesReplace = paired != null)
                            }
                        }) { Text("Try again") }
                    }
                }
                PairUiState.Idle -> if (confirmReplace && paired != null) {
                    ReplaceConfirmation(target, paired, colors)
                } else {
                    Button(onClick = {
                        if (paired != null) {
                            confirmReplace = true
                        } else {
                            startPairing(target, approvesReplace = false)
                        }
                    }) { Text("Pair") }
                    OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                }
            }
        }
    }

    private companion object {
        const val TAG = "pair"
        const val AUTO_CLOSE_MS = 5_000L
    }
}
