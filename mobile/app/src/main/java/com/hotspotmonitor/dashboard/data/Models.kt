package com.hotspotmonitor.dashboard.data

import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.HeatInfo
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.PositionInfo
import com.hotspotmonitor.agent.protocol.PowerInfo
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.SystemInfo
import com.hotspotmonitor.agent.protocol.TetherInfo
import com.hotspotmonitor.agent.protocol.TrafficInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// Dashboard-side shapes of shared/src/protocol.ts and api.ts. Fields the Server may extend with new
// values (event kinds, statuses, roles) are plain strings so an unknown value never breaks parsing.

/** Decodes [element] with [serializer], or null when it is missing or does not fit. */
fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement?): T? {
    if (element == null || element is JsonNull) return null
    return runCatching { AppJson.decodeFromJsonElement(serializer, element) }.getOrNull()
}

@Serializable
data class UserInfo(
    val id: Long,
    val username: String,
    val role: String,
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
) {
    val isAdmin: Boolean get() = role == "admin"
}

@Serializable
data class DeviceSummary(
    @SerialName("device_id") val deviceId: String,
    val name: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("paired_at") val pairedAt: String = "",
    val info: JsonObject? = null,
) {
    val deviceInfo: DeviceInfo? get() = decodeOrNull(DeviceInfo.serializer(), info)
}

@Serializable
data class DeviceSnapshot(
    @SerialName("device_id") val deviceId: String,
    val name: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("paired_at") val pairedAt: String = "",
    val info: JsonObject? = null,
    @SerialName("connected_since") val connectedSince: String? = null,
    /** Latest value of every telemetry group (radio, net, power, ...). */
    val state: JsonObject = JsonObject(emptyMap()),
    /** When each group was last updated. */
    @SerialName("state_ts") val stateTs: Map<String, String> = emptyMap(),
    @SerialName("settings_version") val settingsVersion: Int = 0,
    val baseline: JsonObject? = null,
    val outbox: Int? = null,
    @SerialName("pending_reverts") val pendingReverts: List<CommandRecord> = emptyList(),
) {
    val deviceInfo: DeviceInfo? get() = decodeOrNull(DeviceInfo.serializer(), info)
    val groups: Groups get() = Groups(state)
    val baselineSpot: FinderSpot? get() = decodeOrNull(FinderSpot.serializer(), baseline)

    fun summary() = DeviceSummary(deviceId, name, online, lastSeen, pairedAt, info)

    companion object {
        fun empty(id: String) = DeviceSnapshot(deviceId = id, name = id)
    }
}

/** Typed view of DeviceSnapshot.state; each group decodes on its own so one bad group stays local. */
class Groups(val raw: JsonObject) {
    val radio: RadioInfo? by lazy { decodeOrNull(RadioInfo.serializer(), raw["radio"]) }
    val net: NetInfo? by lazy { decodeOrNull(NetInfo.serializer(), raw["net"]) }
    val traffic: TrafficInfo? by lazy { decodeOrNull(TrafficInfo.serializer(), raw["traffic"]) }
    val tether: TetherInfo? by lazy { decodeOrNull(TetherInfo.serializer(), raw["tether"]) }
    val power: PowerInfo? by lazy { decodeOrNull(PowerInfo.serializer(), raw["power"]) }
    val heat: HeatInfo? by lazy { decodeOrNull(HeatInfo.serializer(), raw["heat"]) }
    val system: SystemInfo? by lazy { decodeOrNull(SystemInfo.serializer(), raw["system"]) }
    val position: PositionInfo? by lazy { decodeOrNull(PositionInfo.serializer(), raw["position"]) }
    val device: DeviceInfo? by lazy { decodeOrNull(DeviceInfo.serializer(), raw["device"]) }
    val capabilities: Map<String, CapabilityEntry> by lazy {
        decodeOrNull(MapSerializer(String.serializer(), CapabilityEntry.serializer()), raw["capabilities"]) ?: emptyMap()
    }
}

/** The telemetry group keys of TelemetryGroups (merge order does not matter). */
val GROUP_KEYS = listOf("radio", "net", "traffic", "tether", "power", "heat", "system", "position", "device", "capabilities")

@Serializable
data class CommandRecord(
    val id: String,
    @SerialName("device_id") val deviceId: String = "",
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val source: String = "dashboard",
    val user: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("acked_at") val ackedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val ok: Boolean? = null,
    val output: String? = null,
    val error: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("revert_confirm") val revertConfirm: String? = null,
    @SerialName("revert_deadline") val revertDeadline: String? = null,
)

@Serializable
data class StoredEvent(
    val id: String,
    val ts: String,
    val kind: String,
    val severity: String = "info",
    val message: String = "",
    val data: JsonObject? = null,
    @SerialName("device_id") val deviceId: String = "",
)

@Serializable
data class AlertRecord(
    val id: Long,
    @SerialName("device_id") val deviceId: String? = null,
    val kind: String,
    val severity: String = "info",
    val title: String = "",
    val message: String = "",
    val ts: String,
    val channels: List<String> = emptyList(),
    val data: JsonObject? = null,
)

@Serializable
data class SmsRecord(
    val id: String,
    val direction: String,
    val peer: String,
    val body: String,
    val at: String,
    @SerialName("device_id") val deviceId: String = "",
)

// --- REST bodies -----------------------------------------------------------------------------

@Serializable
data class ApiErrorBody(val error: String = "", val message: String = "")

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_token") val deviceToken: String? = null,
)

@Serializable
data class LoginResponse(
    @SerialName("totp_required") val totpRequired: Boolean = false,
    val token: String? = null,
    val user: UserInfo? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("device_token") val deviceToken: String? = null,
    @SerialName("pending_token") val pendingToken: String? = null,
)

@Serializable
data class TotpLoginRequest(
    @SerialName("pending_token") val pendingToken: String,
    val code: String,
    @SerialName("device_token") val deviceToken: String? = null,
)

@Serializable
data class SessionResponse(
    val token: String,
    val user: UserInfo,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("device_token") val deviceToken: String? = null,
)

@Serializable
data class MeResponse(
    val user: UserInfo,
    @SerialName("totp_enrollment_required") val totpEnrollmentRequired: Boolean = false,
)

@Serializable
data class TotpSetupResponse(val secret: String, @SerialName("otpauth_url") val otpauthUrl: String)

@Serializable
data class PairCodeResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("deep_link") val deepLink: String,
    @SerialName("adb_command") val adbCommand: String,
)

@Serializable
data class SettingsResponse(
    @SerialName("device_id") val deviceId: String,
    val settings: JsonObject,
    val version: Int,
) {
    val typed: Settings get() = decodeOrNull(Settings.serializer(), settings) ?: Settings.DEFAULT
}

@Serializable
data class FinderSpotsResponse(
    val spots: List<JsonObject> = emptyList(),
    @SerialName("baseline_id") val baselineId: String? = null,
)

@Serializable
data class TelemetryHistoryResponse(
    val metric: String,
    val res: String = "raw",
    /** [unix ms, value] pairs; value may be null (gap). */
    val points: List<List<Double?>> = emptyList(),
)

@Serializable
data class PublicConfig(
    val version: String = "",
    @SerialName("telegram_configured") val telegramConfigured: Boolean = false,
    @SerialName("push_configured") val pushConfigured: Boolean = false,
    @SerialName("fcm_configured") val fcmConfigured: Boolean = false,
)

@Serializable
data class ScreenSessionResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_id") val deviceId: String,
    val quality: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** Finder live values with when this app received them (for staleness). */
data class LiveFinder(val live: FinderLive, val receivedAtMs: Long)
