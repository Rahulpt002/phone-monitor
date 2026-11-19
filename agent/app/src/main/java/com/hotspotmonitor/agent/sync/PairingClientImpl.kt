package com.hotspotmonitor.agent.sync

import android.os.Build
import com.hotspotmonitor.agent.BuildConfig
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.PairingClient
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.protocol.PairResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Pairing over HTTP (docs/protocol.md section 2): POST /api/pair with the one-time code and this
 * phone's DeviceInfo, then stores device id, server URL, ws URL and the token (encrypted, via
 * PairingState/SecureStore) and lets the SyncClient connect. https only, except in debug builds
 * (LAN dev server).
 */
class PairingClientImpl(private val graph: AgentGraph) : PairingClient {
    private val api: PairingApi by lazy {
        PairingApi(
            graph.http.newBuilder()
                // A sleeping free-tier Server can take close to a minute to answer the first request.
                .callTimeout(PAIR_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(PAIR_TIMEOUT_S, TimeUnit.SECONDS)
                // Never follow a redirect from https to http (or the other way round).
                .followSslRedirects(false)
                .build(),
            allowHttp = BuildConfig.DEBUG,
        )
    }

    private val mutex = Mutex()

    override suspend fun pair(serverUrl: String, code: String): Result<PairResponse> {
        return mutex.withLock { pairLocked(serverUrl, code) }
    }

    private suspend fun pairLocked(serverUrl: String, code: String): Result<PairResponse> {
        return try {
            val device = deviceInfo()
            graph.log.i(TAG, "Pairing with ${serverUrl.trim().take(200)}")
            val response = api.pair(serverUrl, code, device)
            if (graph.pairingState.save(response, graph.clock.systemNowMs())) {
                graph.log.i(TAG, "Paired as ${response.deviceId} with ${response.serverUrl}")
                graph.sync.onPaired()
                Result.success(response)
            } else {
                Result.failure(
                    PairingException(
                        "Paired, but the device token could not be stored securely (Android Keystore error). " +
                            "Create a new code and try again.",
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log.w(TAG, "Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    override suspend fun unpair() {
        val deviceId = graph.pairingState.deviceId
        graph.pairingState.clear()
        graph.sync.onUnpaired()
        graph.log.i(TAG, "Unpaired locally" + (deviceId?.let { " (was $it)" } ?: ""))
    }

    /** DeviceInfo from the telemetry module; a Build-based copy if it is slow or failing. */
    private suspend fun deviceInfo(): DeviceInfo {
        val collected = try {
            withTimeoutOrNull(DEVICE_INFO_TIMEOUT_MS) { graph.telemetry.collectStatic().device }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not collect device info; using basic Build values", e)
            null
        }
        // The Server assigns the id; an old one from a previous pairing must not be sent.
        return (collected ?: fallbackDeviceInfo()).copy(deviceId = null)
    }

    private fun fallbackDeviceInfo(): DeviceInfo = DeviceInfo(
        deviceId = null,
        manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
        model = Build.MODEL.orEmpty().ifBlank { "unknown" },
        device = Build.DEVICE.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        sdk = Build.VERSION.SDK_INT,
        buildId = Build.ID,
        agentVersion = graph.agentVersion,
        agentVersionCode = graph.agentVersionCode,
        profileId = runCatching { graph.profiles.current.id }.getOrDefault("generic"),
        rooted = graph.shell.rooted.value == true,
        rootImpl = null,
    )

    private companion object {
        const val TAG = "pairing"
        const val PAIR_TIMEOUT_S = 90L
        const val DEVICE_INFO_TIMEOUT_MS = 15_000L
    }
}
