package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.ApiPaths
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.protocol.PairRequest
import com.hotspotmonitor.agent.protocol.PairResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** A pairing attempt failed; [message] is shown to the person pairing. Never contains the token. */
class PairingException(
    message: String,
    /** HTTP status when the Server answered. */
    val status: Int? = null,
    /** ApiError.error from the Server, e.g. "unauthorized". */
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * POST <server>/api/pair {code, device} -> {device_id, device_token, ws_url, server_url}
 * (docs/protocol.md section 2). Pure HTTP; storing the result is PairingClientImpl's job.
 *
 * @param allowHttp plain http (and ws) are accepted only in debug builds.
 */
internal class PairingApi(private val client: OkHttpClient, private val allowHttp: Boolean) {

    suspend fun pair(serverUrl: String, code: String, device: DeviceInfo): PairResponse {
        val base = PairingUrls.normalizeBaseUrl(serverUrl, allowHttp)
            ?: throw PairingException(
                if (allowHttp) "Not a valid Server URL: '${serverUrl.take(100)}'"
                else "The Server URL must be an https:// address (got '${serverUrl.take(100)}')",
            )
        val cleanCode = PairingUrls.normalizeCode(code)
        if (cleanCode.isEmpty()) throw PairingException("Enter the pairing code shown in the Dashboard")
        val body = AgentJson.encodeToString(PairRequest.serializer(), PairRequest(code = cleanCode, device = device))
        val url = base + ApiPaths.PAIR
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
        } catch (e: IllegalArgumentException) {
            throw PairingException("Not a valid Server URL: '${base.take(100)}'", cause = e)
        }
        return try {
            client.newCall(request).await { response -> parse(response, base) }
        } catch (e: IOException) {
            throw PairingException("Cannot reach $url: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
    }

    private fun parse(response: Response, base: String): PairResponse {
        val text = response.peekBody(MAX_BODY_BYTES).string()
        val json = runCatching { AgentJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (!response.isSuccessful) {
            val error = json?.string("error")
            val message = json?.string("message")?.takeIf { it.isNotBlank() }
                ?: text.take(200).ifBlank { response.message }
            throw PairingException(
                "Pairing failed (HTTP ${response.code}${error?.let { " $it" } ?: ""}): $message",
                status = response.code,
                code = error,
            )
        }
        val deviceId = json?.string("device_id")
        val token = json?.string("device_token")
        if (json == null || deviceId.isNullOrBlank() || token.isNullOrBlank()) {
            throw PairingException("Unexpected answer from the Server (HTTP ${response.code}); is this the Hotspot Monitor Server?", status = response.code)
        }
        val serverUrl = PairingUrls.normalizeBaseUrl(json.string("server_url"), allowHttp) ?: base
        val wsUrl = json.string("ws_url")?.trim()?.takeIf { PairingUrls.isValidWsUrl(it, allowHttp) }
            ?: PairingUrls.agentWsUrl(serverUrl)
        return PairResponse(deviceId = deviceId, deviceToken = token, wsUrl = wsUrl, serverUrl = serverUrl)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BODY_BYTES = 64L * 1024
    }
}

/** Enqueues the call and suspends until [handle] has read the response; cancelling cancels the call. */
internal suspend fun <T> Call.await(handle: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(handle) }
            cont.resumeWith(result)
        }
    })
}
