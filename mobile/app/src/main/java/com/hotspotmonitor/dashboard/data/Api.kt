package com.hotspotmonitor.dashboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Failure of a REST call. [code] is the Server's stable error code (shared/src/api.ts ApiErrorCode)
 * or "network" / "timeout" / "invalid_response" for failures before an answer arrived.
 */
class ApiException(val status: Int, val code: String, message: String) : Exception(message) {
    fun isCode(c: String) = code == c
}

/** Human message for any failure (screens show this). */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> when (code) {
        "network" -> "Can't reach the server. Check your internet connection."
        "timeout" -> "The server took too long to answer. It may be waking up; try again in a moment."
        "reauth_required" -> if (message.isNullOrBlank() || message == "Wrong password") "Wrong password. Try again." else message!!
        else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong ($code)."
    }
    else -> message ?: "Something went wrong."
}

/** REST client for the Server (every path is under /api). */
class ApiClient(
    private val http: OkHttpClient,
    private val session: SessionStore,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Optional hooks for session-level answers (set by AppGraph). */
    var onUnauthorized: () -> Unit = {}
    var onEnrollmentRequired: () -> Unit = {}

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
        auth: Boolean = true,
        token: String? = null,
        parse: (String) -> T,
    ): T {
        val base = session.current.serverUrl.trimEnd('/')
        val url = ("$base/api$path").toHttpUrlOrNull()?.newBuilder()?.apply {
            for ((k, v) in query) if (v != null && v.toString().isNotEmpty()) addQueryParameter(k, v.toString())
        }?.build() ?: throw ApiException(0, "bad_request", "The server address is not valid.")
        val sentToken = token ?: if (auth) session.current.token else null
        val req = Request.Builder().url(url).apply {
            if (sentToken != null) header("Authorization", "Bearer $sentToken")
            val rb = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "GET" -> null
                else -> "".toRequestBody(jsonType)
            }
            method(method, rb)
        }.build()

        val res = try {
            http.newCall(req).await()
        } catch (e: SocketTimeoutException) {
            throw ApiException(0, "timeout", "The server took too long to answer.")
        } catch (e: IOException) {
            throw ApiException(0, "network", e.message ?: "network error")
        }
        val text = withContext(Dispatchers.IO) { res.use { it.body?.string().orEmpty() } }
        if (!res.isSuccessful) {
            val err = runCatching { AppJson.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
            val code = err?.error?.takeIf { it.isNotBlank() } ?: when (res.code) {
                401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not_found"; 409 -> "conflict"; 429 -> "rate_limited"
                else -> if (res.code >= 500) "internal" else "bad_request"
            }
            // A wrong password on a re-auth action is also a 401, but must not end the session.
            val current = sentToken != null && sentToken == session.current.token
            if (current && res.code == 401 && code == "unauthorized") onUnauthorized()
            if (current && res.code == 403 && code == "totp_enrollment_required") onEnrollmentRequired()
            throw ApiException(res.code, code, err?.message ?: "HTTP ${res.code}")
        }
        return try {
            parse(text)
        } catch (e: Exception) {
            throw ApiException(res.code, "invalid_response", "The server sent an unreadable response.")
        }
    }

    private suspend fun <T> get(path: String, s: KSerializer<T>, query: Map<String, Any?> = emptyMap()): T =
        call("GET", path, query = query) { AppJson.decodeFromString(s, it) }

    private suspend fun <T> send(method: String, path: String, s: KSerializer<T>, body: JsonElement? = null): T =
        call(method, path, body = body) { AppJson.decodeFromString(s, it) }

    private suspend fun sendNoContent(method: String, path: String, body: JsonElement? = null) {
        call(method, path, body = body) { }
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    // --- auth ----------------------------------------------------------------------------------

    suspend fun login(username: String, password: String): LoginResponse = call(
        "POST", "/auth/login", auth = false,
        body = AppJson.encodeToJsonElement(LoginRequest.serializer(), LoginRequest(username, password, session.deviceToken(username))),
    ) { AppJson.decodeFromString(LoginResponse.serializer(), it) }

    suspend fun totpLogin(pendingToken: String, code: String, username: String): SessionResponse = call(
        "POST", "/auth/totp", auth = false,
        body = AppJson.encodeToJsonElement(TotpLoginRequest.serializer(), TotpLoginRequest(pendingToken, code, session.deviceToken(username))),
    ) { AppJson.decodeFromString(SessionResponse.serializer(), it) }

    suspend fun me(): MeResponse = get("/auth/me", MeResponse.serializer())

    /** Ends [token]'s session on the Server (best effort). */
    suspend fun logout(token: String) {
        runCatching { call("POST", "/auth/logout", token = token) { } }
    }

    suspend fun totpSetup(): TotpSetupResponse = send("POST", "/auth/totp/setup", TotpSetupResponse.serializer())
    suspend fun totpEnable(code: String) = sendNoContent("POST", "/auth/totp/enable", obj("code" to code))
    suspend fun totpDisable(password: String, code: String) = sendNoContent("POST", "/auth/totp/disable", obj("password" to password, "code" to code))
    suspend fun changePassword(current: String, next: String) = sendNoContent("POST", "/auth/password", obj("current" to current, "next" to next))

    // --- users (admin) -------------------------------------------------------------------------

    suspend fun users(): List<UserInfo> = get("/users", ListSerializer(UserInfo.serializer()))
    suspend fun createUser(username: String, password: String, role: String, reauthPassword: String): UserInfo =
        send("POST", "/users", UserInfo.serializer(), obj("username" to username, "password" to password, "role" to role, "reauth_password" to reauthPassword))
    suspend fun updateUser(id: Long, role: String? = null, password: String? = null, resetTotp: Boolean = false, reauthPassword: String): UserInfo =
        send("PATCH", "/users/$id", UserInfo.serializer(), obj("role" to role, "password" to password, "reset_totp" to (if (resetTotp) true else null), "reauth_password" to reauthPassword))
    suspend fun deleteUser(id: Long, reauthPassword: String) = sendNoContent("DELETE", "/users/$id", obj("reauth_password" to reauthPassword))

    // --- pairing and devices -------------------------------------------------------------------

    suspend fun createPairCode(name: String?): PairCodeResponse =
        send("POST", "/pair/codes", PairCodeResponse.serializer(), obj("name" to name?.takeIf { it.isNotBlank() }))
    suspend fun devices(): List<DeviceSummary> = get("/devices", ListSerializer(DeviceSummary.serializer()))
    suspend fun device(id: String): DeviceSnapshot = get("/device", DeviceSnapshot.serializer(), mapOf("device_id" to id))
    suspend fun renameDevice(id: String, name: String): DeviceSummary = send("PATCH", "/devices/$id", DeviceSummary.serializer(), obj("name" to name))
    suspend fun unpairDevice(id: String, password: String) = sendNoContent("DELETE", "/devices/$id", obj("password" to password))

    // --- history -------------------------------------------------------------------------------

    suspend fun telemetry(deviceId: String?, metric: String, fromIso: String, toIso: String, res: String = "auto"): TelemetryHistoryResponse =
        get("/telemetry", TelemetryHistoryResponse.serializer(), mapOf("device_id" to deviceId, "metric" to metric, "from" to fromIso, "to" to toIso, "res" to res))

    // --- commands ------------------------------------------------------------------------------

    suspend fun sendCommand(deviceId: String?, name: String, args: JsonObject = JsonObject(emptyMap()), confirmed: Boolean = false, password: String? = null): CommandRecord =
        send("POST", "/commands", CommandRecord.serializer(), obj(
            "device_id" to deviceId, "name" to name, "args" to args,
            "confirmed" to (if (confirmed) true else null), "password" to password,
        ))
    suspend fun keepCommand(id: String): CommandRecord = send("POST", "/commands/$id/keep", CommandRecord.serializer())
    suspend fun commands(deviceId: String?, limit: Int = 50, before: String? = null): List<CommandRecord> =
        get("/commands", ListSerializer(CommandRecord.serializer()), mapOf("device_id" to deviceId, "limit" to limit, "before" to before))

    // --- events, alerts, sms -------------------------------------------------------------------

    suspend fun events(deviceId: String?, kind: String? = null, limit: Int = 100, before: String? = null): List<StoredEvent> =
        get("/events", ListSerializer(StoredEvent.serializer()), mapOf("device_id" to deviceId, "kind" to kind, "limit" to limit, "before" to before))
    suspend fun alerts(deviceId: String?, limit: Int = 100, before: String? = null): List<AlertRecord> =
        get("/alerts", ListSerializer(AlertRecord.serializer()), mapOf("device_id" to deviceId, "limit" to limit, "before" to before))
    suspend fun testAlert(): AlertRecord = send("POST", "/alerts/test", AlertRecord.serializer())
    suspend fun sms(deviceId: String?, limit: Int = 100): List<SmsRecord> =
        get("/sms", ListSerializer(SmsRecord.serializer()), mapOf("device_id" to deviceId, "limit" to limit))

    // --- settings ------------------------------------------------------------------------------

    suspend fun settings(deviceId: String): SettingsResponse = get("/settings", SettingsResponse.serializer(), mapOf("device_id" to deviceId))
    suspend fun putSettings(deviceId: String, settings: JsonObject, password: String? = null): SettingsResponse =
        send("PUT", "/settings", SettingsResponse.serializer(), obj("device_id" to deviceId, "settings" to settings, "password" to password))

    // --- finder --------------------------------------------------------------------------------

    suspend fun spots(deviceId: String): FinderSpotsResponse = get("/finder/spots", FinderSpotsResponse.serializer(), mapOf("device_id" to deviceId))

    // --- push (Firebase) -----------------------------------------------------------------------

    suspend fun registerFcm(token: String) = sendNoContent("POST", "/push/fcm/register", obj("token" to token, "platform" to "android"))
    suspend fun unregisterFcm(token: String, sessionToken: String) {
        runCatching { call("POST", "/push/fcm/unregister", body = obj("token" to token), token = sessionToken) { } }
    }

    // --- remote screen -------------------------------------------------------------------------

    suspend fun createScreenSession(deviceId: String, quality: String, password: String, code: String): ScreenSessionResponse =
        send("POST", "/screen/sessions", ScreenSessionResponse.serializer(), obj("device_id" to deviceId, "quality" to quality, "password" to password, "code" to code))
    suspend fun stopScreenSession(id: String) = runCatching { sendNoContent("DELETE", "/screen/sessions/$id") }

    // --- misc ----------------------------------------------------------------------------------

    suspend fun config(): PublicConfig = call("GET", "/config", auth = false) { AppJson.decodeFromString(PublicConfig.serializer(), it) }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
