package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.PairResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Agent knows about its Server after pairing (the token is kept separately, encrypted). */
data class PairingInfo(
    /** Absolute base URL, no trailing slash, e.g. https://hsm.onrender.com */
    val serverUrl: String,
    /** wss://<host>/ws/agent */
    val wsUrl: String,
    val deviceId: String,
    val pairedAtMs: Long,
)

/**
 * Persistent pairing state (docs/protocol.md section 2). The device token lives in [SecureStore]
 * and is only handed out by [token]; never log or display it.
 */
class PairingState(
    private val store: KeyValueStore,
    private val secure: SecureStore,
    private val log: AgentLog,
) {
    private val _info = MutableStateFlow(load())

    /** Null when not paired. */
    val info: StateFlow<PairingInfo?> = _info.asStateFlow()

    private val _paired = MutableStateFlow(_info.value != null)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    val serverUrl: String? get() = _info.value?.serverUrl
    val wsUrl: String? get() = _info.value?.wsUrl
    val deviceId: String? get() = _info.value?.deviceId

    /** The device token for `Authorization: Bearer`, or null when not paired. */
    fun token(): String? = if (_info.value == null) null else secure.getString(TOKEN)

    /** Persists a successful pairing. Returns false when the token could not be stored. */
    @Synchronized
    fun save(response: PairResponse, pairedAtMs: Long): Boolean {
        if (!secure.putString(TOKEN, response.deviceToken)) return false
        val info = PairingInfo(
            serverUrl = response.serverUrl.trimEnd('/'),
            wsUrl = response.wsUrl,
            deviceId = response.deviceId,
            pairedAtMs = pairedAtMs,
        )
        store.putString(KEY_SERVER_URL, info.serverUrl)
        store.putString(KEY_WS_URL, info.wsUrl)
        store.putString(KEY_DEVICE_ID, info.deviceId)
        store.putLong(KEY_PAIRED_AT, info.pairedAtMs)
        _info.value = info
        _paired.value = true
        log.i(TAG, "Paired as ${info.deviceId} with ${info.serverUrl}")
        return true
    }

    /** Forgets the Server and the token (unpair, or the Server revoked the token with 4001). */
    @Synchronized
    fun clear() {
        secure.remove(TOKEN)
        store.remove(KEY_SERVER_URL, KEY_WS_URL, KEY_DEVICE_ID, KEY_PAIRED_AT)
        _info.value = null
        _paired.value = false
        log.i(TAG, "Pairing cleared")
    }

    private fun load(): PairingInfo? {
        val serverUrl = store.getString(KEY_SERVER_URL) ?: return null
        val wsUrl = store.getString(KEY_WS_URL) ?: return null
        val deviceId = store.getString(KEY_DEVICE_ID) ?: return null
        if (!secure.contains(TOKEN)) return null
        return PairingInfo(serverUrl, wsUrl, deviceId, store.getLong(KEY_PAIRED_AT, 0))
    }

    private companion object {
        const val TAG = "pairing"
        const val TOKEN = "device_token"
        const val KEY_SERVER_URL = "pairing.server_url"
        const val KEY_WS_URL = "pairing.ws_url"
        const val KEY_DEVICE_ID = "pairing.device_id"
        const val KEY_PAIRED_AT = "pairing.paired_at"
    }
}
