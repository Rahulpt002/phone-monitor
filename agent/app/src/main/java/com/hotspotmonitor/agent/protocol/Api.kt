package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The REST bodies the Agent uses (shared/src/api.ts). Paths are under /api, e.g.
 * POST <server_url>/api/pair. Dashboard-only endpoints are not mirrored here.
 */

object ApiPaths {
    const val PAIR = "/api/pair"
    const val HEALTHZ = "/healthz"
    const val WS_AGENT = "/ws/agent"
}

/** POST /api/pair (public, rate-limited). */
@Serializable
data class PairRequest(
    val code: String,
    val device: DeviceInfo,
)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    /** Shown once; the Server keeps only its SHA-256. Never log it. */
    @SerialName("device_token") val deviceToken: String,
    /** wss://<host>/ws/agent */
    @SerialName("ws_url") val wsUrl: String,
    /** Absolute base URL of the Server, no trailing slash. */
    @SerialName("server_url") val serverUrl: String,
) {
    override fun toString(): String = "PairResponse(deviceId=$deviceId, wsUrl=$wsUrl, serverUrl=$serverUrl)"
}

/** Error body: HTTP status + { error: ApiErrorCode, message }. */
@Serializable
data class ApiError(
    val error: String,
    val message: String = "",
)
