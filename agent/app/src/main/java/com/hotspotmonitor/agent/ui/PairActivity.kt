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
import com.hotspotmonitor.agent.sync.PairLink
import com.hotspotmonitor.agent.sync.PairingUrls
import kotlinx.coroutines.delay

/**
 * Handles the pairing deep link `hsm://pair?server=<urlencoded base url>&code=<code>` from a QR
 * code or `adb shell "am start -a android.intent.action.VIEW -d '<deep_link>'"`.
 *
 * With a working screen it shows the Server and one Pair button. Launched with `--ez auto true`
 * (dead screen, plan 6.1 "Headless operation") it pairs immediately; replacing an existing pairing
 * that way also needs `--ez replace true`, so another app cannot silently re-point a paired phone.
 */
class PairActivity : ComponentActivity() {
    private lateinit var graph: AgentGraph

    private var link by mutableStateOf<PairLink?>(null)
    private var linkError by mutableStateOf<String?>(null)
    private var autoNote by mutableStateOf<String?>(null)
    private var autoStarted by mutableStateOf(false)

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
        val auto = intent?.getBooleanExtra(PairLink.EXTRA_AUTO, false) == true
        if (!auto) return
        val replace = intent?.getBooleanExtra(PairLink.EXTRA_REPLACE, false) == true
        if (graph.pairingState.paired.value && !replace) {
            autoNote = "Already paired. Automatic pairing replaces a pairing only with --ez replace true."
            graph.log.w(TAG, "Automatic pairing refused: already paired and no replace extra")
            return
        }
        graph.log.i(TAG, "Automatic (headless) pairing requested")
        autoStarted = PairingRunner.start(this, parsed.server, parsed.code)
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
                    Text("Pairing… a sleeping Server can take up to a minute.")
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
                    Button(onClick = { PairingRunner.start(this@PairActivity, target.server, target.code) }) { Text("Try again") }
                }
                PairUiState.Idle -> {
                    Button(onClick = { PairingRunner.start(this@PairActivity, target.server, target.code) }) { Text("Pair") }
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
